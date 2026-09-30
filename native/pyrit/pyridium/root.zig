//! libpyridium: native Seite des Minecraft-Renderers.
//!
//! - GPU-Speicher und Kopien für Minecrafts Texturen (CUDA, ein Stream)
//! - GUI-Compositor (2D-Sprites, kernels.zig) – kein Vertex- oder Shaderpfad
//! - Welt über Pyrit (world.zig)
//! - Ausgabe direkt in die wl_surface des SDL-Fensters (shm-Puffer, eigene
//!   Event-Queue, damit SDLs Ereignisschleife unberührt bleibt)
//!
//! Alle Funktionen werden aus Java (FFM) auf dem Render-Thread gerufen.

const std = @import("std");
const pyrit = @import("pyrit");
const cuda = pyrit.cuda;
const t = @import("types.zig");
const wl = @import("wayland.zig");
pub const world = @import("world.zig");
pub const sections = @import("sections.zig");
pub const materials = @import("materials.zig");
pub const models = @import("models.zig");

const ptx: [:0]const u8 = @embedFile("pyridium_ptx");

comptime {
    _ = world;
    _ = sections;
    _ = materials;
    _ = models;
}

// ---------------------------------------------------------------------------
// Zustand
// ---------------------------------------------------------------------------

const Extra = struct {
    lib: std.DynLib,
    cuMemHostRegister_v2: *const fn (?*anyopaque, usize, c_uint) callconv(.c) cuda.CUresult,
    cuMemHostUnregister: *const fn (?*anyopaque) callconv(.c) cuda.CUresult,
    cuMemHostGetDevicePointer_v2: *const fn (*cuda.CUdeviceptr, ?*anyopaque, c_uint) callconv(.c) cuda.CUresult,
};

pub const Gpu = struct {
    drv: cuda.Driver = undefined,
    ext: Extra = undefined,
    dev: cuda.CUdevice = 0,
    ctx: cuda.CUcontext = null,
    stream: cuda.CUstream = null,
    module: cuda.CUmodule = null,
    k_composite: cuda.CUfunction = null,
    k_fill: cuda.CUfunction = null,
    k_present: cuda.CUfunction = null,
    k_flip: cuda.CUfunction = null,
    // Arbeitspuffer für Sprite-Listen
    scratch: u64 = 0,
    scratch_bytes: usize = 0,
    ready: bool = false,
};

pub var G: Gpu = .{};

threadlocal var err_buf: [512]u8 = undefined;
threadlocal var err_len: usize = 0;
threadlocal var ctx_bound: bool = false;

pub fn setError(comptime fmt: []const u8, args: anytype) void {
    const s = std.fmt.bufPrint(err_buf[0 .. err_buf.len - 1], fmt, args) catch err_buf[0 .. err_buf.len - 1];
    err_len = s.len;
    err_buf[err_len] = 0;
    std.debug.print("[pyridium] {s}\n", .{s});
}

export fn pyd_error() [*:0]const u8 {
    if (err_len == 0) return "";
    return @ptrCast(&err_buf);
}

pub fn ok(r: cuda.CUresult, what: []const u8) bool {
    if (r == cuda.CUDA_SUCCESS) return true;
    setError("CUDA {s}: {s}", .{ what, G.drv.errorString(r) });
    return false;
}

pub fn bind() void {
    if (!ctx_bound and G.ready) {
        _ = G.drv.cuCtxSetCurrent(G.ctx);
        ctx_bound = true;
    }
}

fn loadExtra() !Extra {
    var lib = std.DynLib.open("libcuda.so.1") catch return error.NotFound;
    var e: Extra = undefined;
    e.lib = lib;
    inline for (std.meta.fields(Extra)) |f| {
        if (comptime std.mem.eql(u8, f.name, "lib")) continue;
        @field(e, f.name) = lib.lookup(f.type, f.name) orelse return error.NotFound;
    }
    return e;
}

fn loadCtxFlags() ?*const fn (cuda.CUdevice, c_uint) callconv(.c) cuda.CUresult {
    var lib = std.DynLib.open("libcuda.so.1") catch return null;
    return lib.lookup(*const fn (cuda.CUdevice, c_uint) callconv(.c) cuda.CUresult, "cuDevicePrimaryCtxSetFlags_v2");
}

/// CUDA einrichten; 0 = in Ordnung
export fn pyd_init() i32 {
    if (G.ready) return 0;
    G.drv = cuda.Driver.load() catch {
        setError("libcuda nicht gefunden", .{});
        return -1;
    };
    G.ext = loadExtra() catch {
        setError("libcuda: Funktionen fehlen", .{});
        return -1;
    };
    if (!ok(G.drv.cuInit(0), "cuInit")) return -1;
    if (!ok(G.drv.cuDeviceGet(&G.dev, 0), "cuDeviceGet")) return -1;
    // blockierend warten statt drehen: sonst belegt jede Synchronisation einen CPU-Kern
    if (loadCtxFlags()) |setFlags| _ = setFlags(G.dev, 0x4);
    if (!ok(G.drv.cuDevicePrimaryCtxRetain(&G.ctx, G.dev), "Kontext")) return -1;
    if (!ok(G.drv.cuCtxSetCurrent(G.ctx), "Kontext")) return -1;
    // Bildstream mit höchster Priorität: Weltbau und Generator laufen darunter
    var lo: c_int = 0;
    var hi: c_int = 0;
    _ = G.drv.cuCtxGetStreamPriorityRange(&lo, &hi);
    if (!ok(G.drv.cuStreamCreateWithPriority(&G.stream, cuda.CU_STREAM_NON_BLOCKING, hi), "Stream")) return -1;
    // Speicherpool nicht ans System zurückgeben (sonst kostet jede Allokation)
    var pool: cuda.CUmemoryPool = null;
    if (G.drv.cuDeviceGetDefaultMemPool(&pool, G.dev) == cuda.CUDA_SUCCESS) {
        var thr: u64 = std.math.maxInt(u64);
        _ = G.drv.cuMemPoolSetAttribute(pool, cuda.CU_MEMPOOL_ATTR_RELEASE_THRESHOLD, @ptrCast(&thr));
    }
    if (!ok(G.drv.cuModuleLoadDataEx(&G.module, ptx.ptr, 0, null, null), "Kernel laden")) return -1;
    const names = .{ .{ "k_composite", "pyd_k_composite" }, .{ "k_fill", "pyd_k_fill" }, .{ "k_present", "pyd_k_present" }, .{ "k_flip", "pyd_k_flip_copy" } };
    inline for (names) |n| {
        if (!ok(G.drv.cuModuleGetFunction(&@field(G, n[0]), G.module, n[1]), n[1])) return -1;
    }
    G.ready = true;
    ctx_bound = true;
    return 0;
}

export fn pyd_device_name(buf: [*]u8, len: i32) i32 {
    return G.drv.cuDeviceGetName(buf, len, G.dev);
}

export fn pyd_mem_info(free_out: *u64, total_out: *u64) void {
    bind();
    var f: usize = 0;
    var tt: usize = 0;
    _ = G.drv.cuMemGetInfo_v2(&f, &tt);
    free_out.* = f;
    total_out.* = tt;
}

// ---------------------------------------------------------------------------
// Speicher und Kopien (alles auf dem einen Stream, in Aufrufreihenfolge)
// ---------------------------------------------------------------------------

export fn pyd_alloc(bytes: u64) u64 {
    bind();
    var p: cuda.CUdeviceptr = 0;
    if (!ok(G.drv.cuMemAllocAsync(&p, @max(bytes, 16), G.stream), "Allokation")) return 0;
    _ = G.drv.cuMemsetD8Async(p, 0, @max(bytes, 16), G.stream);
    return p;
}

export fn pyd_free(p: u64) void {
    if (p == 0) return;
    bind();
    _ = G.drv.cuMemFreeAsync(p, G.stream);
}

/// Host -> Gerät. Quelle darf danach sofort wiederverwendet werden.
export fn pyd_upload(dst: u64, src: ?*const anyopaque, n: u64) i32 {
    bind();
    if (n == 0) return 0;
    return if (ok(G.drv.cuMemcpyHtoDAsync_v2(dst, src, n, G.stream), "Upload")) 0 else -1;
}

fn copy2d(kind_src: c_int, src_host: ?*const anyopaque, src_dev: u64, spitch: u64, kind_dst: c_int, dst_host: ?*anyopaque, dst_dev: u64, dpitch: u64, wbytes: u64, rows: u64) i32 {
    var c = std.mem.zeroes(cuda.CUDA_MEMCPY2D);
    c.srcMemoryType = kind_src;
    c.srcHost = src_host;
    c.srcDevice = src_dev;
    c.srcPitch = spitch;
    c.dstMemoryType = kind_dst;
    c.dstHost = dst_host;
    c.dstDevice = dst_dev;
    c.dstPitch = dpitch;
    c.WidthInBytes = wbytes;
    c.Height = rows;
    return if (ok(G.drv.cuMemcpy2DAsync_v2(&c, G.stream), "2D-Kopie")) 0 else -1;
}

export fn pyd_upload2d(dst: u64, dpitch: u64, src: ?*const anyopaque, spitch: u64, wbytes: u64, rows: u64) i32 {
    bind();
    if (wbytes == 0 or rows == 0) return 0;
    return copy2d(cuda.CU_MEMORYTYPE_HOST, src, 0, spitch, cuda.CU_MEMORYTYPE_DEVICE, null, dst, dpitch, wbytes, rows);
}

/// Gerät -> Host, wartet auf den Stream
export fn pyd_download2d(dst: ?*anyopaque, dpitch: u64, src: u64, spitch: u64, wbytes: u64, rows: u64) i32 {
    bind();
    if (wbytes == 0 or rows == 0) return 0;
    if (copy2d(cuda.CU_MEMORYTYPE_DEVICE, null, src, spitch, cuda.CU_MEMORYTYPE_HOST, dst, 0, dpitch, wbytes, rows) != 0) return -1;
    return if (ok(G.drv.cuStreamSynchronize(G.stream), "Sync")) 0 else -1;
}

export fn pyd_copy(dst: u64, src: u64, n: u64) i32 {
    bind();
    if (n == 0) return 0;
    return if (ok(G.drv.cuMemcpyDtoDAsync_v2(dst, src, n, G.stream), "Kopie")) 0 else -1;
}

export fn pyd_copy2d(dst: u64, dpitch: u64, src: u64, spitch: u64, wbytes: u64, rows: u64) i32 {
    bind();
    if (wbytes == 0 or rows == 0) return 0;
    return copy2d(cuda.CU_MEMORYTYPE_DEVICE, null, src, spitch, cuda.CU_MEMORYTYPE_DEVICE, null, dst, dpitch, wbytes, rows);
}

export fn pyd_sync() i32 {
    bind();
    return if (ok(G.drv.cuStreamSynchronize(G.stream), "Sync")) 0 else -1;
}

/// Ereignis am Ende der bisher eingereihten Arbeit (Zaun je Frame statt Stream-Sync).
/// Ereignisse kommen aus einem kleinen Vorrat; 0 = Fehler.
var ev_free: [256]cuda.CUevent = undefined;
var ev_nfree: usize = 0;

export fn pyd_event_record() u64 {
    bind();
    var e: cuda.CUevent = undefined;
    if (ev_nfree > 0) {
        ev_nfree -= 1;
        e = ev_free[ev_nfree];
    } else if (!ok(G.drv.cuEventCreate(&e, 0x1 | 0x2), "Ereignis")) return 0; // blockierend warten, ohne Zeitmessung
    if (!ok(G.drv.cuEventRecord(e, G.stream), "Ereignis setzen")) return 0;
    return @intFromPtr(e);
}

/// 1 = fertig. block = 0: nur nachsehen
export fn pyd_event_wait(ev: u64, block: i32) i32 {
    bind();
    if (ev == 0) return 1;
    const e: cuda.CUevent = @ptrFromInt(ev);
    if (block == 0) return if (G.drv.cuEventQuery(e) == 0) 1 else 0;
    return if (ok(G.drv.cuEventSynchronize(e), "Ereignis warten")) 1 else 0;
}

export fn pyd_event_free(ev: u64) void {
    bind();
    if (ev == 0) return;
    const e: cuda.CUevent = @ptrFromInt(ev);
    if (ev_nfree < ev_free.len) {
        ev_free[ev_nfree] = e;
        ev_nfree += 1;
    } else _ = G.drv.cuEventDestroy_v2(e);
}

pub fn launch(f: cuda.CUfunction, gx: u32, gy: u32, bx: u32, by: u32, args: []?*anyopaque, what: []const u8) i32 {
    return if (ok(G.drv.cuLaunchKernel(f, gx, gy, 1, bx, by, 1, 0, G.stream, args.ptr, null), what)) 0 else -1;
}

/// Rechteck füllen; value: bis zu 16 Bytes Muster je Pixel
export fn pyd_fill(dst: u64, w: u32, bpp: u32, x0: u32, y0: u32, rw: u32, rh: u32, value: *const [4]u32) i32 {
    bind();
    if (rw == 0 or rh == 0) return 0;
    var a = .{ dst, w, bpp, x0, y0, rw, rh, value[0], value[1], value[2], value[3] };
    var args: [11]?*anyopaque = undefined;
    inline for (0..11) |i| args[i] = @ptrCast(&a[i]);
    return launch(G.k_fill, (rw + 15) / 16, (rh + 15) / 16, 16, 16, &args, "Füllen");
}

fn ensureScratch(bytes: usize) bool {
    if (G.scratch_bytes >= bytes) return true;
    if (G.scratch != 0) _ = G.drv.cuMemFreeAsync(G.scratch, G.stream);
    var n: usize = @max(bytes, 1 << 20);
    n = std.math.ceilPowerOfTwo(usize, n) catch n;
    if (!ok(G.drv.cuMemAllocAsync(&G.scratch, n, G.stream), "Sprite-Puffer")) {
        G.scratch = 0;
        G.scratch_bytes = 0;
        return false;
    }
    G.scratch_bytes = n;
    return true;
}

/// Sprites in Reihenfolge in ein RGBA8-Ziel blenden
export fn pyd_composite(target: u64, tw: u32, th: u32, sprites: [*]const t.Sprite, count: u32, texs: [*]const t.Tex, ntex: u32, region: *const [4]i32) i32 {
    bind();
    if (count == 0) return 0;
    const sb = @as(usize, count) * @sizeOf(t.Sprite);
    const tb = @as(usize, @max(ntex, 1)) * @sizeOf(t.Tex);
    const tex_off = std.mem.alignForward(usize, sb, 256);
    if (!ensureScratch(tex_off + tb)) return -1;
    if (pyd_upload(G.scratch, sprites, sb) != 0) return -1;
    if (ntex > 0 and pyd_upload(G.scratch + tex_off, texs, @as(u64, ntex) * @sizeOf(t.Tex)) != 0) return -1;
    const rx0 = @max(region[0], 0);
    const ry0 = @max(region[1], 0);
    const rx1 = @min(region[2], @as(i32, @intCast(tw)));
    const ry1 = @min(region[3], @as(i32, @intCast(th)));
    if (rx1 <= rx0 or ry1 <= ry0) return 0;
    var a = .{ target, tw, th, G.scratch, count, G.scratch + tex_off, rx0, ry0, rx1, ry1 };
    var args: [10]?*anyopaque = undefined;
    inline for (0..10) |i| args[i] = @ptrCast(&a[i]);
    const gx: u32 = @intCast(@divFloor(rx1 - rx0 + 15, 16));
    const gy: u32 = @intCast(@divFloor(ry1 - ry0 + 15, 16));
    return launch(G.k_composite, gx, gy, 16, 16, &args, "Compositor");
}

/// Pyrit-Bild (oben zuerst) in ein Minecraft-Ziel (unten zuerst)
pub fn flipCopy(src: u64, dst: u64, w: u32, h: u32) i32 {
    var a = .{ src, dst, w, h };
    var args: [4]?*anyopaque = .{ @ptrCast(&a[0]), @ptrCast(&a[1]), @ptrCast(&a[2]), @ptrCast(&a[3]) };
    return launch(G.k_flip, (w + 15) / 16, (h + 15) / 16, 16, 16, &args, "Umkopieren");
}

// ---------------------------------------------------------------------------
// Ausgabe über Wayland (wl_surface des SDL-Fensters)
// ---------------------------------------------------------------------------

const nbuf = 3;

const Out = struct {
    c: wl.Client = undefined,
    display: *wl.Display = undefined,
    queue: *wl.Queue = undefined,
    display_wrap: ?*wl.Proxy = null,
    surface: *wl.Proxy = undefined,
    surface_wrap: ?*wl.Proxy = null,
    shm: ?*wl.Proxy = null,
    pool: ?*wl.Proxy = null,
    bufs: [nbuf]?*wl.Proxy = .{null} ** nbuf,
    busy: [nbuf]bool = .{false} ** nbuf,
    mem: []align(std.heap.page_size_min) u8 = &.{},
    mem_dev: u64 = 0,
    registered: bool = false,
    fd: std.posix.fd_t = -1,
    w: u32 = 0,
    h: u32 = 0,
    frame_pending: bool = false,
    /// eingereihtes, noch nicht gezeigtes Bild (nbuf = keins)
    pending: usize = nbuf,
    pending_ev: u64 = 0,
    pending_w: u32 = 0,
    pending_h: u32 = 0,
    ready: bool = false,
    shown: u64 = 0,
    done_count: u64 = 0,
    release_count: u64 = 0,
    waited_ms: i64 = 0,
    last_present_ms: i64 = 0,
};

var O: Out = .{};

const BufferListener = extern struct { release: *const fn (?*anyopaque, *wl.Proxy) callconv(.c) void };
fn onRelease(data: ?*anyopaque, _: *wl.Proxy) callconv(.c) void {
    const k: usize = @intFromPtr(data) - 1;
    if (k < nbuf) O.busy[k] = false;
    O.release_count += 1;
}
var buffer_listener = BufferListener{ .release = onRelease };

const CallbackListener = extern struct { done: *const fn (?*anyopaque, *wl.Proxy, u32) callconv(.c) void };
fn onFrameDone(_: ?*anyopaque, cb: *wl.Proxy, _: u32) callconv(.c) void {
    O.frame_pending = false;
    O.done_count += 1;
    O.c.proxy_destroy(cb);
}
var callback_listener = CallbackListener{ .done = onFrameDone };

const RegistryListener = extern struct {
    global: *const fn (?*anyopaque, *wl.Proxy, u32, [*:0]const u8, u32) callconv(.c) void,
    global_remove: *const fn (?*anyopaque, *wl.Proxy, u32) callconv(.c) void,
};
fn onGlobal(_: ?*anyopaque, reg: *wl.Proxy, name: u32, iface: [*:0]const u8, _: u32) callconv(.c) void {
    if (std.mem.eql(u8, std.mem.span(iface), "wl_shm")) {
        O.shm = O.c.proxy_marshal_flags(reg, wl.registry_bind, O.c.shm, 1, 0, name, O.c.shm.name, @as(u32, 1), @as(?*anyopaque, null));
    }
}
fn onGlobalRemove(_: ?*anyopaque, _: *wl.Proxy, _: u32) callconv(.c) void {}
var registry_listener = RegistryListener{ .global = onGlobal, .global_remove = onGlobalRemove };

fn marshal(p: *wl.Proxy, opc: u32, iface: ?*const wl.Interface, args: anytype) ?*wl.Proxy {
    const ver = O.c.proxy_get_version(p);
    return @call(.auto, O.c.proxy_marshal_flags, .{ p, opc, iface, ver, @as(u32, 0) } ++ args);
}

/// Ereignisse unserer Queue lesen (SDL liest dieselbe Verbindung: daher das
/// prepare/read-Protokoll). timeout_ms < 0 = blockieren.
fn pump(timeout_ms: i32) void {
    while (O.c.display_prepare_read_queue(O.display, O.queue) != 0) {
        _ = O.c.display_dispatch_queue_pending(O.display, O.queue);
    }
    _ = O.c.display_flush(O.display);
    var fds = [1]std.posix.pollfd{.{ .fd = O.c.display_get_fd(O.display), .events = std.posix.POLL.IN, .revents = 0 }};
    const n = std.posix.poll(&fds, timeout_ms) catch 0;
    if (n > 0) _ = O.c.display_read_events(O.display) else O.c.display_cancel_read(O.display);
    _ = O.c.display_dispatch_queue_pending(O.display, O.queue);
}

/// display/surface: wl_display* und wl_surface* aus SDL
export fn pyd_present_init(display: ?*anyopaque, surface: ?*anyopaque) i32 {
    bind();
    if (O.ready) return 0;
    O.c = wl.Client.load() catch {
        setError("libwayland-client nicht ladbar", .{});
        return -1;
    };
    O.display = @ptrCast(display orelse return -1);
    O.surface = @ptrCast(surface orelse return -1);
    O.queue = O.c.display_create_queue(O.display) orelse return -1;
    O.display_wrap = O.c.proxy_create_wrapper(@ptrCast(O.display));
    O.c.proxy_set_queue(O.display_wrap.?, O.queue);
    O.surface_wrap = O.c.proxy_create_wrapper(@ptrCast(O.surface));
    O.c.proxy_set_queue(O.surface_wrap.?, O.queue);
    const reg = O.c.proxy_marshal_flags(O.display_wrap.?, wl.display_get_registry, O.c.registry, 1, 0, @as(?*anyopaque, null)) orelse return -1;
    _ = O.c.proxy_add_listener(reg, @ptrCast(&registry_listener), null);
    _ = O.c.display_roundtrip_queue(O.display, O.queue);
    if (O.shm == null) {
        setError("wl_shm fehlt", .{});
        return -1;
    }
    O.ready = true;
    return 0;
}

fn destroyBuffers() void {
    for (&O.bufs) |*b| if (b.*) |p| {
        O.c.proxy_destroy(p);
        b.* = null;
    };
    if (O.pool) |p| {
        O.c.proxy_destroy(p);
        O.pool = null;
    }
    if (O.registered) {
        _ = G.drv.cuStreamSynchronize(G.stream);
        _ = G.ext.cuMemHostUnregister(O.mem.ptr);
        O.registered = false;
    }
    if (O.mem.len != 0) {
        std.posix.munmap(O.mem);
        O.mem = &.{};
    }
    if (O.fd >= 0) {
        _ = std.c.close(O.fd);
        O.fd = -1;
    }
    O.w = 0;
    O.h = 0;
}

fn resize(w: u32, h: u32) !void {
    if (O.w == w and O.h == h) return;
    // eingereihtes Bild gehoert zu den alten Puffern: fertig werden lassen und verwerfen
    if (O.pending < nbuf) {
        _ = pyd_event_wait(O.pending_ev, 1);
        pyd_event_free(O.pending_ev);
        O.pending_ev = 0;
        O.pending = nbuf;
    }
    destroyBuffers();
    const stride = w * 4;
    const one = std.mem.alignForward(usize, @as(usize, stride) * h, std.heap.page_size_min);
    const size = one * nbuf;
    O.fd = try std.posix.memfd_create("pyridium", 0);
    if (std.c.ftruncate(O.fd, @intCast(size)) != 0) return error.ShmResize;
    O.mem = try std.posix.mmap(null, size, .{ .READ = true, .WRITE = true }, .{ .TYPE = .SHARED }, O.fd, 0);
    // shm direkt für die GPU abbilden: der Kernel schreibt ohne Umweg hinein
    if (G.ext.cuMemHostRegister_v2(O.mem.ptr, size, 0x2) == cuda.CUDA_SUCCESS) {
        if (G.ext.cuMemHostGetDevicePointer_v2(&O.mem_dev, O.mem.ptr, 0) == cuda.CUDA_SUCCESS) {
            O.registered = true;
        } else _ = G.ext.cuMemHostUnregister(O.mem.ptr);
    }
    if (!O.registered) std.debug.print("[pyridium] shm nicht registrierbar, Umweg über Kopie\n", .{});
    O.pool = marshal(O.shm.?, wl.shm_create_pool, O.c.shm_pool, .{ @as(?*anyopaque, null), O.fd, @as(i32, @intCast(size)) }) orelse return error.NoPool;
    O.c.proxy_set_queue(O.pool.?, O.queue);
    for (&O.bufs, 0..) |*b, k| {
        b.* = marshal(O.pool.?, wl.shm_pool_create_buffer, O.c.buffer, .{
            @as(?*anyopaque, null),      @as(i32, @intCast(one * k)),
            @as(i32, @intCast(w)),       @as(i32, @intCast(h)),
            @as(i32, @intCast(stride)), wl.shm_format_xrgb8888,
        }) orelse return error.NoBuffer;
        O.c.proxy_set_queue(b.*.?, O.queue);
        _ = O.c.proxy_add_listener(b.*.?, @ptrCast(&buffer_listener), @ptrFromInt(k + 1));
        O.busy[k] = false;
    }
    O.w = w;
    O.h = h;
}

var tmp_dev: u64 = 0;
var tmp_bytes: usize = 0;

/// Minecraft-Ziel (RGBA8, unten zuerst, sw x sh) in einem dw x dh großen
/// Fensterpuffer anzeigen. vsync: auf den Frame-Callback des Compositors warten.
/// Das eingereihte Bild an den Compositor geben (wartet nur auf dessen Ereignis)
fn commitPending() void {
    const k = O.pending;
    if (k >= nbuf) return;
    _ = pyd_event_wait(O.pending_ev, 1);
    pyd_event_free(O.pending_ev);
    O.pending_ev = 0;
    O.pending = nbuf;
    const surf = O.surface_wrap.?;
    _ = marshal(surf, wl.surface_attach, null, .{ O.bufs[k].?, @as(i32, 0), @as(i32, 0) });
    _ = marshal(surf, wl.surface_damage_buffer, null, .{ @as(i32, 0), @as(i32, 0), @as(i32, @intCast(O.pending_w)), @as(i32, @intCast(O.pending_h)) });
    if (marshal(surf, wl.surface_frame, O.c.callback, .{@as(?*anyopaque, null)})) |cb| {
        _ = O.c.proxy_add_listener(cb, @ptrCast(&callback_listener), null);
        O.frame_pending = true;
    }
    _ = marshal(surf, wl.surface_commit, null, .{});
    O.busy[k] = true;
    _ = O.c.display_flush(O.display);
}

export fn pyd_present(src: u64, sw: u32, sh: u32, dw: u32, dh: u32, vsync: i32) i32 {
    bind();
    if (!O.ready or dw == 0 or dh == 0) return -1;
    resize(dw, dh) catch |e| {
        setError("Fensterpuffer: {s}", .{@errorName(e)});
        return -1;
    };
    pump(0);
    // auf einen freien Puffer und ggf. den Bildtakt warten (höchstens ~0,1 s)
    var guard: u32 = 0;
    const t_wait = world.nowMs();
    defer O.waited_ms += world.nowMs() - t_wait;
    // Bildtakt: auf den Frame-Callback höchstens ein Bildintervall warten.
    // Kommt keiner (Fenster verdeckt, Bildschirm aus), läuft die Anzeige über
    // die Uhr weiter, statt jedes Bild 0,2 s zu blockieren.
    const deadline = O.last_present_ms + 7;
    while (guard < 100) : (guard += 1) {
        const free = for (O.busy) |b| {
            if (!b) break true;
        } else false;
        const vs_ok = vsync == 0 or !O.frame_pending or world.nowMs() >= deadline;
        if (free and vs_ok) break;
        pump(1);
    }
    O.last_present_ms = world.nowMs();
    // Vorheriges Bild zeigen, sobald es fertig ist (die GPU hat es meist schon, waehrend die
    // CPU dieses vorbereitet hat): CPU und GPU laufen um ein Bild versetzt.
    commitPending();
    var k: usize = 0;
    while (k < nbuf and (O.busy[k] or k == O.pending)) k += 1;
    if (k == nbuf) {
        // Compositor gibt nichts frei (verdeckt?): Bild auslassen
        return 0;
    }
    const one = std.mem.alignForward(usize, @as(usize, dw * 4) * dh, std.heap.page_size_min);
    var dst: u64 = undefined;
    if (O.registered) {
        dst = O.mem_dev + one * k;
    } else {
        const need = @as(usize, dw * 4) * dh;
        if (tmp_bytes < need) {
            if (tmp_dev != 0) _ = G.drv.cuMemFreeAsync(tmp_dev, G.stream);
            if (!ok(G.drv.cuMemAllocAsync(&tmp_dev, need, G.stream), "Ausgabepuffer")) return -1;
            tmp_bytes = need;
        }
        dst = tmp_dev;
    }
    var a = .{ src, sw, sh, dst, dw, dh, dw * 4 };
    var args: [7]?*anyopaque = undefined;
    inline for (0..7) |i| args[i] = @ptrCast(&a[i]);
    if (launch(G.k_present, (dw + 15) / 16, (dh + 15) / 16, 16, 16, &args, "Ausgabe") != 0) return -1;
    if (!O.registered) {
        _ = G.drv.cuMemcpyDtoHAsync_v2(O.mem.ptr + one * k, dst, @as(usize, dw * 4) * dh, G.stream);
    }
    O.pending_ev = pyd_event_record();
    O.pending = k;
    O.pending_w = dw;
    O.pending_h = dh;
    O.shown += 1;
    if (O.shown % 120 == 0 and std.c.getenv("PYRIDIUM_DEBUG") != null) {
        std.debug.print("[pyridium] Anzeige: {d} Bilder, {d} Frame-Callbacks, {d} Freigaben, {d} ms gewartet\n", .{ O.shown, O.done_count, O.release_count, O.waited_ms });
        O.waited_ms = 0;
    }
    return 0;
}
