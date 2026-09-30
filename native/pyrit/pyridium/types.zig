//! Gemeinsame Typen von Host (root.zig), Kerneln (kernels.zig) und Java
//! (simon.pyridium.client.gpu.Sprites). Layout nicht ändern ohne die Java-Seite.

/// Ein GUI-Sprite: Viereck mit Ecken in Pixeln (Zielzeilen, 0 = unten),
/// UV, Farbe je Ecke (RGBA8, mit ColorModulator), Rahmen inkl. Schere.
pub const Sprite = extern struct {
    x: [4]f32,
    y: [4]f32,
    u: [4]f32,
    v: [4]f32,
    color: [4]u32,
    /// x0, y0, x1, y1 (x1/y1 exklusiv), schon mit Schere geschnitten
    bbox: [4]i32,
    /// Textur-Index + 1, 0 = keine
    tex: u32,
    mode: u32,
    /// Mischung: Bits 0-3 Quelle Farbe, 4-7 Ziel Farbe, 8-10 Op Farbe,
    /// 11-14 Quelle Alpha, 15-18 Ziel Alpha, 19-21 Op Alpha, 22 an,
    /// 24-27 Schreibmaske (RGBA)
    blend: u32,
    flags: u32,
};

pub const Tex = extern struct {
    ptr: u64,
    w: u32,
    h: u32,
    /// 1 (R8) oder 4 (RGBA8)
    bpp: u32,
    linear: u32,
    clamp: u32,
    pad: u32 = 0,
};

pub const mode_color: u32 = 0;
pub const mode_tex: u32 = 1;
pub const mode_text: u32 = 2;
pub const mode_text_gray: u32 = 3;
/// Atlas: Textur kopieren / zwei Bilder überblenden (color[0] = Textur 2 + 1, color[1] = f32-Anteil)
pub const mode_copy: u32 = 4;
pub const mode_interp: u32 = 5;

pub const blend_enable: u32 = 1 << 22;
pub const sprite_rect: u32 = 1;

comptime {
    if (@sizeOf(Sprite) != 112) @compileError("Sprite-Layout");
    if (@sizeOf(Tex) != 32) @compileError("Tex-Layout");
}

/// Sektionsspeicher für den Generator (geladene Minecraft-Sektionen, 16³ Blöcke)
pub const SecTable = extern struct {
    /// u64[cap]: Schlüssel (secKey), 0 = frei, maxInt = gelöscht
    keys: u64,
    /// u32[cap]: Bit 31 = einheitlich (Zustand in den unteren 16 Bits), sonst Pool-Platz
    vals: u64,
    /// u16[plätze * 4096], Index (y * 16 + z) * 16 + x
    pool: u64,
    /// u32[zustände]: Pyrit-Attribut je Blockzustand (0 = nichts ausgeben)
    state_attr: u64,
    /// u32[zustände]: state_*
    state_flags: u64,
    /// u32[count]: 1 = Chunk vollständig aus geladenen Sektionen
    known: u64,
    cap_mask: u32,
    nstates: u32,
    y_min: i32,
    y_max: i32,
};

pub const state_solid: u32 = 1; // wird ausgegeben
pub const state_opaque: u32 = 2; // verdeckt Nachbarn
pub const state_water: u32 = 4; // Flüssigkeit (nur Oberfläche ausgeben)
pub const state_tint_grass: u32 = 8; // Farbe = Grasfarbe des Bioms (je Blockspalte)
pub const state_tint_foliage: u32 = 16; // Farbe = Laubfarbe des Bioms

/// Pool-Platz einer Sektion: 4096 Zustände (u16), danach je Blockspalte
/// (z * 16 + x) Gras- und Laubfarbe (u32, 0xRRGGBB)
pub const slot_bytes: usize = 4096 * 2 + 256 * 4 * 2;

pub fn secKey(sx: i32, sy: i32, sz: i32) u64 {
    const ux: u64 = @as(u32, @bitCast(sx +% (1 << 21))) & 0x3fffff;
    const uz: u64 = @as(u32, @bitCast(sz +% (1 << 21))) & 0x3fffff;
    const uy: u64 = @as(u32, @bitCast(sy +% (1 << 19))) & 0xfffff;
    return (ux << 42) | (uz << 20) | uy;
}

pub fn secHash(k: u64) u64 {
    var h = k *% 0x9e3779b97f4a7c15;
    h ^= h >> 29;
    h *%= 0xbf58476d1ce4e5b9;
    h ^= h >> 32;
    return h;
}
