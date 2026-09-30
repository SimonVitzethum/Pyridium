//! Optional test-only duty cycle. Drain every CUDA stream before resting, so
//! world generation cannot keep running behind a sleeping render thread.
const std = @import("std");

pub const Budget = struct {
    duty: f64 = 1,
    slept_ns: i64 = 0,

    pub fn now() i64 {
        var ts: std.c.timespec = undefined;
        _ = std.c.clock_gettime(.MONOTONIC, &ts);
        return @as(i64, ts.sec) * 1_000_000_000 + ts.nsec;
    }

    pub fn rest(self: *Budget, driver: anytype, start: i64) !void {
        if (self.duty >= 1) return;
        if (driver.cuCtxSynchronize() != 0) return error.Cuda;
        const active = @max(now() - start, 0);
        // At least 10 ms between short bursts; no accumulated credit permits
        // a long stress run after a slow compilation or a CPU-bound update.
        var remaining = std.c.timespec{
            .sec = 0,
            .nsec = @intFromFloat(@max(10_000_000, @as(f64, @floatFromInt(active)) * (1 / self.duty - 1))),
        };
        remaining.sec = @divTrunc(remaining.nsec, 1_000_000_000);
        remaining.nsec = @mod(remaining.nsec, 1_000_000_000);
        const before = now();
        while (std.c.nanosleep(&remaining, &remaining) != 0) {
            if (std.c._errno().* != @intFromEnum(std.c.E.INTR)) return error.Sleep;
        }
        self.slept_ns += now() - before;
    }
};
