//! Sektionsspeicher: die Blöcke der geladenen Minecraft-Chunks auf der GPU.
//!
//! Der Render-Thread (Java) legt Sektionen ab oder entfernt sie; die Tabelle
//! liegt maßgeblich auf dem Host und wird bei Änderung als Ganzes hochgeladen.
//! Pyrits Generator (Hintergrund-Thread) liest die GPU-Kopie. Geänderte
//! Bereiche werden in Pyrit ungültig gemacht und neu erzeugt.

const std = @import("std");
const pyrit = @import("pyrit");
const cuda = pyrit.cuda;
const t = @import("types.zig");
const root = @import("root.zig");

const G = &root.G;
const gpa = std.heap.c_allocator;

const tomb: u64 = std.math.maxInt(u64);
const slot_bytes: usize = t.slot_bytes;

pub const Store = struct {
    mutex: std.c.pthread_mutex_t = std.c.PTHREAD_MUTEX_INITIALIZER,
    cap: u32 = 0,
    keys: []u64 = &.{},
    vals: []u32 = &.{},
    used: u32 = 0,
    d_keys: u64 = 0,
    d_vals: u64 = 0,
    table_dirty: bool = false,
    // Pool
    pool: u64 = 0,
    pool_slots: u32 = 0,
    free_slots: std.ArrayList(u32) = .empty,
    next_slot: u32 = 0,
    // Zustände
    d_attr: u64 = 0,
    d_flags: u64 = 0,
    nstates: u32 = 0,
    // geladene Spalten (Chunk x/z) – Chunks darin sind vollständig bekannt
    columns: std.AutoHashMapUnmanaged(u64, void) = .empty,
    // Simulationsbereich (Chunk-Mitte, Radius in Chunks; -1 = aus): dort liefert der Server die echten Chunks, der Fernbereich
    // wird dort nie berechnet
    sim_cx: i32 = 0,
    sim_cz: i32 = 0,
    sim_r: i32 = -1,
    // ungültig gewordene Blockbereiche, bis zum nächsten flush
    dirty: std.ArrayList([6]i64) = .empty,
    y_min: i32 = -64,
    y_max: i32 = 320,
};

pub var S: Store = .{};

fn lock() void {
    _ = std.c.pthread_mutex_lock(&S.mutex);
}
fn unlock() void {
    _ = std.c.pthread_mutex_unlock(&S.mutex);
}

fn colKey(cx: i32, cz: i32) u64 {
    return (@as(u64, @as(u32, @bitCast(cx))) << 32) | @as(u32, @bitCast(cz));
}

fn ensureTable() bool {
    if (S.cap != 0) return true;
    S.cap = 1 << 18;
    S.keys = gpa.alloc(u64, S.cap) catch return false;
    S.vals = gpa.alloc(u32, S.cap) catch return false;
    @memset(S.keys, 0);
    @memset(S.vals, 0);
    if (G.drv.cuMemAlloc_v2(&S.d_keys, S.cap * 8) != 0) return false;
    if (G.drv.cuMemAlloc_v2(&S.d_vals, S.cap * 4) != 0) return false;
    _ = G.drv.cuMemsetD8_v2(S.d_keys, 0, S.cap * 8);
    return true;
}

fn findSlot(k: u64) ?u32 {
    var h: u32 = @truncate(t.secHash(k) & (S.cap - 1));
    var guard: u32 = 0;
    while (guard < S.cap) : (guard += 1) {
        const kk = S.keys[h];
        if (kk == k) return h;
        if (kk == 0) return null;
        h = (h + 1) & (S.cap - 1);
    }
    return null;
}

fn insertSlot(k: u64) ?u32 {
    if (findSlot(k)) |h| return h;
    var h: u32 = @truncate(t.secHash(k) & (S.cap - 1));
    var guard: u32 = 0;
    while (guard < S.cap) : (guard += 1) {
        const kk = S.keys[h];
        if (kk == 0 or kk == tomb) {
            S.keys[h] = k;
            S.vals[h] = 0xffff_ffff; // noch ohne Inhalt
            S.used += 1;
            return h;
        }
        h = (h + 1) & (S.cap - 1);
    }
    return null;
}

fn allocPoolSlot() ?u32 {
    if (S.free_slots.pop()) |s| return s;
    if (S.next_slot >= S.pool_slots) {
        // Pool vergrößern: alles anhalten (auch Pyrits Arbeiter-Stream), kopieren
        const n: u32 = if (S.pool_slots == 0) 8192 else S.pool_slots * 2;
        var p: u64 = 0;
        if (G.drv.cuMemAlloc_v2(&p, @as(usize, n) * slot_bytes) != 0) return null;
        _ = G.drv.cuCtxSynchronize();
        if (S.pool != 0) {
            _ = G.drv.cuMemcpyDtoD_v2(p, S.pool, @as(usize, S.pool_slots) * slot_bytes);
            _ = G.drv.cuCtxSynchronize();
            // alter Pool bleibt bestehen: ein Generatorauftrag könnte ihn noch lesen
        }
        S.pool = p;
        S.pool_slots = n;
    }
    const s = S.next_slot;
    S.next_slot += 1;
    return s;
}

fn markDirty(sx: i32, sy: i32, sz: i32) void {
    // eine Sektion plus ein Block Rand (Nachbarn sehen die Haut)
    const x0: i64 = @as(i64, sx) * 16 - 1;
    const y0: i64 = @as(i64, sy) * 16 - 1;
    const z0: i64 = @as(i64, sz) * 16 - 1;
    S.dirty.append(gpa, .{ x0, y0, z0, x0 + 17, y0 + 17, z0 + 17 }) catch {};
}

/// Zustands-Tabellen (Attribut und Flags je Blockzustand)
export fn pyd_sec_states(attr: [*]const u32, flags: [*]const u32, count: u32) i32 {
    root.bind();
    lock();
    defer unlock();
    if (G.drv.cuMemAlloc_v2(&S.d_attr, @as(usize, count) * 4) != 0) return -1;
    if (G.drv.cuMemAlloc_v2(&S.d_flags, @as(usize, count) * 4) != 0) return -1;
    _ = G.drv.cuMemcpyHtoD_v2(S.d_attr, attr, @as(usize, count) * 4);
    _ = G.drv.cuMemcpyHtoD_v2(S.d_flags, flags, @as(usize, count) * 4);
    S.nstates = count;
    return 0;
}

/// Sektion ablegen. data: t.slot_bytes (4096 Zustände (y, z, x), dann Gras- und
/// Laubfarben je Spalte) oder null mit `uniform`
export fn pyd_sec_put(sx: i32, sy: i32, sz: i32, data: ?[*]const u16, uniform: u32) i32 {
    root.bind();
    lock();
    defer unlock();
    if (!ensureTable()) return -1;
    if (S.used * 4 > S.cap * 3) {
        root.setError("Sektionstabelle voll", .{});
        return -1;
    }
    const k = t.secKey(sx, sy, sz);
    const h = insertSlot(k) orelse return -1;
    const old = S.vals[h];
    if (data) |d| {
        var slot: u32 = undefined;
        if (old != 0xffff_ffff and old & 0x8000_0000 == 0) {
            slot = old; // Platz wiederverwenden
        } else {
            slot = allocPoolSlot() orelse return -1;
        }
        // synchron (Quelle ist Java-Speicher): kurz, 8 KiB
        if (G.drv.cuMemcpyHtoDAsync_v2(S.pool + @as(u64, slot) * slot_bytes, d, slot_bytes, G.stream) != 0) return -1;
        S.vals[h] = slot;
    } else {
        if (old != 0xffff_ffff and old & 0x8000_0000 == 0) S.free_slots.append(gpa, old) catch {};
        S.vals[h] = 0x8000_0000 | (uniform & 0xffff);
    }
    S.table_dirty = true;
    markDirty(sx, sy, sz);
    return 0;
}

export fn pyd_sec_remove(sx: i32, sy: i32, sz: i32) void {
    lock();
    defer unlock();
    if (S.cap == 0) return;
    const k = t.secKey(sx, sy, sz);
    const h = findSlot(k) orelse return;
    const old = S.vals[h];
    if (old != 0xffff_ffff and old & 0x8000_0000 == 0) S.free_slots.append(gpa, old) catch {};
    S.keys[h] = tomb;
    S.vals[h] = 0;
    S.table_dirty = true;
}

/// Spalte (Chunk cx, cz) als vollständig geladen bzw. entladen markieren
export fn pyd_col_set(cx: i32, cz: i32, loaded: i32) void {
    lock();
    defer unlock();
    const k = colKey(cx, cz);
    if (loaded != 0) {
        S.columns.put(gpa, k, {}) catch {};
    } else {
        _ = S.columns.remove(k);
    }
    // die ganze Spalte neu (vorher Rückfall-Gelände, jetzt echte Daten)
    const x0: i64 = @as(i64, cx) * 16 - 1;
    const z0: i64 = @as(i64, cz) * 16 - 1;
    S.dirty.append(gpa, .{ x0, S.y_min - 1, z0, x0 + 17, S.y_max, z0 + 17 }) catch {};
}

/// Welt verlassen: alle Sektionen vergessen (Speicher bleibt reserviert)
pub fn reset() void {
    lock();
    defer unlock();
    if (S.cap != 0) {
        @memset(S.keys, 0);
        @memset(S.vals, 0);
        _ = G.drv.cuMemsetD8_v2(S.d_keys, 0, S.cap * 8);
    }
    S.used = 0;
    S.free_slots.clearRetainingCapacity();
    S.next_slot = 0;
    S.columns.clearRetainingCapacity();
    S.dirty.clearRetainingCapacity();
    S.table_dirty = false;
    S.nstates = 0;
}

/// Einmal je Frame: Tabelle hochladen, geänderte Bereiche in Pyrit ungültig machen
pub fn flush(ctx: ?*anyopaque, world: ?*anyopaque, spectator_world: ?*anyopaque) void {
    lock();
    defer unlock();
    if (S.table_dirty and S.cap != 0) {
        _ = G.drv.cuMemcpyHtoDAsync_v2(S.d_keys, S.keys.ptr, S.cap * 8, G.stream);
        _ = G.drv.cuMemcpyHtoDAsync_v2(S.d_vals, S.vals.ptr, S.cap * 4, G.stream);
        _ = G.drv.cuStreamSynchronize(G.stream);
        S.table_dirty = false;
    }
    if (world != null) {
        // Nur Stufen, deren Chunks ganz in geladenen Spalten liegen koennen (siehe known):
        // groebere rechnet der LOD-Generator ohnehin unveraendert.
        const side: f64 = @sqrt(@as(f64, @floatFromInt(S.columns.count())));
        var max_lod: u32 = 0;
        while (max_lod < 20 and @as(f64, @floatFromInt((@as(u64, 2) << @intCast(max_lod + 1)) + 2)) <= side) max_lod += 1;
        for (S.dirty.items) |d| {
            _ = pyrit.pyr_world_invalidate_lods(@ptrCast(ctx), @ptrCast(world), &.{ d[0], d[1], d[2] }, &.{ d[3], d[4], d[5] }, max_lod);
            if (spectator_world != null)
                _ = pyrit.pyr_world_invalidate_lods(@ptrCast(ctx), @ptrCast(spectator_world), &.{ d[0], d[1], d[2] }, &.{ d[3], d[4], d[5] }, 0);
        }
    }
    S.dirty.clearRetainingCapacity();
}

/// Simulationsbereich setzen: Chunks, die ganz darin oder in geladenen Spalten liegen, rechnet der Fernbereich nicht
pub fn setSimArea(cx: i32, cz: i32, r: i32) void {
    lock();
    defer unlock();
    S.sim_cx = cx;
    S.sim_cz = cz;
    S.sim_r = r;
}

fn inSim(cx: i32, cz: i32) bool {
    if (S.sim_r < 0) return false;
    const dx = @as(i64, cx) - S.sim_cx;
    const dz = @as(i64, cz) - S.sim_cz;
    return @abs(dx) <= S.sim_r and @abs(dz) <= S.sim_r;
}

/// Generator-Thread: welche Chunks des Auftrags liegen ganz in geladenen Spalten oder im Simulationsbereich?
pub fn known(keys: []const pyrit.types.ChunkKey, out: []u32) t.SecTable {
    lock();
    defer unlock();
    for (keys, 0..) |k, i| {
        out[i] = 0;
        if (S.cap == 0 or S.nstates == 0) continue;
        const size: i64 = @as(i64, 32) << @intCast(k.lod);
        const x0 = @as(i64, k.x) * size;
        const z0 = @as(i64, k.z) * size;
        // Chunk einschließlich eines Voxels Rand
        const cx0: i32 = @intCast((x0 - (size >> 5)) >> 4);
        const cz0: i32 = @intCast((z0 - (size >> 5)) >> 4);
        const cx1: i32 = @intCast((x0 + size + (size >> 5) - 1) >> 4);
        const cz1: i32 = @intCast((z0 + size + (size >> 5) - 1) >> 4);
        if (@as(i64, cx1 - cx0 + 1) * (cz1 - cz0 + 1) > 4096) continue;
        var all = true;
        var cz = cz0;
        outer: while (cz <= cz1) : (cz += 1) {
            var cx = cx0;
            while (cx <= cx1) : (cx += 1) {
                if (!S.columns.contains(colKey(cx, cz)) and !inSim(cx, cz)) {
                    all = false;
                    break :outer;
                }
            }
        }
        if (all) out[i] = 1;
    }
    return .{
        .keys = S.d_keys,
        .vals = S.d_vals,
        .pool = S.pool,
        .state_attr = S.d_attr,
        .state_flags = S.d_flags,
        .known = 0,
        .cap_mask = if (S.cap == 0) 0 else S.cap - 1,
        .nstates = S.nstates,
        .y_min = S.y_min,
        .y_max = S.y_max,
    };
}
