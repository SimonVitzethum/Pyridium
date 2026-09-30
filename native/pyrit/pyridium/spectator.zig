//! One bounded loaded-area cache. Hysteresis avoids churn near the VRAM limit.
const std = @import("std");
pub const ray_mask: u32 = 4;
pub const Policy = struct {
    pub fn budget(free_bytes: usize, world_budget: u64) u64 {
        if (free_bytes < 768 << 20) return 0;
        // DAG pools are already allocated: divide their existing quota, not
        // the free VRAM again. Leave room for streaming/OptiX build scratch.
        return @min(128 << 20, world_budget / 8);
    }
    pub fn release(free_bytes: usize, wanted: bool) bool {
        return !wanted or free_bytes < 384 << 20;
    }
};
test "spectator cache has bounded pooled budget and VRAM hysteresis" {
    const t = std.testing;
    try t.expectEqual(@as(u64, 0), Policy.budget(512 << 20, 1024 << 20));
    try t.expectEqual(@as(u64, 128 << 20), Policy.budget(2048 << 20, 2048 << 20));
    try t.expectEqual(@as(u64, 32 << 20), Policy.budget(2048 << 20, 256 << 20));
    try t.expect(!Policy.release(512 << 20, true));
    try t.expect(Policy.release(256 << 20, true));
    try t.expect(Policy.release(2048 << 20, false));
}
