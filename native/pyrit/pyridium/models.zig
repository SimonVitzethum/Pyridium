//! Cached Minecraft model parts: CUDA voxelization, GPU HashDAG construction,
//! stable Pyrit instances with motion history. No graphics API rendering.
const std = @import("std");
const pyrit = @import("pyrit");
const t = @import("types.zig");
const root = @import("root.zig");
const world = @import("world.zig");
const Budget = @import("test_budget").Budget;
const gpa = std.heap.c_allocator;
const G = &root.G;
const Entry = struct { handle: pyrit.api.Handle, geometry: u64, seen: u64 };
var entries: std.AutoHashMapUnmanaged(u64, Entry) = .empty;
var frame: u64 = 0;
var func: pyrit.cuda.CUfunction = null;

pub fn reset() void {
    // The owning Pyrit context destroys GPU geometry and instance resources.
    entries.deinit(gpa);
    entries = .empty;
    frame = 0;
}

export fn pyd_model_create(faces: [*]const t.ModelFace, face_count: u32, count: u32, log2_size: u32) u64 {
    root.bind();
    const ctx = world.context() orelse return 0;
    if (face_count == 0 or face_count > 6 or count == 0 or count > 1 << 20 or log2_size < 3 or log2_size > 9) return 0;
    for (faces[0..face_count]) |f| {
        if (f.texture == 0 or f.width == 0 or f.height == 0 or f.width > 16384 or f.height > 16384 or
            f.steps[0] == 0 or f.steps[1] == 0 or f.steps[0] > 512 or f.steps[1] > 512 or
            f.first > count or @as(u64, f.steps[0]) * f.steps[1] > count - f.first) return 0;
        for (f.p) |p| for (p) |v| {
            if (!std.math.isFinite(v)) return 0;
        };
        for (f.uv) |uv| for (uv) |v| {
            if (!std.math.isFinite(v)) return 0;
        };
    }
    const start = Budget.now();
    defer {
        _ = world.restTest(start);
    }
    if (func == null and !root.ok(G.drv.cuModuleGetFunction(&func, G.module, "pyd_k_model"), "Modellkernel")) return 0;
    var d_faces: u64 = 0;
    var d_voxels: u64 = 0;
    if (!root.ok(G.drv.cuMemAlloc_v2(&d_faces, face_count * @sizeOf(t.ModelFace)), "Modellflächen")) return 0;
    defer {
        _ = G.drv.cuMemFree_v2(d_faces);
    }
    if (!root.ok(G.drv.cuMemAlloc_v2(&d_voxels, @as(usize, count) * 16), "Modellvoxel")) return 0;
    defer {
        _ = G.drv.cuMemFree_v2(d_voxels);
    }
    if (!root.ok(G.drv.cuMemcpyHtoD_v2(d_faces, faces, face_count * @sizeOf(t.ModelFace)), "Modellflächen laden")) return 0;
    var nfaces = face_count;
    var n = count;
    var size = log2_size;
    var args = [_]?*anyopaque{ @ptrCast(&d_faces), @ptrCast(&nfaces), @ptrCast(&d_voxels), @ptrCast(&n), @ptrCast(&size) };
    if (!root.ok(G.drv.cuLaunchKernel(func, (count + 255) / 256, 1, 1, 256, 1, 1, 0, G.stream, &args, null), "Modellvoxelisierung")) return 0;
    var geometry: pyrit.api.Handle = null;
    if (pyrit.pyr_geometry_build(@ptrCast(ctx), log2_size, @ptrFromInt(d_voxels), count, 0, &geometry) != 0) {
        root.setError("Modell-DAG: {s}", .{pyrit.pyr_error_message()});
        return 0;
    }
    return @intFromPtr(geometry);
}

export fn pyd_models_frame(models: [*]const t.ModelInstance, count: u32) i32 {
    root.bind();
    const ctx = world.context() orelse return -1;
    if (count > 32768) return -1;
    frame += 1;
    for (models[0..count]) |m| {
        if (m.id == 0 or m.geometry == 0) return -1;
        for (m.transform) |v| if (!std.math.isFinite(v)) return -1;
        const slot = entries.getOrPut(gpa, m.id) catch return -1;
        if (!slot.found_existing) {
            var instance: pyrit.api.Handle = null;
            if (pyrit.pyr_instance_create(@ptrCast(ctx), @ptrFromInt(m.geometry), &instance) != 0) {
                entries.removeByPtr(slot.key_ptr);
                return -1;
            }
            slot.value_ptr.* = .{ .handle = instance, .geometry = m.geometry, .seen = frame };
        }
        const e = slot.value_ptr;
        if (e.geometry != m.geometry) {
            if (pyrit.pyr_instance_set_geometry(@ptrCast(ctx), e.handle, @ptrFromInt(m.geometry)) != 0) return -1;
            e.geometry = m.geometry;
        }
        if (e.seen + 1 < frame) _ = pyrit.pyr_instance_reset_history(@ptrCast(ctx), e.handle);
        e.seen = frame;
        if (pyrit.pyr_instance_set_transform(@ptrCast(ctx), e.handle, &m.transform) != 0 or
            pyrit.pyr_instance_set_mask(@ptrCast(ctx), e.handle, 0xff) != 0) return -1;
    }
    var expired: std.ArrayList(u64) = .empty;
    defer expired.deinit(gpa);
    var iter = entries.iterator();
    while (iter.next()) |e| {
        if (e.value_ptr.seen != frame) {
            if (pyrit.pyr_instance_set_mask(@ptrCast(ctx), e.value_ptr.handle, 0) != 0) return -1;
            if (frame - e.value_ptr.seen > 120) {
                if (pyrit.pyr_instance_destroy(@ptrCast(ctx), e.value_ptr.handle) != 0) return -1;
                expired.append(gpa, e.key_ptr.*) catch return -1;
            }
        }
    }
    for (expired.items) |id| _ = entries.remove(id);
    return 0;
}

/// Evict a cached model after its instances have expired. Pyrit enforces ownership/refcounts.
export fn pyd_model_destroy(geometry: u64) i32 {
    root.bind();
    const ctx = world.context() orelse return -1;
    return pyrit.pyr_geometry_destroy(@ptrCast(ctx), @ptrFromInt(geometry));
}
