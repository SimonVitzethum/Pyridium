//! Frame-time feedback. World LOD stays tied to display resolution.
const std = @import("std");

pub const Resolution = struct {
    scale: f32 = 0.5,
    target_fps: f32 = 165,
    average_ms: f32 = 0,
    samples: u32 = 0,

    /// Zero disables adaptive resolution and selects full display resolution.
    /// Re-enabling starts from the usual quality floor instead of stale feedback.
    pub fn configure(self: *Resolution, target_fps: f32) void {
        if (target_fps == 0) {
            self.scale = 1;
        } else if (self.target_fps == 0) {
            self.scale = 0.5;
        }
        self.target_fps = target_fps;
        self.average_ms = 0;
        self.samples = 0;
    }

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
    r.configure(0);
    try t.expectEqual(@as(f32, 1), r.scale);
    for (0..120) |_| try t.expect(!r.observe(1));
}

test "native quality mode clears feedback and survives mode resets" {
    const t = std.testing;
    var r = Resolution{ .scale = 0.7, .average_ms = 4, .samples = 59 };
    r.configure(0);
    try t.expectEqual(@as(f32, 1), r.scale);
    try t.expectEqual(@as(f32, 0), r.average_ms);
    try t.expectEqual(@as(u32, 0), r.samples);
    r = .{};
    r.configure(0);
    try t.expectEqual(@as(f32, 1), r.scale);
    r.configure(165);
    try t.expectEqual(@as(f32, 0.5), r.scale);
    try t.expectEqual(@as(f32, 165), r.target_fps);
}
