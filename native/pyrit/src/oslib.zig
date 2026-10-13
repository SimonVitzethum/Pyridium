//! Plattform-DynLib mit gleicher API wie std.DynLib (open/lookup/close).
//!
//! Hintergrund: Diese Toolchain kann std.DynLib auf Windows nicht bauen
//! ("unsupported platform"). Auf Windows läuft das Laden über kernel32
//! (LoadLibrary/GetProcAddress); überall sonst ist es exakt std.DynLib
//! (dort also kein Verhaltensunterschied, gleicher Typ).

const std = @import("std");
const builtin = @import("builtin");

pub const DynLib = if (builtin.os.tag == .windows) WinDynLib else std.DynLib;

/// Monotone Millisekunden für Host-Messungen (Windows: Performance-Counter,
/// sonst CLOCK_MONOTONIC). Ersetzt verstreute clock_gettime-Aufrufe, die auf
/// Windows nicht bauen.
pub fn milliTimestamp() i64 {
    if (builtin.os.tag == .windows) {
        var freq: i64 = 0;
        _ = Win32.QueryPerformanceFrequency(&freq);
        var ctr: i64 = 0;
        _ = Win32.QueryPerformanceCounter(&ctr);
        if (freq <= 0) return 0;
        return @divTrunc(ctr * 1000, freq);
    }
    var ts: std.c.timespec = undefined;
    _ = std.c.clock_gettime(.MONOTONIC, &ts);
    return @as(i64, ts.sec) * 1000 + @divTrunc(@as(i64, ts.nsec), 1000000);
}

/// Monotone Nanosekunden (Windows: Performance-Counter).
pub fn nanoTimestamp() u64 {
    if (builtin.os.tag == .windows) {
        var freq: i64 = 0;
        _ = Win32.QueryPerformanceFrequency(&freq);
        var ctr: i64 = 0;
        _ = Win32.QueryPerformanceCounter(&ctr);
        if (freq <= 0) return 0;
        return @as(u64, @intCast(@divTrunc(ctr * 1000000000, freq)));
    }
    var ts: std.c.timespec = undefined;
    _ = std.c.clock_gettime(.MONOTONIC, &ts);
    return @as(u64, @intCast(ts.sec)) * 1_000_000_000 + @as(u64, @intCast(ts.nsec));
}

const Win32 = struct {
    extern "kernel32" fn QueryPerformanceFrequency(lpFrequency: *i64) callconv(.c) c_int;
    extern "kernel32" fn QueryPerformanceCounter(lpPerformanceCount: *i64) callconv(.c) c_int;
    extern "kernel32" fn AcquireSRWLockExclusive(l: *usize) callconv(.c) void;
    extern "kernel32" fn ReleaseSRWLockExclusive(l: *usize) callconv(.c) void;
    extern "kernel32" fn SleepConditionVariableSRW(c: *usize, l: *usize, timeout_ms: u32, flags: u32) callconv(.c) c_int;
    extern "kernel32" fn WakeAllConditionVariable(c: *usize) callconv(.c) void;
};

/// Plattform-Mutex (Windows: SRWLOCK, sonst pthread). Gleiches Verhalten,
/// gleiche Aufrufe pro OS wie bisher.
pub const Mutex = if (builtin.os.tag == .windows) SrwMutex else PthreadMutex;

const SrwMutex = struct {
    state: usize = 0,
    pub fn lock(self: *SrwMutex) void {
        Win32.AcquireSRWLockExclusive(&self.state);
    }
    pub fn unlock(self: *SrwMutex) void {
        Win32.ReleaseSRWLockExclusive(&self.state);
    }
};

const PthreadMutex = struct {
    m: std.c.pthread_mutex_t = std.c.PTHREAD_MUTEX_INITIALIZER,
    pub fn lock(self: *PthreadMutex) void {
        _ = std.c.pthread_mutex_lock(&self.m);
    }
    pub fn unlock(self: *PthreadMutex) void {
        _ = std.c.pthread_mutex_unlock(&self.m);
    }
};

/// Plattform-Condition (Windows: CONDITION_VARIABLE, sonst pthread_cond).
/// Nur unendliches Warten und Broadcast (mehr nutzt der Welt-Arbeiter nicht).
pub const Cond = if (builtin.os.tag == .windows) SrwCond else PthreadCond;

const SrwCond = struct {
    var_: usize = 0,
    pub fn wait(self: *SrwCond, m: *SrwMutex) void {
        _ = Win32.SleepConditionVariableSRW(&self.var_, &m.state, 0xFFFFFFFF, 0);
    }
    pub fn broadcast(self: *SrwCond) void {
        Win32.WakeAllConditionVariable(&self.var_);
    }
};

const PthreadCond = struct {
    c: std.c.pthread_cond_t = std.c.PTHREAD_COND_INITIALIZER,
    pub fn wait(self: *PthreadCond, m: *PthreadMutex) void {
        _ = std.c.pthread_cond_wait(&self.c, &m.m);
    }
    pub fn broadcast(self: *PthreadCond) void {
        _ = std.c.pthread_cond_broadcast(&self.c);
    }
};

const WinDynLib = struct {
    h: std.os.windows.HMODULE,

    const K32 = struct {
        extern "kernel32" fn LoadLibraryW(lpLibFileName: [*:0]const u16) callconv(.c) ?std.os.windows.HMODULE;
        extern "kernel32" fn GetProcAddress(hModule: std.os.windows.HMODULE, lpProcName: [*:0]const u8) callconv(.c) ?*anyopaque;
        extern "kernel32" fn FreeLibrary(hModule: std.os.windows.HMODULE) callconv(.c) c_int;
    };

    pub fn open(path: []const u8) !WinDynLib {
        // UTF-16 passt immer in gleich viele u16 wie das UTF-8 Bytes hat.
        var buf16: [1024]u16 = undefined;
        var fba = std.heap.FixedBufferAllocator.init(std.mem.sliceAsBytes(&buf16));
        const w = std.unicode.utf8ToUtf16LeAllocZ(fba.allocator(), path) catch return error.NameTooLong;
        const h = K32.LoadLibraryW(w.ptr) orelse return error.FileNotFound;
        return .{ .h = h };
    }

    pub fn lookup(self: WinDynLib, comptime T: type, name: [:0]const u8) ?T {
        const addr = K32.GetProcAddress(self.h, name.ptr) orelse return null;
        return @ptrCast(addr);
    }

    pub fn close(self: WinDynLib) void {
        _ = K32.FreeLibrary(self.h);
    }
};
