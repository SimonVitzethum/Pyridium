//! Caching-Entscheidungen des Renderpfads: reine Logik, CPU-testbar.
//!
//! - GI nur jeden N-ten Frame neu (`PYRIT_GI_EVERY`, Standard 1 = jeder Frame).
//!   Path Tracing bleibt vollständig: der GI-Durchgang entfällt nie ersatzlos,
//!   sondern der zeitliche Verlauf (`render.giTemporal`) wird wiederverwendet.
//! - Statischer Frame-Reuse (`PYRIT_STATIC_REUSE=1`, opt-in): Kamera (ohne
//!   Jitter) und Szene bitgleich zum Vorframe -> alle Endpuffer werden auf dem
//!   Stream kopiert, kein Kernel läuft, kein Warten. Nur ohne Jitter exakt;
//!   mit Jitter wäre die Abtastung eine andere.
//! - Reprojektions-Match: teilt die Schwellen mit `render.giTemporal`
//!   (Normalen-Cosinus > 0,9, Tiefe 5 % + 0,02 f16-Toleranz).

const std = @import("std");
const types = @import("types.zig");

/// `PYRIT_GI_EVERY=N`: GI-Durchgang nur jeden N-ten Frame neu rechnen.
/// Unbekannt/ungültig -> 1 (jeder Frame, bisheriges Verhalten).
pub fn parseGiEvery(text: ?[]const u8) u32 {
    const t = text orelse return 1;
    const n = std.fmt.parseInt(u32, t, 10) catch return 1;
    return std.math.clamp(n, 1, 16);
}

/// Neuen GI-Durchgang rechnen? `tick` zählt die GI-Frames dieser Ansicht.
/// Bei geänderter Geometrie/Auflösung oder ohne gültigen Verlauf immer.
pub fn giShouldRun(tick: u64, every: u32, hist_ok: bool, geom_changed: bool) bool {
    if (geom_changed or !hist_ok) return true;
    if (every <= 1) return true;
    return tick % every == 0;
}

/// Kein Jitter? Nur dann sind zwei Frames mit gleicher Pose bitgleich
/// abgetastet (Jitter wirkt nur auf die Strahlerzeugung).
pub fn jitterZero(cam: *const types.Camera) bool {
    return cam.jitter[0] == 0 and cam.jitter[1] == 0;
}

/// Kamera bis auf Jitter identisch (Pose, Auflösung, Projektion, Ebenen)?
/// Feldweise statt Bitvergleich: eventuelle Füllbytes dürfen nicht stören.
pub fn cameraStaticForReuse(cur: *const types.Camera, prev: *const types.Camera) bool {
    if (!std.mem.eql(f32, &cur.view_to_world, &prev.view_to_world)) return false;
    if (cur.projection != prev.projection) return false;
    if (cur.width != prev.width or cur.height != prev.height) return false;
    if (!std.mem.eql(f32, &cur.scale, &prev.scale)) return false;
    if (!std.mem.eql(f32, &cur.shift, &prev.shift)) return false;
    if (cur.near_plane != prev.near_plane or cur.far_plane != prev.far_plane) return false;
    return true;
}

/// Reprojektions-Match wie `render.giTemporal`: dieselbe Fläche genau dann,
/// wenn Normale fast gleich und Tiefe innerhalb der Toleranz liegt.
/// `nw` = aktuell (Normale xyz + Tiefe w), `g` = Verlauf.
pub fn reprojMatches(nw: [4]f32, g: [4]f32) bool {
    const nd = nw[0] * g[0] + nw[1] * g[1] + nw[2] * g[2];
    if (!(nd > 0.9)) return false;
    const tol = 0.05 * nw[3] + 0.02;
    return @abs(g[3] - nw[3]) <= tol;
}
