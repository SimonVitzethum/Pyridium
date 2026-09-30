//! Blockmaterialien aus Vanilla-Texturen: Java liefert je Material die
//! Texturen der Ober- und Seitenfläche (RGBA8, Zeile 0 = oben im Bild), hier
//! entstehen daraus Pyrit-Texturen, eine Reliefkarte aus der Helligkeit und
//! das Material. Beleuchtet wird ausschließlich mit Pyrits Shading.

const std = @import("std");
const pyrit = @import("pyrit");
const pyr = @import("pyrit_device");
const types = pyr.types;
const world = @import("world.zig");
const root = @import("root.zig");

const gpa = std.heap.c_allocator;

pub const flag_cutout: u32 = 1;
pub const flag_water: u32 = 2;
pub const flag_side_own_color: u32 = 4;
pub const flag_emissive: u32 = 8;
pub const flag_glass: u32 = 16;
pub const flag_no_texture: u32 = 32;
/// Kategorien wie in der Pyrit-Demo (Rauheit, Farbschwankung, Klarlack)
pub const flag_natural: u32 = 64; // Erde, Stein, Sand, Kies: großflächige Farbschwankung
pub const flag_tree: u32 = 128; // Holz, Laub: ein Ton je Baum
pub const flag_snow: u32 = 256; // Schnee, Eis: leichter Klarlack
pub const flag_grass: u32 = 512; // Gras: trockene Flecken wärmer

/// Pyrit liest Texturen mit Zeile size-1 oben am Block (v wächst nach oben):
/// Vanilla-Bilder deshalb senkrecht spiegeln.
fn flipped(src: [*]const u8, w: u32, h: u32) ![]u8 {
    const out = try gpa.alloc(u8, @as(usize, w) * h * 4);
    const row = @as(usize, w) * 4;
    for (0..h) |y| @memcpy(out[y * row ..][0..row], src[(h - 1 - y) * row ..][0..row]);
    return out;
}

/// Pyrit nimmt Texturwerte als lineare Albedo (die Voxelfarbe wandelt es
/// selbst aus sRGB). Vanilla-Texturen sind sRGB: umrechnen, sonst wird alles
/// blass und zu hell. Gleiche Kurve wie shade.srgbToLinear (Potenz 2,2).
fn toLinear(img: []u8) void {
    var lut: [256]u8 = undefined;
    for (0..256) |i| {
        const v = std.math.pow(f32, @as(f32, @floatFromInt(i)) / 255.0, 2.2);
        lut[i] = @intFromFloat(@round(v * 255.0));
    }
    var i: usize = 0;
    while (i < img.len) : (i += 4) {
        img[i] = lut[img[i]];
        img[i + 1] = lut[img[i + 1]];
        img[i + 2] = lut[img[i + 2]];
    }
}

fn normalMap(tex: []const u8, w: u32, h: u32, strength: f32) ![]u8 {
    const out = try gpa.alloc(u8, tex.len);
    const lum = struct {
        fn at(t: []const u8, ww: u32, hh: u32, x: i64, y: i64) f32 {
            const xx: usize = @intCast(@mod(x, ww));
            const yy: usize = @intCast(@mod(y, hh));
            const o = (yy * ww + xx) * 4;
            return (0.2126 * @as(f32, @floatFromInt(t[o])) + 0.7152 * @as(f32, @floatFromInt(t[o + 1])) + 0.0722 * @as(f32, @floatFromInt(t[o + 2]))) / 255.0;
        }
    }.at;
    for (0..h) |yy| for (0..w) |xx| {
        const x: i64 = @intCast(xx);
        const y: i64 = @intCast(yy);
        const du = (lum(tex, w, h, x + 1, y) - lum(tex, w, h, x - 1, y)) * strength;
        const dv = (lum(tex, w, h, x, y + 1) - lum(tex, w, h, x, y - 1)) * strength;
        const o = (yy * w + xx) * 4;
        out[o + 0] = @intFromFloat(@min(@max(128 + du * 127, 0), 255));
        out[o + 1] = @intFromFloat(@min(@max(128 + dv * 127, 0), 255));
        out[o + 2] = 255;
        out[o + 3] = 255;
    };
    return out;
}

fn texture(ctx: ?*anyopaque, rgba: []const u8, w: u32, h: u32) u32 {
    var idx: u32 = 0;
    if (pyrit.pyr_texture_create(@ptrCast(ctx), w, h, rgba.ptr, &idx) != 0) return 0;
    return idx;
}

/// Material `index` (1..255) festlegen. top/side: RGBA8 w x h (Vanilla-Ausrichtung)
export fn pyd_material_define(index: u32, top: ?[*]const u8, side: ?[*]const u8, w: u32, h: u32, flags: u32, roughness: f32, emission: f32) i32 {
    root.bind();
    const ctx = world.context() orelse return -1;
    var m: types.Material = undefined;
    pyrit.pyr_material_default(&m);
    m.flags = types.material_voxel_color;
    m.roughness = roughness;
    m.texture_scale = 1; // eine Kachel je Block
    if (flags & flag_no_texture == 0) {
        if (top) |t| {
            const ft = flipped(t, w, h) catch return -1;
            defer gpa.free(ft);
            // Relief aus der sRGB-Helligkeit (wahrnehmungsgleich), Farbe linear
            const nm = normalMap(ft, w, h, 1.6) catch return -1;
            defer gpa.free(nm);
            toLinear(ft);
            m.texture = texture(ctx, ft, w, h);
            m.normal_texture = texture(ctx, nm, w, h);
            m.normal_strength = 1;
        }
        if (side) |s| {
            const fs = flipped(s, w, h) catch return -1;
            defer gpa.free(fs);
            toLinear(fs);
            m.side_texture = texture(ctx, fs, w, h);
            if (flags & flag_side_own_color != 0) m.side_color = .{ 1, 1, 1 };
        }
    }
    if (flags & flag_cutout != 0) {
        m.flags |= types.material_cutout;
        m.subsurface = 0.5;
        m.subsurface_color = .{ 0.5, 0.9, 0.3 };
    }
    if (flags & flag_glass != 0) {
        m.flags |= types.material_transparent;
        m.ior = 1.5;
        m.opacity = 0.1;
        m.density = 0.02;
        m.roughness = 0.05;
    }
    // Materialwerte wie in der Demo (demo/scene.zig)
    if (flags & flag_natural != 0 and std.c.getenv("PYRIT_NO_VARIATION") == null) {
        m.variation = .{ 0.16, 0.10, 0.06 };
        m.variation_scale = .{ 150, 23 };
    }
    if (flags & flag_grass != 0) {
        m.variation = .{ 0.16, 0.10, 0.06 };
        m.variation_scale = .{ 150, 23 };
        m.variation_warm = 0.5;
        m.roughness = 0.9;
    }
    if (flags & flag_tree != 0) {
        m.variation = .{ 0, 0.14, 0 };
        m.variation_scale = .{ 0, 9 };
    }
    if (flags & flag_snow != 0) {
        m.clearcoat = 0.25;
        m.roughness = 0.6;
    }
    if (flags & flag_emissive != 0) {
        m.emission = .{ emission, emission * 0.85, emission * 0.6 };
    }
    if (flags & flag_water != 0) {
        m = @import("demo").water();
    }
    if (pyrit.pyr_material_set(@ptrCast(ctx), index, &m) != 0) {
        root.setError("Material {d}: {s}", .{ index, pyrit.pyr_error_message() });
        return -1;
    }
    return 0;
}
