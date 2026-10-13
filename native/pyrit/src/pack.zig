//! Shaderpack-Laufzeit: baut die CUDA-C++-Stages eines Packs mit NVRTC und
//! linkt sie mit den Zig-Kernels (siehe docs/SHADERPACKS.md).
//!
//! Ein Pack ist ein Verzeichnis mit `stages/pack.cu` (Einstieg, darf weitere
//! Dateien aus `stages/` einbinden). Java entpackt Zips und liefert Pfade.
//! Der Pack-Code wird zur Link-Zeit mit den Kernels verbunden: der Heißpfad
//! ruft die Stage-Funktionen direkt (keine Funktionszeiger, keine Indirektion
//! zur Laufzeit). Das übersetzte PTX liegt auf der Platte im Cache-Ordner.

const std = @import("std");
const diag = @import("diag.zig");

/// ABI-Header für Packs (Gerätecode); gehört dem Kern, nicht dem Pack
pub const header_source: []const u8 = @embedFile("pack/pyrit_pack.cuh");
/// Notfall-Pack, falls weder das gewählte noch das Standardpack baut: nur
/// ein neutraler Clamp. Die Optik liefert immer ein richtiges Pack.
pub const fallback_source: []const u8 = @embedFile("pack/fallback.cu");

pub const max_options = 64;

pub const Config = struct {
    dir: [1024]u8 = undefined,
    dir_len: usize = 0,
    fallback_dir: [1024]u8 = undefined,
    fallback_len: usize = 0,
    cache: [1024]u8 = undefined,
    cache_len: usize = 0,
    options: [max_options]f32 = .{0} ** max_options,
    /// Meldung des letzten Baus (Fehler oder Hinweis), für Java/Log
    message: [4096]u8 = undefined,
    message_len: usize = 0,
    /// Name des aktiven Packs ("(Notfall)" bei Fallback)
    active: [256]u8 = undefined,
    active_len: usize = 0,
    generation: u32 = 0,

    fn dirSlice(self: *const Config) []const u8 {
        return self.dir[0..self.dir_len];
    }
};

pub var cfg: Config = .{};

fn copyInto(dst: []u8, len: *usize, src: []const u8) void {
    const n = @min(dst.len, src.len);
    @memcpy(dst[0..n], src[0..n]);
    len.* = n;
}

pub fn setPack(dir: []const u8, fallback_dir: []const u8, cache: []const u8) void {
    copyInto(&cfg.dir, &cfg.dir_len, dir);
    copyInto(&cfg.fallback_dir, &cfg.fallback_len, fallback_dir);
    copyInto(&cfg.cache, &cfg.cache_len, cache);
    cfg.generation +%= 1;
}

pub fn setOption(index: u32, value: f32) void {
    if (index < max_options) cfg.options[index] = value;
}

pub fn message() []const u8 {
    return cfg.message[0..cfg.message_len];
}

fn setMessage(comptime fmt: []const u8, args: anytype) void {
    const s = std.fmt.bufPrint(&cfg.message, fmt, args) catch cfg.message[0..0];
    cfg.message_len = s.len;
}

fn setActive(name: []const u8) void {
    copyInto(&cfg.active, &cfg.active_len, name);
}

// ---------------------------------------------------------------------------
// Dateien (libc, ohne std.Io)
// ---------------------------------------------------------------------------

fn readFile(gpa: std.mem.Allocator, path: []const u8) ?[]u8 {
    var zbuf: [1100]u8 = undefined;
    const z = std.fmt.bufPrintZ(&zbuf, "{s}", .{path}) catch return null;
    const f = std.c.fopen(z.ptr, "rb") orelse return null;
    defer _ = std.c.fclose(f);
    var list: std.ArrayList(u8) = .empty;
    var chunk: [16384]u8 = undefined;
    while (true) {
        const n = std.c.fread(&chunk, 1, chunk.len, f);
        if (n == 0) break;
        list.appendSlice(gpa, chunk[0..n]) catch {
            list.deinit(gpa);
            return null;
        };
    }
    return list.toOwnedSlice(gpa) catch null;
}

fn writeFile(path: []const u8, data: []const u8) void {
    var zbuf: [1100]u8 = undefined;
    const z = std.fmt.bufPrintZ(&zbuf, "{s}", .{path}) catch return;
    const f = std.c.fopen(z.ptr, "wb") orelse return;
    defer _ = std.c.fclose(f);
    _ = std.c.fwrite(data.ptr, 1, data.len, f);
}

// ---------------------------------------------------------------------------
// NVRTC
// ---------------------------------------------------------------------------

const NvrtcProgram = ?*opaque {};

const Nvrtc = struct {
    lib: std.DynLib,
    create: *const fn (*NvrtcProgram, [*:0]const u8, [*:0]const u8, c_int, ?[*]const [*:0]const u8, ?[*]const [*:0]const u8) callconv(.c) c_int,
    compile: *const fn (NvrtcProgram, c_int, ?[*]const [*:0]const u8) callconv(.c) c_int,
    ptx_size: *const fn (NvrtcProgram, *usize) callconv(.c) c_int,
    get_ptx: *const fn (NvrtcProgram, [*]u8) callconv(.c) c_int,
    log_size: *const fn (NvrtcProgram, *usize) callconv(.c) c_int,
    get_log: *const fn (NvrtcProgram, [*]u8) callconv(.c) c_int,
    destroy: *const fn (*NvrtcProgram) callconv(.c) c_int,
    version: *const fn (*c_int, *c_int) callconv(.c) c_int,
};

var nvrtc_cached: ?Nvrtc = null;

fn loadNvrtc() ?*Nvrtc {
    if (nvrtc_cached != null) return &nvrtc_cached.?;
    const names = [_][]const u8{ "libnvrtc.so", "libnvrtc.so.13", "libnvrtc.so.12", "libnvrtc.so.11.2", "/opt/cuda/lib64/libnvrtc.so" };
    for (names) |n| {
        var lib = std.DynLib.open(n) catch continue;
        var out: Nvrtc = undefined;
        out.lib = lib;
        out.create = lib.lookup(@TypeOf(out.create), "nvrtcCreateProgram") orelse continue;
        out.compile = lib.lookup(@TypeOf(out.compile), "nvrtcCompileProgram") orelse continue;
        out.ptx_size = lib.lookup(@TypeOf(out.ptx_size), "nvrtcGetPTXSize") orelse continue;
        out.get_ptx = lib.lookup(@TypeOf(out.get_ptx), "nvrtcGetPTX") orelse continue;
        out.log_size = lib.lookup(@TypeOf(out.log_size), "nvrtcGetProgramLogSize") orelse continue;
        out.get_log = lib.lookup(@TypeOf(out.get_log), "nvrtcGetProgramLog") orelse continue;
        out.destroy = lib.lookup(@TypeOf(out.destroy), "nvrtcDestroyProgram") orelse continue;
        out.version = lib.lookup(@TypeOf(out.version), "nvrtcVersion") orelse continue;
        nvrtc_cached = out;
        return &nvrtc_cached.?;
    }
    return null;
}

fn fnv(h: u64, data: []const u8) u64 {
    var x = h;
    for (data) |b| {
        x ^= b;
        x *%= 0x100000001b3;
    }
    return x;
}

/// Quelle eines Pack-Verzeichnisses (stages/pack.cu) übersetzen. Ergebnis:
/// PTX (nullterminiert, im Besitz des Aufrufers). Bei Fehler steht das
/// Compiler-Log in `message()`.
fn buildPackPtx(gpa: std.mem.Allocator, dir: []const u8, name: []const u8) ?[:0]u8 {
    var pbuf: [1200]u8 = undefined;
    const entry_path = std.fmt.bufPrint(&pbuf, "{s}/stages/pack.cu", .{dir}) catch return null;
    const body = readFile(gpa, entry_path) orelse {
        setMessage("Pack '{s}': stages/pack.cu fehlt in {s}", .{ name, dir });
        return null;
    };
    defer gpa.free(body);
    // Einbindungen (#include "datei") einen Schritt tief mitlesen: sie zaehlen fuer
    // den Cache-Schluessel und fuer die Erkennung vorhandener Stages
    var all: std.ArrayList(u8) = .empty;
    defer all.deinit(gpa);
    all.appendSlice(gpa, body) catch return null;
    var it = std.mem.splitScalar(u8, body, '\n');
    while (it.next()) |line| {
        const k = std.mem.indexOf(u8, line, "#include \"") orelse continue;
        const rest = line[k + 10 ..];
        const e = std.mem.indexOfScalar(u8, rest, '"') orelse continue;
        const inc_path = std.fmt.bufPrint(&pbuf, "{s}/stages/{s}", .{ dir, rest[0..e] }) catch continue;
        if (readFile(gpa, inc_path)) |inc| {
            defer gpa.free(inc);
            all.appendSlice(gpa, "\n") catch return null;
            all.appendSlice(gpa, inc) catch return null;
        }
    }
    return compileSource(gpa, name, body, all.items, dir);
}

fn compileSource(gpa: std.mem.Allocator, name: []const u8, body: []const u8, scan: []const u8, dir: ?[]const u8) ?[:0]u8 {
    const nv = loadNvrtc() orelse {
        setMessage("NVRTC (libnvrtc) nicht gefunden", .{});
        return null;
    };
    var major: c_int = 0;
    var minor: c_int = 0;
    _ = nv.version(&major, &minor);
    // Cache-Schlüssel: Header, Quelle, NVRTC-Version
    var key = fnv(0xcbf29ce484222325, header_source);
    key = fnv(key, scan);
    key = fnv(key, std.mem.asBytes(&major));
    key = fnv(key, std.mem.asBytes(&minor));
    var cpath_buf: [1200]u8 = undefined;
    const have_cache = cfg.cache_len > 0;
    const cpath = if (have_cache) (std.fmt.bufPrint(&cpath_buf, "{s}/{x:0>16}.ptx", .{ cfg.cache[0..cfg.cache_len], key }) catch "") else "";
    if (cpath.len > 0) {
        if (readFile(gpa, cpath)) |cached| {
            defer gpa.free(cached);
            if (gpa.allocSentinel(u8, cached.len, 0)) |out| {
                @memcpy(out, cached);
                return out;
            } else |_| {}
        }
    }

    // fehlende Stages mit den Vorgaben des Headers auffuellen
    const t_display: []const u8 = if (std.mem.indexOf(u8, scan, "PYR_STAGE_DISPLAY") == null) "\nPYR_DEFAULT_DISPLAY_IMPL\n" else "";
    const t_sky: []const u8 = if (std.mem.indexOf(u8, scan, "PYR_STAGE_SKY") == null) "\nPYR_DEFAULT_SKY_IMPL\n" else "";
    const t_sun: []const u8 = if (std.mem.indexOf(u8, scan, "PYR_STAGE_SUN") == null) "\nPYR_DEFAULT_SUN_IMPL\n" else "";
    const src = std.fmt.allocPrintSentinel(gpa, "{s}\n#line 1 \"pack.cu\"\n{s}{s}{s}{s}", .{ header_source, body, t_display, t_sky, t_sun }, 0) catch return null;
    defer gpa.free(src);
    var prog: NvrtcProgram = null;
    var name_z: [300]u8 = undefined;
    const nz = std.fmt.bufPrintZ(&name_z, "{s}.cu", .{name}) catch return null;
    if (nv.create(&prog, src.ptr, nz.ptr, 0, null, null) != 0) {
        setMessage("nvrtcCreateProgram fehlgeschlagen", .{});
        return null;
    }
    defer _ = nv.destroy(&prog);
    var inc_buf: [1300]u8 = undefined;
    var opts: [5][*:0]const u8 = undefined;
    var n_opts: usize = 0;
    opts[n_opts] = "--gpu-architecture=compute_75";
    n_opts += 1;
    opts[n_opts] = "--std=c++17";
    n_opts += 1;
    opts[n_opts] = "--relocatable-device-code=true";
    n_opts += 1;
    opts[n_opts] = "--use_fast_math";
    n_opts += 1;
    if (dir) |d| {
        const inc = std.fmt.bufPrintZ(&inc_buf, "--include-path={s}/stages", .{d}) catch return null;
        opts[n_opts] = inc.ptr;
        n_opts += 1;
    }
    const rc = nv.compile(prog, @intCast(n_opts), &opts);
    var log_n: usize = 0;
    _ = nv.log_size(prog, &log_n);
    if (rc != 0) {
        const logbuf = gpa.alloc(u8, log_n + 1) catch return null;
        defer gpa.free(logbuf);
        logbuf[log_n] = 0;
        if (log_n > 0) _ = nv.get_log(prog, logbuf.ptr);
        setMessage("Pack '{s}': NVRTC-Fehler\n{s}", .{ name, logbuf[0..log_n] });
        return null;
    }
    var n: usize = 0;
    _ = nv.ptx_size(prog, &n);
    const out = gpa.allocSentinel(u8, n, 0) catch return null;
    _ = nv.get_ptx(prog, out.ptr);
    if (cpath.len > 0) writeFile(cpath, out[0 .. n - 1]);
    return out;
}

/// Pack-PTX in Reihenfolge Pack, Standardpack, Notfall bauen. Setzt `message`
/// und den aktiven Namen. Ergebnis im Besitz des Aufrufers.
pub fn buildWithFallback(gpa: std.mem.Allocator) ?[:0]u8 {
    cfg.message_len = 0;
    if (cfg.dir_len > 0) {
        const d = cfg.dirSlice();
        const base = std.fs.path.basename(d);
        if (buildPackPtx(gpa, d, base)) |ptx| {
            setActive(base);
            return ptx;
        }
        const first_error = gpa.dupe(u8, message()) catch return null;
        defer gpa.free(first_error);
        if (cfg.fallback_len > 0 and !std.mem.eql(u8, cfg.fallback_dir[0..cfg.fallback_len], d)) {
            const fd = cfg.fallback_dir[0..cfg.fallback_len];
            if (buildPackPtx(gpa, fd, std.fs.path.basename(fd))) |ptx| {
                setActive(std.fs.path.basename(fd));
                setMessage("{s}\n-> Standardpack aktiv", .{first_error});
                return ptx;
            }
        }
        setMessage("{s}\n-> Notfall-Pack aktiv", .{first_error});
    }
    const ptx = compileSource(gpa, "notfall", fallback_source, fallback_source, null) orelse return null;
    setActive("(Notfall)");
    return ptx;
}

// ---------------------------------------------------------------------------
// Linken
// ---------------------------------------------------------------------------

const cuda = @import("cuda.zig");

/// Kernel-PTX und Pack-PTX zu einem Modul verbinden
pub fn linkModule(drv: *const cuda.Driver, kernels_ptx: [:0]const u8, pack_ptx: [:0]const u8, out: *cuda.CUmodule, err_log: []u8) cuda.CUresult {
    var state: cuda.CUlinkState = null;
    @memset(err_log, 0);
    const opts = [_]c_int{ cuda.CU_JIT_ERROR_LOG_BUFFER, cuda.CU_JIT_ERROR_LOG_BUFFER_SIZE_BYTES };
    var vals = [_]?*anyopaque{ @ptrCast(err_log.ptr), @ptrFromInt(err_log.len) };
    var r = drv.cuLinkCreate_v2(opts.len, &opts, &vals, &state);
    if (r != cuda.CUDA_SUCCESS) return r;
    defer _ = drv.cuLinkDestroy(state);
    r = drv.cuLinkAddData_v2(state, cuda.CU_JIT_INPUT_PTX, @ptrCast(@constCast(kernels_ptx.ptr)), kernels_ptx.len + 1, "pyrit_kernels", 0, null, null);
    if (r != cuda.CUDA_SUCCESS) return r;
    r = drv.cuLinkAddData_v2(state, cuda.CU_JIT_INPUT_PTX, @ptrCast(@constCast(pack_ptx.ptr)), pack_ptx.len + 1, "pack", 0, null, null);
    if (r != cuda.CUDA_SUCCESS) return r;
    var cubin: ?*anyopaque = null;
    var size: usize = 0;
    r = drv.cuLinkComplete(state, &cubin, &size);
    if (r != cuda.CUDA_SUCCESS) return r;
    return drv.cuModuleLoadData(out, cubin);
}

/// Optionswerte in das Modul-Global `pyr_pack_options` kopieren
pub fn uploadOptions(drv: *const cuda.Driver, module: cuda.CUmodule) void {
    var ptr: cuda.CUdeviceptr = 0;
    var size: usize = 0;
    if (drv.cuModuleGetGlobal_v2(&ptr, &size, module, "pyr_pack_options") != cuda.CUDA_SUCCESS) return;
    _ = drv.cuMemcpyHtoD_v2(ptr, &cfg.options, @min(size, @sizeOf(@TypeOf(cfg.options))));
}
