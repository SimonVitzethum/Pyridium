//! Shared Minecraft face mapping for shading AND DAG/OptiX alpha testing.
//! A tile wraps within its own face at every mip; adjacent faces never bleed.
const types = @import("types.zig");
const vec = @import("vec.zig");
const fm = @import("fmath.zig");
pub const Rgba = @Vector(4, f32);

pub fn face(n: vec.Vec3) u32 {
    const ax = @abs(n[0]);
    const ay = @abs(n[1]);
    const az = @abs(n[2]);
    if (ax >= ay and ax >= az) return if (n[0] < 0) 0 else 1;
    if (ay >= az) return if (n[1] < 0) 2 else 3;
    return if (n[2] < 0) 4 else 5;
}

pub fn uv(p: vec.Vec3, f: u32) [2]f32 {
    return switch (f >> 1) {
        0 => .{ p[2], p[1] },
        1 => .{ p[0], p[2] },
        else => .{ p[0], p[1] },
    };
}

inline fn texel(t: *const types.TextureData, off: u64, w: u32, tile: u32, x: i32, y: i32) Rgba {
    const xx: u32 = @intCast(@mod(x, @as(i32, @intCast(w))));
    const yy: u32 = @intCast(@mod(y, @as(i32, @intCast(w))));
    const px = @as([*]const [4]u8, @ptrFromInt(t.data))[off + (@as(u64, tile) * w + yy) * w + xx];
    return @as(Rgba, .{ @floatFromInt(px[0]), @floatFromInt(px[1]), @floatFromInt(px[2]), @floatFromInt(px[3]) }) * @as(Rgba, @splat(1.0 / 255.0));
}

inline fn level(t: *const types.TextureData, tile: u32, lod: u32, u: f32, v: f32, sharp: f32) Rgba {
    var off: u64 = 0;
    var w = t.width;
    var h = t.height;
    var k: u32 = 0;
    while (k < lod) : (k += 1) {
        off += @as(u64, w) * h;
        w = @max(w / 2, 1);
        h = @max(h / 2, 1);
    }
    const x = (u - @floor(u)) * @as(f32, @floatFromInt(w)) - 0.5;
    const y = (v - @floor(v)) * @as(f32, @floatFromInt(w)) - 0.5;
    const x0: i32 = @intFromFloat(@floor(x));
    const y0: i32 = @intFromFloat(@floor(y));
    const tx = @min(@max((x - @floor(x) - 0.5) * sharp + 0.5, 0), 1);
    const ty = @min(@max((y - @floor(y) - 0.5) * sharp + 0.5, 0), 1);
    const a = texel(t, off, w, tile, x0, y0);
    const b = texel(t, off, w, tile, x0 + 1, y0);
    const c = texel(t, off, w, tile, x0, y0 + 1);
    const d = texel(t, off, w, tile, x0 + 1, y0 + 1);
    const top = a + (b - a) * @as(Rgba, @splat(tx));
    const bot = c + (d - c) * @as(Rgba, @splat(tx));
    return top + (bot - top) * @as(Rgba, @splat(ty));
}

pub inline fn sample(s: *const types.Scene, index: u32, tile: u32, uvw: [2]f32, footprint: f32) Rgba {
    if (index == 0 or index >= s.texture_count) return @splat(1);
    const table: [*]const types.TextureData = @ptrFromInt(s.textures);
    var t = &table[index];
    var tile_index = tile;
    if (t.reserved_tex_data == types.texture_tile_table) {
        if (t.data == 0 or tile >= t.width) return @splat(1);
        const target = @as([*]const u32, @ptrFromInt(t.data))[tile];
        if (target == 0 or target >= s.texture_count) return @splat(1);
        t = &table[target];
        tile_index = 0;
    }
    if (t.data == 0 or t.width == 0 or tile_index >= t.height / t.width) return @splat(1);
    const tpp = footprint * @as(f32, @floatFromInt(t.width));
    // Stop at one texel per tile. Further mips merge different faces.
    const max_lod = @min(@max(t.levels, 1) - 1, 31 - @clz(t.width));
    if (tpp <= 1) return level(t, tile_index, 0, uvw[0], uvw[1], 1 / @max(tpp, 1e-4));
    const lf = @min(fm.log2(tpp), @as(f32, @floatFromInt(max_lod)));
    const lo: u32 = @intFromFloat(@floor(lf));
    const a = level(t, tile_index, lo, uvw[0], uvw[1], 1);
    if (lo >= max_lod) return a;
    const b = level(t, tile_index, lo + 1, uvw[0], uvw[1], 1);
    return a + (b - a) * @as(Rgba, @splat(lf - @floor(lf)));
}

pub fn alpha(s: *const types.Scene, index: u32, f: u32, p: vec.Vec3) f32 {
    return sample(s, index, f, uv(p, f), 0)[3];
}

pub fn rgb(c: Rgba) vec.Vec3 { return .{ c[0], c[1], c[2] }; }

pub fn tileWidth(s: *const types.Scene, index: u32, tile: u32) u32 {
    if (index == 0 or index >= s.texture_count) return 1;
    const table: [*]const types.TextureData = @ptrFromInt(s.textures);
    var t = &table[index];
    if (t.reserved_tex_data == types.texture_tile_table) {
        if (t.data == 0 or tile >= t.width) return 1;
        const target = @as([*]const u32, @ptrFromInt(t.data))[tile];
        if (target == 0 or target >= s.texture_count) return 1;
        t = &table[target];
    }
    return @max(t.width, 1);
}
