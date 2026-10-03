//! OptiX-Modul: Traversierung (Intersection, Closest Hit, Miss) und
//! beliebige Strahlen (pyr_trace). Klein; die Schattierung liegt in
//! rt_render.zig und rt_gi.zig.
//!
//! - Hardware-BVH: Instanzen (IAS) und AABBs der belegten Teilbäume (GAS)
//! - __intersection__dag: verfolgt den Teil-DAG im getroffenen AABB
//! - __closesthit__dag:   schreibt t, Instanz, Attribut, Fläche in die Payload
//! - __raygen__trace:     beliebige Strahlen (pyr_trace)

const std = @import("std");
const pyr = @import("pyrit_device");
const types = pyr.types;
const ox = pyr.optix;
const common = @import("rt_common.zig");
const RtTracer = common.RtTracer;
const params = common.params;

pub const panic = std.debug.no_panic;

export fn __raygen__trace() callconv(.nvptx_kernel) void {
    const prm = params();
    const i = ox.launchIndex()[0];
    const tp = &prm.trace;
    if (i >= tp.count) return;
    const s: *const types.Scene = @ptrFromInt(prm.scene);
    const ray = @as([*]const types.Ray, @ptrFromInt(tp.rays))[i];
    const tracer = RtTracer{ .handle = prm.handle, .flags = prm.flags };
    const h = tracer.trace(s, ray.origin, ray.direction, ray.tmin, ray.tmax, tp.ray_mask, tp.flags);
    if (tp.flags & types.trace_extended != 0) {
        @as([*]types.HitEx, @ptrFromInt(tp.hits))[i] = pyr.scene.extendedHit(s, ray.origin, ray.direction, h);
    } else {
        @as([*]types.Hit, @ptrFromInt(tp.hits))[i] = pyr.scene.toHit(h);
    }
}

/// Wiederholungs-Wavefront: die Strahlen der Liste verfolgen (src/device/replay.zig)
export fn __raygen__slots() callconv(.nvptx_kernel) void {
    const prm = params();
    const rp = &prm.replay;
    const count = @min(@as(*const u32, @ptrFromInt(rp.count)).*, rp.capacity);
    const stride = ox.launchDimensions()[0];
    var i = ox.launchIndex()[0];
    // The queue size remains on the GPU. A bounded launch visits it with a
    // grid-stride loop, avoiding millions of empty raygen invocations in
    // late replay passes without a CPU count readback or synchronization.
    while (i < count) : (i += stride) {
        const k = @as([*]const u32, @ptrFromInt(rp.list))[i];
        const ray = @as([*]const types.ReplayRay, @ptrFromInt(rp.rays))[k];
        var p = [4]u32{ @bitCast(types.flt_max), types.no_hit, 0, @intFromBool(ray.flags & types.trace_skip_transparent != 0) };
        if (prm.handle != 0) common.tracePolicy(prm.handle, ray.o, ray.d, ray.tmin, ray.tmax, ray.mask, ray.flags, &p);
        @as([*]types.ReplayHit, @ptrFromInt(rp.hits))[k] = .{ .t = @bitCast(p[0]), .instance = p[1], .attribute = p[2], .face = p[3] };
        @as([*]u32, @ptrFromInt(rp.state))[k] = 2;
    }
}

export fn __intersection__dag() callconv(.nvptx_kernel) void {
    const g: *const types.RtGeometry = @ptrFromInt(ox.sbtDataPointer());
    const prim = @as([*]const types.RtPrim, @ptrFromInt(g.prims))[ox.primitiveIndex()];
    const want_attribute = params().flags & types.trace_no_attribute == 0;
    const s: *const types.Scene = @ptrFromInt(params().scene);
    const skip: ?*const types.MaterialMask = if (ox.getPayload(3) != 0) &s.transparent_materials else null;
    const inst = &pyr.scene.instances(s)[ox.instanceId()];
    const cut: ?*const types.MaterialMask = if (pyr.scene.anyCutout(s) and pyr.scene.cutoutHere(inst)) &s.cutout_materials else null;
    const h = pyr.rt.traceSubtree(g, prim, ox.objectRayOrigin(), ox.objectRayDirection(), ox.rayTmin(), ox.rayTmax(), want_attribute, skip, cut, s.reserved_tex, s) orelse return;
    const kind = h.face | (if (h.inside) types.hit_inside else 0);
    _ = ox.reportIntersection1(h.t, kind, h.attribute);
}

export fn __closesthit__dag() callconv(.nvptx_kernel) void {
    ox.setPayload(0, @bitCast(ox.rayTmax()));
    ox.setPayload(1, ox.instanceId());
    ox.setPayload(2, ox.attribute0());
    ox.setPayload(3, ox.hitKind());
}

export fn __miss__none() callconv(.nvptx_kernel) void {}
