//! Welt über Pyrit: Kontext, gestreamte Welt (Hash-DAG-Chunks mit LOD),
//! Licht, Nachbearbeitung und Hochskalieren. Das Bild landet im
//! Farbziel von Minecraft, danach blendet der GUI-Compositor darüber.

const std = @import("std");
const pyrit = @import("pyrit");
const pyr = @import("pyrit_device");
const api = pyrit.api;
const types = pyr.types;
const scene = @import("demo");
const cuda = pyrit.cuda;
const root = @import("root.zig");
const sections = @import("sections.zig");
const t = @import("types.zig");
const quality = @import("quality.zig");
const spectator = @import("spectator.zig");
const Budget = @import("test_budget").Budget;

const G = &root.G;

/// Kamera aus Java (Weltkoordinaten in Blöcken)
pub const CamIn = extern struct {
    pos: [3]f64,
    forward: [3]f32,
    fov_y: f32,
    up: [3]f32,
    time: f32,
    /// Ausgabegröße (= Minecraft-Hauptziel)
    width: u32,
    height: u32,
    /// Farbziel (RGBA8, unten zuerst)
    target: u64,
    /// Sonnenwinkel (Radiant ab Zenit, negativ = Nacht) und Richtung
    sun_theta: f32,
    sun_phi: f32,
    flags: u32,
    reserved: u32,
};

pub const Mode = enum(u32) { native = 0, taau = 1, dlaa = 2, dlss = 3, rr = 4 };

const W = struct {
    ctx: ?*anyopaque = null,
    world: ?*anyopaque = null,
    spectator_world: ?*anyopaque = null,
    spectator_budget: u64 = 0,
    spectator_active: bool = false,
    spectator_rendered: bool = false,
    spectator_retry_ms: i64 = 0,
    spectator_log_ms: i64 = 0,
    gen: scene.Generator = undefined,
    wi: api.WorldInfo = undefined,
    light: types.Lighting = undefined,
    base_light_flags: u32 = 0,
    features: u32 = 7,
    dlss_warmed: bool = false,
    view: api.Handle = null,
    post: api.PostInfo = undefined,
    fx: api.PostFx = undefined,
    tg: api.Targets = std.mem.zeroes(api.Targets),
    rw: u32 = 0,
    rh: u32 = 0,
    ow: u32 = 0,
    oh: u32 = 0,
    ldr: u64 = 0,
    mode: Mode = .taau,
    frame: u32 = 0,
    ready: bool = false,
    loaded: bool = false,
    stats: api.WorldStats = undefined,
    resolution: quality.Resolution = .{},
    timings: [3][2]cuda.CUevent = .{.{ null, null }} ** 3,
    timing_frame: u64 = 0,
    timing_ready: bool = false,
    test_budget: Budget = .{},
};

var S: W = .{};

/// Shared DAGs need one transform per visible part, not one DAG per actor.
/// Reserve separately from the 65536 world chunks so crowds cannot consume
/// the world streamer's slots. This is a rendering capacity, not a mob limit.
pub const max_model_parts: u32 = 1 << 20;

fn req(r: api.Result, what: []const u8) bool {
    if (r == api.ok) return true;
    root.setError("Pyrit {s}: {s}: {s}", .{ what, pyrit.pyr_result_string(r), pyrit.pyr_error_message() });
    return false;
}

fn devAlloc(bytes: usize) u64 {
    var p: u64 = 0;
    if (G.drv.cuMemAlloc_v2(&p, bytes) != 0) return 0;
    return p;
}

fn freeTargets() void {
    for ([_]u64{ S.tg.hits, S.tg.motion, S.tg.color, S.tg.normal, S.tg.albedo, S.tg.material, S.ldr }) |b| {
        if (b != 0) _ = G.drv.cuMemFree_v2(b);
    }
    S.tg = std.mem.zeroes(api.Targets);
    S.ldr = 0;
    S.rw = 0;
    S.rh = 0;
}

fn scaleOf(m: Mode) u32 {
    return switch (m) {
        .native, .dlaa => 1,
        .taau, .dlss, .rr => 2,
    };
}

fn upscalerOf(m: Mode) u32 {
    return switch (m) {
        .native => api.upscaler_none,
        .taau => api.upscaler_taau,
        .dlaa, .dlss => api.upscaler_dlss,
        .rr => api.upscaler_dlss_rr,
    };
}

fn ensureTargets(ow: u32, oh: u32) bool {
    const scale: f32 = if (scaleOf(S.mode) == 1) 1 else S.resolution.scale;
    const rw = @max(@as(u32, @intFromFloat(@as(f32, @floatFromInt(ow)) * scale)), 16);
    const rh = @max(@as(u32, @intFromFloat(@as(f32, @floatFromInt(oh)) * scale)), 16);
    if (S.rw == rw and S.rh == rh and S.ow == ow and S.oh == oh) return true;
    if (!root.ok(G.drv.cuStreamSynchronize(G.stream), "Renderziele wechseln")) return false;
    freeTargets();
    const n: usize = @as(usize, rw) * rh;
    S.tg.hits = devAlloc(n * 16);
    S.tg.motion = devAlloc(n * 8);
    S.tg.color = devAlloc(n * 16);
    S.tg.normal = devAlloc(n * 16);
    S.tg.albedo = devAlloc(n * 16);
    if (S.mode == .rr) S.tg.material = devAlloc(n * 8);
    S.tg.ray_mask = 0x1;
    S.tg.coverage = if (S.mode == .native) 2 else 1;
    S.tg.detail_scale = @as(f32, @floatFromInt(oh)) / @as(f32, @floatFromInt(rh));
    S.ldr = devAlloc(@as(usize, ow) * oh * 4);
    if (S.tg.hits == 0 or S.tg.motion == 0 or S.tg.color == 0 or S.tg.normal == 0 or S.tg.albedo == 0 or
        (S.mode == .rr and S.tg.material == 0) or S.ldr == 0)
    {
        freeTargets();
        root.setError("Weltziele: nicht genügend CUDA-Speicher", .{});
        return false;
    }
    S.rw = rw;
    S.rh = rh;
    S.ow = ow;
    S.oh = oh;
    S.frame = 0;
    S.resolution.average_ms = 0;
    S.resolution.samples = 0;
    return true;
}

const LodFn = *const fn (keys: u64, count: i32, voxels: u64, counts: u64, capacity: i32, known: u64) callconv(.c) i32;
var lod_cb: ?LodFn = null;

/// Java: LOD-Generator (exakte Vanilla-Dichte auf der GPU) für Pyrits Aufträge
export fn pyd_set_lod_generator(f: ?LodFn) void {
    lod_cb = f;
}
var d_known: u64 = 0;
var d_known_len: usize = 0;
var h_keys: std.ArrayList(pyr.types.ChunkKey) = .empty;
var h_known: std.ArrayList(u32) = .empty;
var generate_mutex: std.c.pthread_mutex_t = std.c.PTHREAD_MUTEX_INITIALIZER;

/// Generator (Pyrits Arbeiter-Thread): erst das Rückfall-Gelände für alle
/// Chunks, dann überschreibt der Sektions-Kern die Chunks, die ganz in
/// geladenen Minecraft-Spalten liegen.
fn generate(user: ?*anyopaque, params: *const types.WorldGenParams, stream: ?*anyopaque) callconv(.c) void {
    // Both streamers use the same section tables and staging buffers. Do not
    // duplicate worldgen or race the reusable host/device scratch storage.
    _ = std.c.pthread_mutex_lock(&generate_mutex);
    defer _ = std.c.pthread_mutex_unlock(&generate_mutex);
    const cache_only = user == @as(?*anyopaque, @ptrCast(&S.spectator_world));
    const n = params.count;
    if (n == 0) return;
    const st: cuda.CUstream = @ptrCast(stream);
    h_keys.resize(std.heap.c_allocator, n) catch return;
    h_known.resize(std.heap.c_allocator, n) catch return;
    // Schlüssel holen; danach ist auch Pyrits Nullen der Zähler erledigt
    if (G.drv.cuMemcpyDtoHAsync_v2(h_keys.items.ptr, params.chunks, n * @sizeOf(pyr.types.ChunkKey), st) != 0) return;
    if (G.drv.cuStreamSynchronize(st) != 0) return;
    var tab = sections.known(h_keys.items, h_known.items);
    // Fernbereich: exakter Vanilla-Generator (Java, GPU); sonst Rückfall-Gelände
    var handled = false;
    if (!cache_only) if (lod_cb) |cb| {
        handled = cb(@intFromPtr(h_keys.items.ptr), @intCast(n), params.voxels, params.counts, @intCast(params.capacity), @intFromPtr(h_known.items.ptr)) == 1;
    };
    // Never substitute a different demo world for missing Minecraft data.
    // Counts were cleared by Pyrit; loaded sections below remain renderable.
    if (!handled) _ = G.drv.cuMemsetD8Async(params.counts, 0, @as(usize, n) * 4, st);
    var any = false;
    for (h_known.items) |k| any = any or k != 0;
    if (!any or tab.pool == 0 or tab.state_attr == 0) return;
    if (d_known_len < n) {
        if (d_known != 0) _ = G.drv.cuMemFree_v2(d_known);
        d_known_len = @max(n, 1024);
        if (G.drv.cuMemAlloc_v2(&d_known, d_known_len * 4) != 0) {
            d_known = 0;
            d_known_len = 0;
            return;
        }
    }
    _ = G.drv.cuMemcpyHtoDAsync_v2(d_known, h_known.items.ptr, n * 4, st);
    tab.known = d_known;
    var g = params.*;
    var args = [_]?*anyopaque{ @ptrCast(&g), @ptrCast(&tab) };
    _ = G.drv.cuLaunchKernel(G.k_gen_sections, n, 1, 1, 256, 1, 1, 0, st, &args, null);
    // d_known is shared by both workers. It cannot be overwritten while the
    // previous worker's kernel still reads it, even on low-priority streams.
    _ = G.drv.cuStreamSynchronize(st);
}

fn terrainBudget(bytes: u64) void {
    const config = api.WorldConfig{ .voxel_pixels = S.wi.voxel_pixels, .view_distance = S.wi.view_distance, .memory_budget = bytes };
    _ = pyrit.pyr_world_configure(@ptrCast(S.ctx), @ptrCast(S.world), &config);
}

fn releaseSpectator() void {
    if (S.spectator_world) |w| {
        _ = pyrit.pyr_world_destroy(@ptrCast(S.ctx), @ptrCast(w));
        S.spectator_world = null;
        S.spectator_budget = 0;
        S.spectator_rendered = false;
        S.frame = 0;
        terrainBudget(S.wi.memory_budget);
        std.debug.print("[pyridium] Spectator-DAG freigegeben\n", .{});
    }
}

fn updateSpectator(c: *const CamIn, o: *const [3]f64, cam: *const types.Camera) void {
    const wanted = c.flags & 3 != 0;
    const active = c.flags & 1 != 0;
    if (active != S.spectator_active) {
        S.spectator_active = active;
        S.frame = 0; // reset reconstruction after changing terrain masks
    }
    S.tg.ray_mask = 1;
    S.tg.flags = 0;
    S.tg.secondary_mask = 0;
    if (!wanted and S.spectator_world == null) return;
    var free_b: usize = 0;
    var total_b: usize = 0;
    if (G.drv.cuMemGetInfo_v2(&free_b, &total_b) != 0) return;
    if (S.spectator_world != null and spectator.Policy.release(free_b, wanted)) {
        releaseSpectator();
        S.spectator_retry_ms = nowMs() + 5000;
    }
    if (wanted and S.spectator_world == null and nowMs() >= S.spectator_retry_ms) {
        S.spectator_retry_ms = nowMs() + 5000;
        const bytes = spectator.Policy.budget(free_b, S.wi.memory_budget);
        if (bytes != 0) {
            var wi = S.wi;
            wi.user = @ptrCast(&S.spectator_world);
            wi.max_lod = 1; // exactly LOD0; the usual adaptive far world stays separate
            wi.view_chunks = 4;
            wi.view_distance = 128;
            wi.memory_budget = bytes;
            wi.mask = spectator.ray_mask;
            wi.secondary_mask = 0;
            wi.chunks_per_update = 32;
            wi.chunk_capacity = 32768;
            wi.keep_frames = 120;
            if (pyrit.pyr_world_create(@ptrCast(S.ctx), &wi, @ptrCast(&S.spectator_world)) == api.ok) {
                S.spectator_budget = bytes;
                terrainBudget(S.wi.memory_budget - bytes);
                std.debug.print("[pyridium] Spectator-DAG angelegt: {d} MiB Budget, 128 Bloecke Radius, nur geladene Sektionen\n", .{bytes >> 20});
            }
        }
    }
    if (S.spectator_world) |w| {
        if (pyrit.pyr_world_update(@ptrCast(S.ctx), @ptrCast(w), &c.pos, o, cam) != api.ok) {
            releaseSpectator(); // optional cache must never disable the terrain renderer
            S.spectator_retry_ms = nowMs() + 5000;
            return;
        }
        var stats: api.WorldStats = undefined;
        _ = pyrit.pyr_world_stats(@ptrCast(w), &stats);
        // LOD0 cannot coarsen in response to pressure. Its optional cache
        // therefore needs a hard release policy in addition to WorldConfig.
        if (stats.bytes > S.spectator_budget) {
            std.debug.print("[pyridium] Spectator-DAG: Budget erreicht, Vorbau pausiert\n", .{});
            releaseSpectator();
            S.spectator_retry_ms = nowMs() + 60000;
            return;
        }
        // Never replace the near scene with an unbuilt cache. Once ready,
        // near rays must not hit the approximate far world's cave fillings.
        if (active and stats.pending_chunks == 0 and stats.resident_chunks != 0) {
            S.tg.ray_mask = 1 | spectator.ray_mask;
            S.tg.flags = types.trace_spectator;
            // Approximate near terrain must not cast fictitious cave shadows
            // or black out GI. Loaded cave walls also serve secondary rays.
            S.tg.secondary_mask = spectator.ray_mask;
        }
        const rendered = S.tg.flags & types.trace_spectator != 0;
        if (rendered != S.spectator_rendered) {
            S.spectator_rendered = rendered;
            S.frame = 0;
        }
        if (nowMs() >= S.spectator_log_ms) {
            S.spectator_log_ms = nowMs() + 10000;
            std.debug.print("[pyridium] Spectator-DAG: aktiv {any}, gerendert {any}, sichtbar {d}, resident {d}, offen {d}, {d} MiB\n", .{ active, rendered, stats.visible_chunks, stats.resident_chunks, stats.pending_chunks, stats.bytes >> 20 });
        }
    }
}

/// Pyrit-Kontext und Welt anlegen. seed: Weltsamen (Demo-Generator vorerst)
export fn pyd_world_create(seed: u64, mode: u32, features: u32) i32 {
    root.bind();
    if (S.ready) return 0;
    if (features & ~@as(u32, 7) != 0) {
        root.setError("Unbekannte Renderer-Features", .{});
        return -1;
    }
    S.features = features;
    if (std.c.getenv("PYRIDIUM_TEST_GPU_DUTY")) |value| {
        const duty = std.fmt.parseFloat(f64, std.mem.span(value)) catch 0;
        if (!std.math.isFinite(duty) or duty <= 0 or duty > 1) {
            root.setError("PYRIDIUM_TEST_GPU_DUTY muss in (0, 1] liegen", .{});
            return -1;
        }
        S.test_budget.duty = duty;
    }
    const gpa = std.heap.c_allocator;
    var ci = scene.createInfo(0);
    ci.max_instances += max_model_parts;
    ci.flags |= api.create_material16;
    if (features & 4 == 0) ci.flags |= api.create_no_rt;
    ci.cuda_context = @ptrCast(G.ctx);
    ci.cuda_stream = @ptrCast(G.stream);
    ci.max_textures = 65536; // Shared face tiles + lightweight material recipes
    // Speicher nach freiem VRAM: Reserve für Ziele, DLSS, Generator und andere Programme,
    // der Rest für die DAG-Pools (40 % Knoten, 35 % Blätter, 25 % Attribute)
    var free_b: usize = 0;
    var total_b: usize = 0;
    _ = G.drv.cuMemGetInfo_v2(&free_b, &total_b);
    const reserve: usize = 1536 << 20;
    const usable: usize = if (free_b > reserve + (768 << 20)) free_b - reserve else 768 << 20;
    // WorldStats in the 1024-chunk Full-HD run use ~200 MiB. Reserving most
    // VRAM for mostly-empty pools starves PBR, RR/replay scratch and worldgen.
    // Leave the majority for those actual workloads; LOD already reacts to
    // the measured DAG budget rather than requiring an oversized fixed pool.
    const pools = usable * 3 / 10;
    ci.node_pool_bytes = pools * 4 / 10;
    ci.leaf_pool_bytes = pools * 35 / 100;
    ci.attribute_pool_bytes = pools * 25 / 100;
    std.debug.print("[pyridium] VRAM frei {d} MiB: DAG-Pools {d} MiB\n", .{ free_b >> 20, pools >> 20 });
    if (!req(pyrit.pyr_create(&ci, @ptrCast(&S.ctx)), "Kontext")) return -1;
    S.gen = scene.Generator.init(&G.drv, .{ .seed = @truncate(seed) }) catch {
        root.setError("Generator", .{});
        return -1;
    };
    S.wi = S.gen.worldInfo();
    S.wi.voxel_pixels = 1;
    if (S.test_budget.duty < 1) {
        S.wi.flags |= api.world_sync;
        S.wi.chunks_per_update = 16;
        std.debug.print("[pyridium] Testbudget {d:.0} %, synchrone Weltbatches\n", .{S.test_budget.duty * 100});
    }
    S.wi.generate = generate;
    // Chunk-Budget: vier Fünftel der Pools, darüber wird die Welt gröber
    S.wi.memory_budget = (ci.node_pool_bytes + ci.leaf_pool_bytes + ci.attribute_pool_bytes) * 4 / 5;
    S.wi.user = &S;
    if (!req(pyrit.pyr_world_create(@ptrCast(S.ctx), &S.wi, @ptrCast(&S.world)), "Welt")) return -1;
    S.light = scene.lighting(S.ctx, gpa, .{}) catch {
        root.setError("Licht", .{});
        return -1;
    };
    // Edge-aware temporal reconstruction reduces GI work, retaining full
    // resolution for primary hits, textures and direct shadows.
    S.light.flags |= types.lighting_gi_half;
    // Diagnostic comparison only; defaults and player settings stay unchanged.
    // Direct lighting retains PBR, shadow/refraction rays and atmospheric fog,
    // but launches no diffuse bounce/GI or scene-reflection rays.
    if (std.c.getenv("PYRIDIUM_TEST_LIGHTING")) |value| {
        const preset = std.mem.span(value);
        if (std.mem.eql(u8, preset, "direct")) {
            S.light.flags &= ~(types.lighting_gi | types.lighting_gi_half |
                types.lighting_ao | types.lighting_reflections);
        } else if (!std.mem.eql(u8, preset, "path")) {
            root.setError("Unbekanntes Test-Licht: {s} (path/direct)", .{preset});
            return -1;
        }
        std.debug.print("[pyridium] Test-Licht: {s}, Flags 0x{x}; GI {any}, Szenenreflexionen {any}, RT-Schatten {any}\n", .{
            preset,                                      S.light.flags,
            S.light.flags & types.lighting_gi != 0,      S.light.flags & types.lighting_reflections != 0,
            S.light.flags & types.lighting_shadows != 0,
        });
    }
    S.base_light_flags = S.light.flags;
    S.light.flags = types.lightingFeatures(S.base_light_flags, features & 1 != 0, features & 2 != 0);
    if (!req(pyrit.pyr_set_lighting(@ptrCast(S.ctx), &S.light), "Licht")) return -1;
    if (!req(pyrit.pyr_view_create(@ptrCast(S.ctx), &S.view), "Ansicht")) return -1;
    S.post = scene.post();
    S.post.denoise_iterations = 2;
    S.fx = scene.fx();
    S.post.fx = &S.fx;
    S.mode = @enumFromInt(@min(mode, 4));
    S.ready = true;
    // DLSS (NGX) braucht beim Initialisieren viel Stapel; Javas Render-Thread hat nur
    // rund 1 MiB. Deshalb einmal in einem eigenen Thread mit 64 MiB anwerfen.
    warmDlss();
    logFeatures();
    return 0;
}

fn logFeatures() void {
    std.debug.print("[pyridium] Renderer-Features: RR {any}, GI {any}, RT-Effekte {any}, RT-Cores {any}; Modus {s}\n", .{
        S.mode == .rr,                                      S.light.flags & types.lighting_gi != 0,
        S.light.flags & types.lighting_no_ray_effects == 0, pyrit.pyr_features(@ptrCast(S.ctx)) & api.feature_rt_cores != 0,
        @tagName(S.mode),
    });
}

export fn pyd_world_set_features(features: u32) i32 {
    root.bind();
    if (!S.ready or features & ~@as(u32, 7) != 0) {
        root.setError("Welt nicht bereit oder unbekannte Renderer-Features", .{});
        return -1;
    }
    if (features == S.features) return 0;
    if ((features ^ S.features) & 4 != 0) {
        root.setError("RT-Core-Wechsel braucht einen neuen Weltkontext", .{});
        return -1;
    }
    var light = S.light;
    light.flags = types.lightingFeatures(S.base_light_flags, features & 1 != 0, features & 2 != 0);
    if (!req(pyrit.pyr_set_lighting(@ptrCast(S.ctx), &light), "Renderer-Features")) return -1;
    if (!req(pyrit.pyr_view_reset_history(@ptrCast(S.ctx), S.view), "Lichtverlauf zuruecksetzen")) return -1;
    S.light = light;
    S.features = features;
    S.frame = 0; // Invalidate the old lighting history.
    logFeatures();
    return 0;
}

fn warmDlss() void {
    if (S.dlss_warmed or S.mode == .native or S.mode == .taau) return;
    const thread = std.Thread.spawn(.{ .stack_size = 64 << 20 }, dlssWarm, .{}) catch {
        root.setError("DLSS-Initialisierungsthread nicht verfuegbar - weiter mit TAAU", .{});
        S.mode = .taau;
        return;
    };
    thread.join();
    S.dlss_warmed = true;
}

fn dlssWarm() void {
    root.bind();
    if (!ensureTargets(256, 256)) return;
    var cam = std.mem.zeroes(types.Camera);
    pyrit.pyr_camera_look_at(&cam, &.{ 0, 100, 0 }, &.{ 0, 90, 10 }, &.{ 0, 1, 0 });
    pyrit.pyr_camera_perspective(&cam, 1.2, S.rw, S.rh, 0.05);
    const fi = api.FrameInfo{ .time = 0, .origin = .{ 0, 0, 0 } };
    _ = pyrit.pyr_commit(@ptrCast(S.ctx), &fi);
    _ = pyrit.pyr_render(@ptrCast(S.ctx), S.view, &cam, &S.tg);
    S.post.upscaler = upscalerOf(S.mode);
    S.post.output_ldr = S.ldr;
    S.post.output_width = S.ow;
    S.post.output_height = S.oh;
    S.post.flags = api.post_reset;
    if (pyrit.pyr_postprocess(@ptrCast(S.ctx), S.view, &S.tg, &S.post) != api.ok) {
        root.setError("DLSS: {s} - weiter mit TAAU", .{pyrit.pyr_error_message()});
        S.mode = .taau;
    }
    _ = G.drv.cuCtxSynchronize();
    S.rw = 0; // Ziele beim ersten echten Frame in echter Größe
}

pub fn context() ?*anyopaque {
    return if (S.ready) S.ctx else null;
}

pub fn restTest(start_ns: i64) bool {
    S.test_budget.rest(&G.drv, start_ns) catch return false;
    return true;
}

/// Streaming policy and frame-time target. 0 FPS disables adaptation.
export fn pyd_world_configure(px: f32, view_chunks: u32, target_fps: f32) i32 {
    root.bind();
    if (!S.ready or view_chunks == 0 or view_chunks > 32768 or !std.math.isFinite(target_fps) or target_fps < 0) {
        root.setError("Weltkonfiguration: Sichtweite 1..32768, FPS endlich und >= 0 erforderlich", .{});
        return -1;
    }
    const config = api.WorldConfig{
        .voxel_pixels = px,
        .view_distance = @floatFromInt(view_chunks * 16),
        .memory_budget = S.wi.memory_budget - S.spectator_budget,
    };
    if (!req(pyrit.pyr_world_configure(@ptrCast(S.ctx), @ptrCast(S.world), &config), "Weltkonfiguration")) return -1;
    S.wi.voxel_pixels = px;
    S.wi.view_distance = config.view_distance;
    S.resolution.configure(target_fps);
    return 0;
}

export fn pyd_world_set_mode(mode: u32) void {
    const m: Mode = @enumFromInt(@min(mode, 4));
    if (m == S.mode) return;
    if (S.ready and !req(pyrit.pyr_view_reset_history(@ptrCast(S.ctx), S.view), "Bildverlauf zuruecksetzen")) return;
    S.mode = m;
    const target_fps = S.resolution.target_fps;
    S.resolution = .{};
    S.resolution.configure(target_fps);
    S.rw = 0; // Ziele neu
    // Also safe when RR is first enabled after starting in native mode.
    warmDlss();
    logFeatures();
}

pub fn nowMs() i64 {
    var ts: std.c.timespec = undefined;
    _ = std.c.clock_gettime(.MONOTONIC, &ts);
    return @as(i64, ts.sec) * 1000 + @divTrunc(@as(i64, ts.nsec), 1000000);
}

fn origin(pos: [3]f64) [3]f64 {
    return .{ @floor(pos[0] / 1024) * 1024, 0, @floor(pos[2] / 1024) * 1024 };
}

fn camera(c: *const CamIn, cam: *types.Camera, w: u32, h: u32) void {
    const o = origin(c.pos);
    const eye = [3]f32{ @floatCast(c.pos[0] - o[0]), @floatCast(c.pos[1] - o[1]), @floatCast(c.pos[2] - o[2]) };
    const target = [3]f32{ eye[0] + c.forward[0] * 100, eye[1] + c.forward[1] * 100, eye[2] + c.forward[2] * 100 };
    pyrit.pyr_camera_look_at(cam, &eye, &target, &c.up);
    pyrit.pyr_camera_perspective(cam, c.fov_y, w, h, 0.05);
    cam.jitter = .{ 0, 0 };
    if (S.mode != .native) pyrit.pyr_jitter_halton(S.frame, &cam.jitter);
}

/// Einen Frame der Welt in c.target rendern
export fn pyd_world_frame(c: *const CamIn) i32 {
    root.bind();
    const burst_start = Budget.now();
    if (!S.ready or c.width == 0 or c.height == 0) return -1;
    // Delayed event queries measure CUDA work without waiting. Vsync and
    // artificial test sleeps cannot lower the reconstruction quality.
    const slot = S.timing_frame % S.timings.len;
    if (!S.timing_ready) {
        for (&S.timings) |*pair| for (pair) |*ev| {
            if (ev.* == null and !root.ok(G.drv.cuEventCreate(ev, 0), "Framezeit-Ereignis")) return -1;
        };
        S.timing_ready = true;
    }
    if (S.timing_frame >= S.timings.len and G.drv.cuEventQuery(S.timings[slot][1]) == 0) {
        var ms: f32 = 0;
        if (G.drv.cuEventElapsedTime(&ms, S.timings[slot][0], S.timings[slot][1]) == 0 and scaleOf(S.mode) != 1)
            _ = S.resolution.observe(ms);
    }
    if (!ensureTargets(c.width, c.height)) return -1;
    if (S.ldr == 0) {
        root.setError("Weltziele: kein Speicher", .{});
        return -1;
    }
    var cam = std.mem.zeroes(types.Camera);
    camera(c, &cam, S.rw, S.rh);
    const o = origin(c.pos);
    // LOD nach der Ausgabeauflösung wählen
    var out_cam = cam;
    out_cam.width = S.ow;
    out_cam.height = S.oh;
    // GPU ist hier frei (die Ausgabe des Vorframes hat synchronisiert): die
    // Welt darf Chunks übernehmen, ohne laufende Frames zu stören
    // Der Render-Thread wartet nie auf die Welt: sie füllt sich fortlaufend
    // (grob zuerst, der Planer verfeinert), der Frame bleibt flüssig.
    S.loaded = true;
    sections.flush(S.ctx, S.world, S.spectator_world);
    if (!req(pyrit.pyr_world_update(@ptrCast(S.ctx), @ptrCast(S.world), &c.pos, &o, &out_cam), "Update")) return -1;
    updateSpectator(c, &o, &out_cam);
    const fi = api.FrameInfo{ .time = c.time, .origin = o };
    if (!req(pyrit.pyr_commit(@ptrCast(S.ctx), &fi), "Commit")) return -1;
    _ = G.drv.cuEventRecord(S.timings[slot][0], G.stream);
    if (!req(pyrit.pyr_render(@ptrCast(S.ctx), S.view, &cam, &S.tg), "Render")) return -1;
    S.post.upscaler = upscalerOf(S.mode);
    S.post.output_ldr = S.ldr;
    S.post.output_width = S.ow;
    S.post.output_height = S.oh;
    S.post.flags = if (S.frame == 0) api.post_reset else 0;
    if (!req(pyrit.pyr_postprocess(@ptrCast(S.ctx), S.view, &S.tg, &S.post), "Nachbearbeitung")) return -1;
    _ = G.drv.cuEventRecord(S.timings[slot][1], G.stream);
    if (root.flipCopy(S.ldr, c.target, S.ow, S.oh) != 0) return -1;
    // Pyridium presents via its shared CUDA stream, not pyr_synchronize.
    // Explicitly collect Pyrit's diagnostic events after that existing wait;
    // otherwise the event ring fills and no GPU breakdown is ever reported.
    if (std.c.getenv("PYRIT_GPU_TIMING") != null)
        if (!req(pyrit.pyr_synchronize(@ptrCast(S.ctx)), "GPU-Profil")) return -1;
    S.frame += 1;
    S.timing_frame += 1;
    S.test_budget.rest(&G.drv, burst_start) catch {
        root.setError("GPU-Testbudget: Synchronisierung/Pause fehlgeschlagen", .{});
        return -1;
    };
    return 0;
}

/// Welt verlassen: Pyrit-Welt und -Kontext freigeben (aller Grafikspeicher)
export fn pyd_world_destroy() void {
    @import("materials.zig").reset();
    root.bind();
    if (!S.ready) return;
    lod_cb = null;
    _ = G.drv.cuCtxSynchronize();
    @import("models.zig").reset();
    for (S.timings) |pair| for (pair) |ev| {
        if (ev != null) _ = G.drv.cuEventDestroy_v2(ev);
    };
    _ = pyrit.pyr_world_destroy(@ptrCast(S.ctx), @ptrCast(S.world));
    if (S.spectator_world) |w| _ = pyrit.pyr_world_destroy(@ptrCast(S.ctx), @ptrCast(w));
    freeTargets();
    S.gen.deinit();
    pyrit.pyr_destroy(@ptrCast(S.ctx));
    sections.reset();
    S = .{};
}

/// Planer-Parameter (siehe pyr_world_plan_info); 0 = ok
export fn pyd_world_plan_info(out: *[11]f64) i32 {
    if (!S.ready) return -1;
    return pyrit.pyr_world_plan_info(@ptrCast(S.world), out);
}

export fn pyd_world_stats(out: *api.WorldStats) void {
    if (S.ready) _ = pyrit.pyr_world_stats(@ptrCast(S.world), out);
}
