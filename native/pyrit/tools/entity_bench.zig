//! Repeated humanoid HashDAGs, not 500k simulated Minecraft mobs. Every actor
//! remains in the IAS and participates in shadow/GI rays. Animation runs on
//! CUDA; the measured loop submits only time and camera, with no CPU pose loop.
//! zig build entity-bench --release=fast -- --entities 500000 --animated
const std = @import("std");
const pyrit = @import("pyrit");
const pyr = @import("pyrit_device");
const api = pyrit.api;
const types = pyr.types;
const cuda = pyrit.cuda;

var drv: cuda.Driver = undefined;
extern "c" fn setenv([*:0]const u8, [*:0]const u8, c_int) c_int;
fn req(r: api.Result) !void {
    if (r == api.ok) return;
    std.debug.print("Pyrit: {s}: {s}\n", .{ pyrit.pyr_result_string(r), pyrit.pyr_error_message() });
    return error.Renderer;
}
fn cu(r: cuda.CUresult) !void {
    if (r == cuda.CUDA_SUCCESS) return;
    std.debug.print("CUDA: {s}\n", .{drv.errorString(r)});
    return error.Cuda;
}
fn alloc(bytes: usize) !u64 {
    var p: u64 = 0;
    try cu(drv.cuMemAlloc_v2(&p, bytes));
    return p;
}
fn ns(cpu: bool) i64 {
    var ts: std.c.timespec = undefined;
    _ = std.c.clock_gettime(if (cpu) .PROCESS_CPUTIME_ID else .MONOTONIC, &ts);
    return @as(i64, ts.sec) * 1_000_000_000 + ts.nsec;
}
fn matrix(scale: f32, x: f32, y: f32, z: f32) [12]f32 {
    return .{ scale, 0, 0, x, 0, scale, 0, y, 0, 0, scale, z };
}
fn humanoid(init: std.process.Init, ctx: ?*anyopaque) !api.Handle {
    var vox: std.ArrayList(api.Voxel) = .empty;
    defer vox.deinit(init.gpa);
    // 32-voxel tall silhouette, coloured skin, tunic and boots.
    for (0..32) |y| for (0..24) |x| for (0..12) |z| {
        const head = y >= 24 and x >= 8 and x < 16 and z >= 2 and z < 10;
        const body = y >= 12 and y < 24 and x >= 6 and x < 18 and z >= 3 and z < 9;
        const arms = y >= 12 and y < 23 and ((x >= 2 and x < 6) or (x >= 18 and x < 22)) and z >= 4 and z < 8;
        const legs = y < 12 and ((x >= 6 and x < 11) or (x >= 13 and x < 18)) and z >= 3 and z < 9;
        if (!(head or body or arms or legs)) continue;
        const attribute = if (head) pyrit.pyr_voxel_attribute(0, 216, 168, 122) else if (legs) pyrit.pyr_voxel_attribute(0, 56, 67, 100) else pyrit.pyr_voxel_attribute(0, 58, 166, 137);
        try vox.append(init.gpa, .{ .x = @intCast(x), .y = @intCast(y), .z = @intCast(z), .attribute = attribute });
    };
    var geo: api.Handle = null;
    try req(pyrit.pyr_geometry_build(@ptrCast(ctx), 5, vox.items.ptr, @intCast(vox.items.len), api.build_host_input, &geo));
    return geo;
}

pub fn main(init: std.process.Init) !void {
    const args = try init.minimal.args.toSlice(init.arena.allocator());
    var count: u32 = 500000;
    var frames: u32 = 120;
    var w: u32 = 1920;
    var h: u32 = 1080;
    var scale: u32 = 1;
    var animated = false;
    var rt_threads: ?[:0]const u8 = null;
    var out: []const u8 = "/tmp/pyrit-crowd.ppm";
    var i: usize = 1;
    while (i < args.len) : (i += 1) {
        if (std.mem.eql(u8, args[i], "--animated")) {
            animated = true;
        } else if (i + 1 < args.len) {
            const key = args[i];
            i += 1;
            if (std.mem.eql(u8, key, "--entities")) count = try std.fmt.parseInt(u32, args[i], 10) else if (std.mem.eql(u8, key, "--frames")) frames = try std.fmt.parseInt(u32, args[i], 10) else if (std.mem.eql(u8, key, "--out")) out = args[i] else if (std.mem.eql(u8, key, "--rt-threads")) {
                if (try std.fmt.parseInt(u32, args[i], 10) == 0) return error.Usage;
                rt_threads = try init.arena.allocator().dupeZ(u8, args[i]);
            } else if (std.mem.eql(u8, key, "--scale")) {
                scale = try std.fmt.parseInt(u32, args[i], 10);
            } else if (std.mem.eql(u8, key, "--size")) {
                var wh = std.mem.splitScalar(u8, args[i], 'x');
                w = try std.fmt.parseInt(u32, wh.next() orelse return error.Usage, 10);
                h = try std.fmt.parseInt(u32, wh.next() orelse return error.Usage, 10);
            } else return error.Usage;
        } else return error.Usage;
    }
    if (count == 0 or count > 2000000 or frames < 2 or w == 0 or h == 0 or w > 3840 or h > 2160 or (scale != 1 and scale != 2) or w % scale != 0 or h % scale != 0) return error.Usage;
    if (rt_threads) |value| if (setenv("PYRIT_RT_THREADS", value.ptr, 1) != 0) return error.Environment;
    drv = try cuda.Driver.load();
    defer drv.lib.close();
    try cu(drv.cuInit(0));
    var device: cuda.CUdevice = 0;
    try cu(drv.cuDeviceGet(&device, 0));
    var primary: cuda.CUcontext = null;
    try cu(drv.cuDevicePrimaryCtxRetain(&primary, device));
    defer _ = drv.cuDevicePrimaryCtxRelease_v2(device);
    try cu(drv.cuCtxSetCurrent(primary));
    var ci = std.mem.zeroes(api.CreateInfo);
    ci.struct_size = @sizeOf(api.CreateInfo);
    ci.version = api.version;
    ci.cuda_context = @ptrCast(primary);
    ci.flags = api.create_force_rt;
    ci.max_instances = count;
    ci.max_geometries = 16;
    ci.node_pool_bytes = 16 << 20;
    ci.leaf_pool_bytes = 16 << 20;
    ci.attribute_pool_bytes = 16 << 20;
    // Deliberately small ring also exercises batched commit uploads.
    ci.staging_bytes = 4 << 20;
    var ctx: ?*anyopaque = null;
    try req(pyrit.pyr_create(&ci, @ptrCast(&ctx)));
    defer pyrit.pyr_destroy(@ptrCast(ctx));
    if (pyrit.pyr_features(@ptrCast(ctx)) & api.feature_rt_cores == 0) return error.HardwareRtRequired;
    const geo = try humanoid(init, ctx);
    var skeleton: api.Handle = null;
    var clip: api.Handle = null;
    if (animated) {
        const bone = pyrit.anim_mod.Bone{ .parent = -1, .reserved = 0, .geometry = geo, .rest = pyr.anim.identity, .part = matrix(1, -12, 0, -6), .sway = 0, .reserved2 = 0 };
        try req(pyrit.pyr_skeleton_create(@ptrCast(ctx), @ptrCast(&bone), 1, &skeleton));
        const keys = [_]types.Keyframe{
            .{ .bone = 0, .time = 0, .translation = .{ 0, 0, 0 }, .scale = 1, .rotation = .{ 0, 0, 0, 1 } },
            .{ .bone = 0, .time = 1, .translation = .{ 0, 2, 0 }, .scale = 1, .rotation = .{ 0, 0.149438, 0, 0.988771 } },
            .{ .bone = 0, .time = 2, .translation = .{ 0, 0, 0 }, .scale = 1, .rotation = .{ 0, 0, 0, 1 } },
        };
        try req(pyrit.pyr_clip_create(@ptrCast(ctx), skeleton, &keys, keys.len, 2, types.clip_loop, &clip));
    }
    const side: u32 = @intFromFloat(@ceil(@sqrt(@as(f32, @floatFromInt(count)))));
    const start = ns(false);
    for (0..count) |index| {
        const x = (@as(f32, @floatFromInt(index % side)) - @as(f32, @floatFromInt(side / 2))) * 1.5;
        const z = @as(f32, @floatFromInt(index / side)) * 1.5;
        if (animated) {
            var actor = std.mem.zeroes(pyrit.anim_mod.ActorInfo);
            actor.root = matrix(1.0 / 16.0, x, 0, z);
            actor.clip = clip;
            actor.speed = 1;
            actor.start_time = @as(f32, @floatFromInt(index % 97)) * 0.021;
            var handle: api.Handle = null;
            try req(pyrit.pyr_actor_create(@ptrCast(ctx), skeleton, &actor, &handle));
        } else {
            var handle: api.Handle = null;
            try req(pyrit.pyr_instance_create(@ptrCast(ctx), geo, &handle));
            const m = matrix(1.0 / 16.0, x - 0.75, 0, z - 0.375);
            try req(pyrit.pyr_instance_set_transform(@ptrCast(ctx), handle, &m));
        }
    }
    var lighting: types.Lighting = undefined;
    pyrit.pyr_lighting_default(&lighting);
    lighting.flags |= types.lighting_gi_half;
    lighting.gi_bounces = 2;
    try req(pyrit.pyr_set_lighting(@ptrCast(ctx), &lighting));
    var view: api.Handle = null;
    try req(pyrit.pyr_view_create(@ptrCast(ctx), &view));
    var cam = std.mem.zeroes(types.Camera);
    const eye = [3]f32{ 0, 3, -12 };
    const target = [3]f32{ 0, 1, 16 };
    const up = [3]f32{ 0, 1, 0 };
    pyrit.pyr_camera_look_at(&cam, &eye, &target, &up);
    const rw = w / scale;
    const rh = h / scale;
    pyrit.pyr_camera_perspective(&cam, 0.9, rw, rh, 0.1);
    const n = @as(usize, rw) * rh;
    const output_n = @as(usize, w) * h;
    var targets = std.mem.zeroes(api.Targets);
    const buffers = [_]u64{ try alloc(n * 16), try alloc(n * 8), try alloc(n * 16), try alloc(n * 16), try alloc(n * 16), try alloc(output_n * 4) };
    defer for (buffers) |p| {
        _ = drv.cuMemFree_v2(p);
    };
    targets.hits = buffers[0];
    targets.motion = buffers[1];
    targets.color = buffers[2];
    targets.normal = buffers[3];
    targets.albedo = buffers[4];
    targets.detail_scale = @floatFromInt(scale);
    var post = std.mem.zeroes(api.PostInfo);
    post.output_ldr = buffers[5];
    post.upscaler = if (scale == 1) api.upscaler_none else api.upscaler_taau;
    post.output_width = w;
    post.output_height = h;
    post.denoise_iterations = 2;
    post.clamp_sigma = 1.5;
    post.tonemap = types.tonemap_neutral;
    try req(pyrit.pyr_commit(@ptrCast(ctx), null));
    try req(pyrit.pyr_synchronize(@ptrCast(ctx)));
    var stats: api.Stats = undefined;
    try req(pyrit.pyr_get_stats(@ptrCast(ctx), &stats));
    if (stats.instances != count or stats.geometries != 1) return error.WrongScene;
    std.debug.print("crowd: {d} actors, 1 shared humanoid HashDAG, {d}x{d} output / {d}x{d} internal, CUDA TAAU {any}, GPU animation {any}, setup {d:.1} ms\n", .{ count, w, h, rw, rh, scale > 1, animated, @as(f64, @floatFromInt(ns(false) - start)) / 1e6 });
    const times = try init.gpa.alloc(f64, frames);
    defer init.gpa.free(times);
    var gpu_sum: f64 = 0;
    var submit_sum: f64 = 0;
    var cpu_start: i64 = 0;
    var wall_start: i64 = 0;
    const stream: cuda.CUstream = @ptrCast(pyrit.pyr_cuda_stream(@ptrCast(ctx)));
    var begin: cuda.CUevent = null;
    var end: cuda.CUevent = null;
    try cu(drv.cuEventCreate(&begin, 0));
    try cu(drv.cuEventCreate(&end, 1)); // blocking host wait, timed event
    defer _ = drv.cuEventDestroy_v2(begin);
    defer _ = drv.cuEventDestroy_v2(end);
    for (0..frames + 30) |fi| {
        if (fi == 30) {
            cpu_start = ns(true);
            wall_start = ns(false);
        }
        const t0 = ns(false);
        try cu(drv.cuEventRecord(begin, stream));
        var frame = std.mem.zeroes(api.FrameInfo);
        frame.time = @as(f64, @floatFromInt(fi)) / 165;
        try req(pyrit.pyr_commit(@ptrCast(ctx), &frame));
        pyrit.pyr_jitter_halton(@intCast(fi), &cam.jitter);
        try req(pyrit.pyr_render(@ptrCast(ctx), view, &cam, &targets));
        try req(pyrit.pyr_postprocess(@ptrCast(ctx), view, &targets, &post));
        const submitted = ns(false);
        try cu(drv.cuEventRecord(end, stream));
        try cu(drv.cuEventSynchronize(end));
        var gpu_ms: f32 = 0;
        try cu(drv.cuEventElapsedTime(&gpu_ms, begin, end));
        if (fi >= 30) {
            times[fi - 30] = @as(f64, @floatFromInt(ns(false) - t0)) / 1e6;
            gpu_sum += gpu_ms;
            submit_sum += @as(f64, @floatFromInt(submitted - t0)) / 1e6;
        }
    }
    const wall = @as(f64, @floatFromInt(ns(false) - wall_start)) / 1e6;
    const cpu = @as(f64, @floatFromInt(ns(true) - cpu_start)) / 1e6;
    std.mem.sort(f64, times, {}, std.sort.asc(f64));
    const nf: f64 = @floatFromInt(frames);
    std.debug.print("RESULT actors={d} animated={any} frames={d} fps={d:.2} wall_ms={d:.3} gpu_ms={d:.3} submit_ms={d:.3} p50_ms={d:.3} p95_ms={d:.3} p99_ms={d:.3} cpu_core_percent={d:.1}\n", .{ count, animated, frames, 1000 * nf / wall, wall / nf, gpu_sum / nf, submit_sum / nf, times[times.len / 2], times[@min(times.len - 1, times.len * 95 / 100)], times[@min(times.len - 1, times.len * 99 / 100)], 100 * cpu / wall });
    const pixels = try init.gpa.alloc([4]u8, output_n);
    defer init.gpa.free(pixels);
    try cu(drv.cuMemcpyDtoH_v2(pixels.ptr, buffers[5], output_n * 4));
    var image: std.ArrayList(u8) = .empty;
    defer image.deinit(init.gpa);
    var header: [80]u8 = undefined;
    try image.appendSlice(init.gpa, try std.fmt.bufPrint(&header, "P6\n{d} {d}\n255\n", .{ w, h }));
    for (pixels) |p| try image.appendSlice(init.gpa, p[0..3]);
    try std.Io.Dir.cwd().writeFile(init.io, .{ .sub_path = out, .data = image.items });
}
