//! GPU-Kernel von Pyridium (nvptx64 -> PTX, eingebettet in libpyridium).
//!
//! Kein Vertex-, Mesh- oder Shaderpfad: die Welt rendert Pyrit, und die
//! Oberfläche (Menüs, HUD, Text) besteht aus 2D-Sprites, die Java aus den
//! GUI-Draws von Minecraft liest. Dieser Compositor blendet sie der Reihe nach
//! in ein RGBA8-Ziel (Zeile 0 = unten, wie Minecrafts Render-Targets).

const std = @import("std");
const builtin = @import("builtin");
const t = @import("types.zig");

pub const panic = std.debug.no_panic;

const kernel: std.builtin.CallingConvention = .nvptx_kernel;

/// Model surfaces are voxelized once on CUDA, then reused while poses change.
export fn pyd_k_model(faces_ptr: u64, face_count: u32, voxels_ptr: u64, count: u32, log2_size: u32) callconv(kernel) void {
    const bx = asm ("mov.u32 %[v], %ctaid.x;"
        : [v] "=r" (-> u32),
    );
    const tx = asm ("mov.u32 %[v], %tid.x;"
        : [v] "=r" (-> u32),
    );
    const i = bx * 256 + tx;
    if (i >= count) return;
    const faces: [*]const t.ModelFace = @ptrFromInt(faces_ptr);
    const out: [*][4]u32 = @ptrFromInt(voxels_ptr);
    out[i] = .{ 0, 0, 0, 0 };
    // Host validates sorted, contiguous ranges. Binary lookup avoids quadratic
    // work for detailed item meshes and custom submissions with hundreds of quads.
    var low: u32 = 0;
    var high = face_count;
    while (low < high) {
        const mid = low + (high - low) / 2;
        if (faces[mid].first <= i) low = mid + 1 else high = mid;
    }
    if (low != 0) {
        const face = &faces[low - 1];
        const j = i - face.first;
        if (j >= face.steps[0] * face.steps[1]) return;
        const u = (@as(f32, @floatFromInt(j % face.steps[0])) + 0.5) / @as(f32, @floatFromInt(face.steps[0]));
        const v = (@as(f32, @floatFromInt(j / face.steps[0])) + 0.5) / @as(f32, @floatFromInt(face.steps[1]));
        // Match Vanilla's diagonal 0->2, including non-parallelogram quads and
        // per-vertex UV/color gradients. Bilinear UVs would distort trapezoids.
        const weights: [4]f32 = if (u >= v) .{ 1 - u, u - v, v, 0 } else .{ 1 - v, 0, u, v - u };
        var voxel: [4]u32 = .{ 0, 0, 0, 0 };
        inline for (0..3) |a| {
            var p: f32 = -0.0001;
            inline for (0..4) |k| p += face.p[k][a] * weights[k];
            voxel[a] = @intFromFloat(std.math.clamp(@floor(p), 0, @as(f32, @floatFromInt((@as(u32, 1) << @intCast(log2_size)) - 1))));
        }
        var uv: [2]f32 = .{ 0, 0 };
        inline for (0..2) |a| inline for (0..4) |k| {
            uv[a] += face.uv[k][a] * weights[k];
        };
        const x: u32 = @intFromFloat(std.math.clamp(@floor(uv[0] * @as(f32, @floatFromInt(face.width))), 0, @as(f32, @floatFromInt(face.width - 1))));
        const y: u32 = @intFromFloat(std.math.clamp(@floor(uv[1] * @as(f32, @floatFromInt(face.height))), 0, @as(f32, @floatFromInt(face.height - 1))));
        const rgba: [4]u8 = if (face.texture == 0) .{ 255, 255, 255, 255 } else blk: {
            const pixels: [*]const [4]u8 = @ptrFromInt(face.texture);
            break :blk pixels[@as(u64, y) * face.width + x];
        };
        var tint: [4]u32 = undefined;
        inline for (0..4) |a| {
            var channel: f32 = 0;
            inline for (0..4) |k| channel += @as(f32, @floatFromInt((face.color[k] >> @as(u5, @intCast(a * 8))) & 255)) * weights[k];
            tint[a] = @intFromFloat(std.math.clamp(@round(channel), 0, 255));
        }
        if (@as(u32, rgba[3]) * tint[3] < 128 * 255) return;
        const r = @as(u32, rgba[0]) * tint[2] / 255;
        const g = @as(u32, rgba[1]) * tint[1] / 255;
        const b = @as(u32, rgba[2]) * tint[0] / 255;
        voxel[3] = pyr.types.packMaterial16(4094, (r << 16) | (g << 8) | b);
        out[i] = voxel;
        return;
    }
}

inline fn barrier() void {
    asm volatile ("bar.sync 0;" ::: .{ .memory = true });
}

inline fn unorm(v: u32, shift: u5) f32 {
    return @as(f32, @floatFromInt((v >> shift) & 0xff)) * (1.0 / 255.0);
}

inline fn unpack(v: u32) [4]f32 {
    return .{ unorm(v, 0), unorm(v, 8), unorm(v, 16), unorm(v, 24) };
}

inline fn to8(x: f32) u32 {
    const c = std.math.clamp(x, 0.0, 1.0);
    return @intFromFloat(@round(c * 255.0));
}

inline fn pack(c: [4]f32) u32 {
    return to8(c[0]) | (to8(c[1]) << 8) | (to8(c[2]) << 16) | (to8(c[3]) << 24);
}

fn factor(f: u32, s: [4]f32, d: [4]f32, comp: usize) f32 {
    return switch (f) {
        2 => d[3], // DST_ALPHA
        3 => d[comp], // DST_COLOR
        4 => 1.0, // ONE
        5, 6 => 1.0,
        7 => 1.0 - d[3], // ONE_MINUS_DST_ALPHA
        8 => 1.0 - d[comp], // ONE_MINUS_DST_COLOR
        9 => 1.0 - s[3], // ONE_MINUS_SRC_ALPHA
        10 => 1.0 - s[comp], // ONE_MINUS_SRC_COLOR
        11 => s[3], // SRC_ALPHA
        12 => if (comp == 3) 1.0 else @min(s[3], 1.0 - d[3]),
        13 => s[comp], // SRC_COLOR
        else => 0.0, // ZERO, CONSTANT_*
    };
}

fn op(o: u32, s: f32, d: f32) f32 {
    return switch (o) {
        1 => s - d,
        2 => d - s,
        3 => @min(s, d),
        4 => @max(s, d),
        else => s + d,
    };
}

fn blend(b: u32, s: [4]f32, d: [4]f32) [4]f32 {
    var r = s;
    if (b & t.blend_enable != 0) {
        const sc = b & 15;
        const dc = (b >> 4) & 15;
        const oc = (b >> 8) & 7;
        const sa = (b >> 11) & 15;
        const da = (b >> 15) & 15;
        const oa = (b >> 19) & 7;
        for (0..3) |i| {
            const minmax = oc == 3 or oc == 4;
            const fs = if (minmax) 1.0 else factor(sc, s, d, i);
            const fd = if (minmax) 1.0 else factor(dc, s, d, i);
            r[i] = op(oc, s[i] * fs, d[i] * fd);
        }
        const mm = oa == 3 or oa == 4;
        r[3] = op(oa, s[3] * (if (mm) 1.0 else factor(sa, s, d, 3)), d[3] * (if (mm) 1.0 else factor(da, s, d, 3)));
    }
    const wm = (b >> 24) & 15;
    if (wm != 15) {
        inline for (0..4) |i| {
            if (wm & (1 << i) == 0) r[i] = d[i];
        }
    }
    return r;
}

fn texel(tx: *const t.Tex, x: i32, y: i32) [4]f32 {
    const w: i32 = @intCast(tx.w);
    const h: i32 = @intCast(tx.h);
    var xi = x;
    var yi = y;
    if (tx.clamp != 0) {
        xi = std.math.clamp(xi, 0, w - 1);
        yi = std.math.clamp(yi, 0, h - 1);
    } else {
        xi = @mod(xi, w);
        yi = @mod(yi, h);
    }
    const idx: u64 = @as(u64, @intCast(yi)) * tx.w + @as(u64, @intCast(xi));
    const base: u64 = tx.ptr + idx * tx.bpp;
    if (tx.bpp == 1) {
        const p: *const u8 = @ptrFromInt(base);
        const v = @as(f32, @floatFromInt(p.*)) * (1.0 / 255.0);
        return .{ v, v, v, v };
    }
    const p: *const u32 = @ptrFromInt(base);
    return unpack(p.*);
}

fn sample(tx: *const t.Tex, u: f32, v: f32) [4]f32 {
    const fw: f32 = @floatFromInt(tx.w);
    const fh: f32 = @floatFromInt(tx.h);
    if (tx.linear == 0) {
        return texel(tx, @intFromFloat(@floor(u * fw)), @intFromFloat(@floor(v * fh)));
    }
    const x = u * fw - 0.5;
    const y = v * fh - 0.5;
    const fx = @floor(x);
    const fy = @floor(y);
    const ax = x - fx;
    const ay = y - fy;
    const ix: i32 = @intFromFloat(fx);
    const iy: i32 = @intFromFloat(fy);
    const a = texel(tx, ix, iy);
    const b = texel(tx, ix + 1, iy);
    const c = texel(tx, ix, iy + 1);
    const d = texel(tx, ix + 1, iy + 1);
    var r: [4]f32 = undefined;
    for (0..4) |i| {
        const top = a[i] + (b[i] - a[i]) * ax;
        const bot = c[i] + (d[i] - c[i]) * ax;
        r[i] = top + (bot - top) * ay;
    }
    return r;
}

inline fn cross(ax: f32, ay: f32, bx: f32, by: f32) f32 {
    return ax * by - ay * bx;
}

/// Parameter (u, v) des Punkts im bilinearen Viereck a,b,c,d
/// (P = a + e u + f v + g u v); false, wenn außerhalb.
fn invBilinear(s: *const t.Sprite, px: f32, py: f32, out_u: *f32, out_v: *f32) bool {
    const ex = s.x[1] - s.x[0];
    const ey = s.y[1] - s.y[0];
    const fx = s.x[3] - s.x[0];
    const fy = s.y[3] - s.y[0];
    const gx = s.x[0] - s.x[1] + s.x[2] - s.x[3];
    const gy = s.y[0] - s.y[1] + s.y[2] - s.y[3];
    const hx = px - s.x[0];
    const hy = py - s.y[0];
    const k2 = cross(gx, gy, fx, fy);
    const k1 = cross(ex, ey, fx, fy) + cross(hx, hy, gx, gy);
    const k0 = cross(hx, hy, ex, ey);
    var u: f32 = undefined;
    var v: f32 = undefined;
    if (@abs(k2) < 1e-6 * @max(@abs(k1), 1e-12)) {
        if (k1 == 0) return false;
        v = -k0 / k1;
        const den = ex + gx * v;
        const deny = ey + gy * v;
        u = if (@abs(den) > @abs(deny)) (hx - fx * v) / den else (hy - fy * v) / deny;
    } else {
        var w = k1 * k1 - 4.0 * k0 * k2;
        if (w < 0) return false;
        w = @sqrt(w);
        const ik2 = 0.5 / k2;
        v = (-k1 - w) * ik2;
        if (v < 0 or v >= 1) v = (-k1 + w) * ik2;
        const den = ex + gx * v;
        const deny = ey + gy * v;
        u = if (@abs(den) > @abs(deny)) (hx - fx * v) / den else (hy - fy * v) / deny;
    }
    if (!(u >= 0 and u < 1 and v >= 0 and v < 1)) return false;
    out_u.* = u;
    out_v.* = v;
    return true;
}

const chunk = 256;
var sh_bb: [chunk][4]i32 addrspace(.shared) = undefined;

/// Blendet `count` Sprites in Reihenfolge ins Ziel. Gitter: 16x16-Blöcke über
/// das Rechteck region (x0, y0, x1, y1).
export fn pyd_k_composite(
    target: u64,
    tw: u32,
    th: u32,
    sprites: [*]const t.Sprite,
    count: u32,
    texs: [*]const t.Tex,
    rx0: i32,
    ry0: i32,
    rx1: i32,
    ry1: i32,
) callconv(kernel) void {
    const lx: i32 = @intCast(@workItemId(0));
    const ly: i32 = @intCast(@workItemId(1));
    const tid: u32 = @intCast(ly * 16 + lx);
    const bx0 = rx0 + @as(i32, @intCast(@workGroupId(0))) * 16;
    const by0 = ry0 + @as(i32, @intCast(@workGroupId(1))) * 16;
    const px = bx0 + lx;
    const py = by0 + ly;
    const mine = px < rx1 and py < ry1 and px >= 0 and py >= 0 and px < tw and py < th;
    const pix: *u32 = @ptrFromInt(target + (@as(u64, @intCast(@max(py, 0))) * tw + @as(u64, @intCast(@max(px, 0)))) * 4);
    var dst: [4]f32 = if (mine) unpack(pix.*) else .{ 0, 0, 0, 0 };
    var dirty = false;
    const fx: f32 = @as(f32, @floatFromInt(px)) + 0.5;
    const fy: f32 = @as(f32, @floatFromInt(py)) + 0.5;

    var base: u32 = 0;
    while (base < count) : (base += chunk) {
        if (base + tid < count) sh_bb[tid] = sprites[base + tid].bbox;
        barrier();
        const n = @min(chunk, count - base);
        var k: u32 = 0;
        while (k < n) : (k += 1) {
            const bb = sh_bb[k];
            // ganzer Block außerhalb: alle Threads überspringen gemeinsam
            if (bb[0] >= bx0 + 16 or bb[1] >= by0 + 16 or bb[2] <= bx0 or bb[3] <= by0) continue;
            if (!mine or px < bb[0] or py < bb[1] or px >= bb[2] or py >= bb[3]) continue;
            const s = &sprites[base + k];
            var u: f32 = 0;
            var v: f32 = 0;
            if (s.flags & t.sprite_rect != 0) {
                // achsenparallel: halboffene Kanten exakt über die Pixelmitte
                const x0 = @min(s.x[0], s.x[2]);
                const x1 = @max(s.x[0], s.x[2]);
                const y0 = @min(s.y[0], s.y[2]);
                const y1 = @max(s.y[0], s.y[2]);
                if (!(fx >= x0 and fx < x1 and fy >= y0 and fy < y1)) continue;
                if (!invBilinear(s, @min(@max(fx, x0), x1 - 1e-3), @min(@max(fy, y0), y1 - 1e-3), &u, &v)) continue;
            } else if (!invBilinear(s, fx, fy, &u, &v)) continue;
            // Farbe und UV bilinear über die Ecken
            const w0 = (1 - u) * (1 - v);
            const w1 = u * (1 - v);
            const w2 = u * v;
            const w3 = (1 - u) * v;
            if (s.mode >= t.mode_copy) {
                // Atlas-Aufbau: Texel kopieren (bzw. zwei Bilder überblenden), ohne Verwerfen
                const cu = s.u[0] * w0 + s.u[1] * w1 + s.u[2] * w2 + s.u[3] * w3;
                const cv = s.v[0] * w0 + s.v[1] * w1 + s.v[2] * w2 + s.v[3] * w3;
                var a = sample(&texs[s.tex - 1], cu, cv);
                if (s.mode == t.mode_interp and s.color[0] != 0) {
                    const b = sample(&texs[s.color[0] - 1], cu, cv);
                    const f: f32 = @bitCast(s.color[1]);
                    for (0..4) |i| a[i] = a[i] + (b[i] - a[i]) * f;
                }
                dst = blend(s.blend, a, dst);
                dirty = true;
                continue;
            }
            const c0 = unpack(s.color[0]);
            const c1 = unpack(s.color[1]);
            const c2 = unpack(s.color[2]);
            const c3 = unpack(s.color[3]);
            var col: [4]f32 = undefined;
            for (0..4) |i| col[i] = c0[i] * w0 + c1[i] * w1 + c2[i] * w2 + c3[i] * w3;
            const mode = s.mode;
            if (mode == t.mode_color) {
                if (col[3] <= 0.0) continue;
            } else {
                const tu = s.u[0] * w0 + s.u[1] * w1 + s.u[2] * w2 + s.u[3] * w3;
                const tv = s.v[0] * w0 + s.v[1] * w1 + s.v[2] * w2 + s.v[3] * w3;
                var tc: [4]f32 = if (s.tex != 0) sample(&texs[s.tex - 1], tu, tv) else .{ 1, 1, 1, 1 };
                if (mode == t.mode_text_gray) tc = .{ tc[0], tc[0], tc[0], tc[0] };
                for (0..4) |i| col[i] *= tc[i];
                if (mode == t.mode_tex and col[3] <= 0.0) continue;
                if ((mode == t.mode_text or mode == t.mode_text_gray) and col[3] < 0.1) continue;
            }
            dst = blend(s.blend, col, dst);
            dirty = true;
        }
        barrier();
    }
    if (mine and dirty) pix.* = pack(dst);
}

/// Rechteck eines Ziels mit einem Muster füllen (bis 16 Bytes je Pixel)
export fn pyd_k_fill(dst: u64, w: u32, bpp: u32, x0: u32, y0: u32, rw: u32, rh: u32, v0: u32, v1: u32, v2: u32, v3: u32) callconv(kernel) void {
    const x = @workGroupId(0) * 16 + @workItemId(0);
    const y = @workGroupId(1) * 16 + @workItemId(1);
    if (x >= rw or y >= rh) return;
    const off = (@as(u64, y0 + y) * w + (x0 + x)) * bpp;
    const vals = [4]u32{ v0, v1, v2, v3 };
    if (bpp % 4 == 0) {
        const p: [*]u32 = @ptrFromInt(dst + off);
        for (0..bpp / 4) |i| p[i] = vals[i];
    } else {
        const p: [*]u8 = @ptrFromInt(dst + off);
        const bytes: [16]u8 = @bitCast(vals);
        for (0..bpp) |i| p[i] = bytes[i];
    }
}

/// Minecraft-Ziel (RGBA8, Zeile 0 unten) -> Wayland XRGB8888 (Zeile 0 oben)
export fn pyd_k_present(src: u64, sw: u32, sh: u32, dst: u64, dw: u32, dh: u32, dpitch: u32) callconv(kernel) void {
    const x = @workGroupId(0) * 16 + @workItemId(0);
    const y = @workGroupId(1) * 16 + @workItemId(1);
    if (x >= dw or y >= dh) return;
    var v: u32 = 0xff000000;
    if (x < sw and y < sh) {
        const s: *const u32 = @ptrFromInt(src + (@as(u64, sh - 1 - y) * sw + x) * 4);
        const c = s.*;
        v = 0xff000000 | ((c & 0xff) << 16) | (c & 0xff00) | ((c >> 16) & 0xff);
    }
    const d: *u32 = @ptrFromInt(dst + @as(u64, y) * dpitch + @as(u64, x) * 4);
    d.* = v;
}

/// Pyrits LDR-Bild (RGBA8, Zeile 0 oben) in ein Minecraft-Ziel (Zeile 0 unten)
export fn pyd_k_flip_copy(src: u64, dst: u64, w: u32, h: u32) callconv(kernel) void {
    const x = @workGroupId(0) * 16 + @workItemId(0);
    const y = @workGroupId(1) * 16 + @workItemId(1);
    if (x >= w or y >= h) return;
    const s: *const u32 = @ptrFromInt(src + (@as(u64, y) * w + x) * 4);
    const d: *u32 = @ptrFromInt(dst + (@as(u64, h - 1 - y) * w + x) * 4);
    d.* = s.* | 0xff000000;
}

// ---------------------------------------------------------------------------
// Generator aus geladenen Minecraft-Sektionen
// ---------------------------------------------------------------------------

const pyr = @import("pyrit_device");
const gen_side = 34; // 32 + Rand
const gen_cells = gen_side * gen_side * gen_side;
const gen_words = (gen_cells + 31) / 32;
var sh_opaque: [gen_words]u32 addrspace(.shared) = undefined;
var sh_solid: [gen_words]u32 addrspace(.shared) = undefined;
var sh_water: [gen_words]u32 addrspace(.shared) = undefined;

const Lookup = struct {
    s: *const t.SecTable,
    last_key: u64 = 0,
    last_val: u32 = 0xffff_ffff,

    fn section(self: *Lookup, sx: i32, sy: i32, sz: i32) u32 {
        const k = t.secKey(sx, sy, sz);
        if (k == self.last_key) return self.last_val;
        const keys: [*]const u64 = @ptrFromInt(self.s.keys);
        const vals: [*]const u32 = @ptrFromInt(self.s.vals);
        var h: u32 = @truncate(t.secHash(k) & self.s.cap_mask);
        var val: u32 = 0x8000_0000; // fehlt: Luft (Chunk bekannt)
        var guard: u32 = 0;
        while (guard <= self.s.cap_mask) : (guard += 1) {
            const kk = keys[h];
            if (kk == k) {
                val = vals[h];
                break;
            }
            if (kk == 0) break;
            h = (h + 1) & self.s.cap_mask;
        }
        self.last_key = k;
        self.last_val = val;
        return val;
    }

    fn block(self: *Lookup, x: i32, y: i32, z: i32) u32 {
        if (y < self.s.y_min or y >= self.s.y_max) return 0;
        const v = self.section(x >> 4, y >> 4, z >> 4);
        if (v & 0x8000_0000 != 0) return v & 0xffff;
        const pool: [*]const u16 = @ptrFromInt(self.s.pool + @as(u64, v) * t.slot_bytes);
        const idx: u64 = (@as(u64, @intCast(y & 15)) * 16 + @as(u64, @intCast(z & 15))) * 16 + @as(u64, @intCast(x & 15));
        return pool[idx];
    }

    /// Biomfarbe (0 Gras, 1 Laub) der Blockspalte; 0 = unbekannt
    fn tint(self: *Lookup, x: i32, y: i32, z: i32, which: u32) u32 {
        if (y < self.s.y_min or y >= self.s.y_max) return 0;
        const v = self.section(x >> 4, y >> 4, z >> 4);
        if (v & 0x8000_0000 != 0) return 0;
        const cols: [*]const u32 = @ptrFromInt(self.s.pool + @as(u64, v) * t.slot_bytes + 8192);
        return cols[which * 256 + @as(u32, @intCast(z & 15)) * 16 + @as(u32, @intCast(x & 15))];
    }

    fn flags(self: *Lookup, sid: u32) u32 {
        if (sid >= self.s.nstates) return 0;
        const f: [*]const u32 = @ptrFromInt(self.s.state_flags);
        return f[sid];
    }

    /// Zustand einer Zelle der Stufe l: bei l > 0 der oberste feste Block
    /// zweier Probespalten (die sichtbare Oberseite der Zelle)
    fn cell(self: *Lookup, bx: i32, by: i32, bz: i32, l: u5) u32 {
        var p: [3]i32 = undefined;
        return self.cellAt(bx, by, bz, l, &p);
    }

    fn cellAt(self: *Lookup, bx: i32, by: i32, bz: i32, l: u5, pos: *[3]i32) u32 {
        pos.* = .{ bx, by, bz };
        if (l == 0) return self.block(bx, by, bz);
        const size: i32 = @as(i32, 1) << l;
        const o1: i32 = size >> 2;
        const o2: i32 = size - 1 - (size >> 2);
        var y = by + size - 1;
        while (y >= by) : (y -= 1) {
            const a = self.block(bx + o1, y, bz + o1);
            if (self.flags(a) & (t.state_solid | t.state_water) != 0) {
                pos.* = .{ bx + o1, y, bz + o1 };
                return a;
            }
            const b = self.block(bx + o2, y, bz + o2);
            if (self.flags(b) & (t.state_solid | t.state_water) != 0) {
                pos.* = .{ bx + o2, y, bz + o2 };
                return b;
            }
        }
        return 0;
    }
};

inline fn bitGet(arr: *addrspace(.shared) [gen_words]u32, i: u32) bool {
    return (arr[i >> 5] >> @intCast(i & 31)) & 1 != 0;
}

inline fn bitSet(arr: *addrspace(.shared) [gen_words]u32, i: u32) void {
    _ = @atomicRmw(u32, &arr[i >> 5], .Or, @as(u32, 1) << @intCast(i & 31), .monotonic);
}

/// Ein Block je Chunk (256 Threads). Chunks mit known[c] == 0 bleiben dem
/// Rückfall-Generator überlassen, der vorher im selben Stream lief.
export fn pyd_k_gen_sections(g: pyr.types.WorldGenParams, s: t.SecTable) callconv(kernel) void {
    const c = @workGroupId(0);
    if (c >= g.count) return;
    const known: [*]const u32 = @ptrFromInt(s.known);
    if (known[c] == 0) return;
    const tid = @workItemId(0);
    const key = @as([*]const pyr.types.ChunkKey, @ptrFromInt(g.chunks))[c];
    const l: u5 = @intCast(key.lod);
    const n: i32 = 32;
    const step: i32 = @as(i32, 1) << l;
    const base = [3]i32{ key.x * n * step, key.y * n * step, key.z * n * step };
    var i: u32 = tid;
    while (i < gen_words) : (i += 256) {
        sh_opaque[i] = 0;
        sh_solid[i] = 0;
        sh_water[i] = 0;
    }
    if (tid == 0) @as([*]u32, @ptrFromInt(g.counts))[c] = 0;
    barrier();
    var lk = Lookup{ .s = &s };
    i = tid;
    while (i < gen_cells) : (i += 256) {
        const cx: i32 = @as(i32, @intCast(i % gen_side)) - 1;
        const cz: i32 = @as(i32, @intCast((i / gen_side) % gen_side)) - 1;
        const cy: i32 = @as(i32, @intCast(i / (gen_side * gen_side))) - 1;
        const sid = lk.cell(base[0] + cx * step, base[1] + cy * step, base[2] + cz * step, l);
        const f = lk.flags(sid);
        if (f & t.state_opaque != 0) bitSet(&sh_opaque, i);
        if (f & t.state_solid != 0) bitSet(&sh_solid, i);
        if (f & t.state_water != 0) bitSet(&sh_water, i);
    }
    barrier();
    const attrs: [*]const u32 = @ptrFromInt(s.state_attr);
    i = tid;
    while (i < 32 * 32 * 32) : (i += 256) {
        const x: u32 = i & 31;
        const z: u32 = (i >> 5) & 31;
        const y: u32 = i >> 10;
        const ci = (x + 1) + (z + 1) * gen_side + (y + 1) * gen_side * gen_side;
        const solid = bitGet(&sh_solid, ci);
        const water = bitGet(&sh_water, ci);
        if (!solid and !water) continue;
        const nb = [6]u32{ ci - 1, ci + 1, ci - gen_side, ci + gen_side, ci - gen_side * gen_side, ci + gen_side * gen_side };
        var show = false;
        for (nb) |q| {
            if (solid) {
                if (!bitGet(&sh_opaque, q)) show = true;
            } else if (!bitGet(&sh_solid, q) and !bitGet(&sh_water, q)) show = true;
        }
        if (!show) continue;
        var bp: [3]i32 = undefined;
        const sid = lk.cellAt(base[0] + @as(i32, @intCast(x)) * step, base[1] + @as(i32, @intCast(y)) * step, base[2] + @as(i32, @intCast(z)) * step, l, &bp);
        if (sid >= s.nstates) continue;
        var a = attrs[sid];
        if (a == 0) continue;
        const sf = lk.flags(sid);
        if (sf & (t.state_tint_grass | t.state_tint_foliage) != 0) {
            const col = lk.tint(bp[0], bp[1], bp[2], if (sf & t.state_tint_grass != 0) 0 else 1);
            if (col != 0) a = @import("pyrit_device").types.packMaterial16(a & 0xffff, col);
        }
        const counts: [*]u32 = @ptrFromInt(g.counts);
        const out: [*][4]u32 = @ptrFromInt(g.voxels);
        const k = @atomicRmw(u32, &counts[c], .Add, 1, .monotonic);
        if (k < g.capacity) out[@as(u64, c) * g.capacity + k] = .{ x, y, z, a };
    }
}
