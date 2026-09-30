//! Frame-time feedback. World LOD stays tied to display resolution.
const std = @import("std");

pub const Resolution = struct {
    scale: f32 = 0.5,
    target_fps: f32 = 165,
    average_ms: f32 = 0,
    samples: u32 = 0,

    pub fn observe(self: *Resolution, ms: f32) bool {
        if (self.target_fps <= 0 or !std.math.isFinite(ms) or ms <= 0) return false;
        self.average_ms = if (self.average_ms == 0) ms else self.average_ms + 0.1 * (ms - self.average_ms);
        self.samples += 1;
        if (self.samples < 60) return false;
        self.samples = 0;
        // Reserve time for GUI/presentation. Preserve a quality floor even
        // when the requested FPS cannot be reached on this hardware.
        const wanted = std.math.clamp(self.scale * @sqrt((900 / self.target_fps) / self.average_ms), 0.5, 1);
        const next = std.math.clamp(wanted, self.scale - 0.05, self.scale + 0.05);
        if (@abs(next - self.scale) < 0.025) return false;
        self.scale = next;
        self.average_ms = 0;
        return true;
    }
};

test "resolution feedback preserves quality floor and rejects invalid timings" {
    const t = std.testing;
    var r = Resolution{};
    for (0..120) |_| try t.expect(!r.observe(30));
    try t.expectEqual(@as(f32, 0.5), r.scale);
    try t.expect(!r.observe(std.math.nan(f32)));
    for (0..59) |_| try t.expect(!r.observe(2));
    try t.expect(r.observe(2));
    try t.expectApproxEqAbs(@as(f32, 0.55), r.scale, 1e-6);
    r.target_fps = 0;
    for (0..120) |_| try t.expect(!r.observe(1));
}
