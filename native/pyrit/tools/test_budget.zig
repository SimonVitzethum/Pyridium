//! Optional test-only duty cycle. Drain every CUDA stream before resting, so
//! world generation cannot keep running behind a sleeping render thread.
const std = @import("std");
const builtin = @import("builtin");

// Kernel32-Direktimporte nur für Windows (diese Toolchain hat keine
// fertigen std-Wrapper dafür); auf anderen OS unbenutzt.
const Win = struct {
    extern "kernel32" fn QueryPerformanceCounter(c: *i64) callconv(.c) c_int;
    extern "kernel32" fn QueryPerformanceFrequency(f: *i64) callconv(.c) c_int;
    extern "kernel32" fn Sleep(ms: u32) callconv(.c) void;
};

pub const Budget = struct {
    duty: f64 = 1,
    slept_ns: i64 = 0,

    pub fn now() i64 {
        if (builtin.os.tag == .windows) {
            var f: i64 = 0;
            _ = Win.QueryPerformanceFrequency(&f);
            var c: i64 = 0;
            _ = Win.QueryPerformanceCounter(&c);
            if (f <= 0) return 0;
            return @divTrunc(c * 1000000000, f);
        }
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
        const sleep_ns: i64 = @intFromFloat(@max(10_000_000, @as(f64, @floatFromInt(active)) * (1 / self.duty - 1)));
        const before = now();
        if (builtin.os.tag == .windows) {
            Win.Sleep(@intCast(@divTrunc(sleep_ns + 999999, 1000000)));
        } else {
            var remaining = std.c.timespec{
                .sec = 0,
                .nsec = 0,
            };
            remaining.sec = @divTrunc(sleep_ns, 1_000_000_000);
            remaining.nsec = @mod(sleep_ns, 1_000_000_000);
            while (std.c.nanosleep(&remaining, &remaining) != 0) {
                if (std.c._errno().* != @intFromEnum(std.c.E.INTR)) return error.Sleep;
            }
        }
        self.slept_ns += now() - before;
    }
};
