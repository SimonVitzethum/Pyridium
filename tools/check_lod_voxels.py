"""Windowless CUDA check against an independent surface reference.

Run after compileCuda: python3 tools/check_lod_voxels.py build/cuda/wg_lod_voxels.ptx
Only tiny batches run; a 50% duty cycle separates all launches.
"""
import ctypes as c
import sys
import time

cu = c.CDLL("libcuda.so.1")
u64, i32, u32 = c.c_uint64, c.c_int32, c.c_uint32
allocations = []


def call(name, *args):
    result = getattr(cu, name)(*args)
    if result:
        raise RuntimeError(f"{name}: CUDA error {result}")


def buffer(data):
    pointer = u64()
    call("cuMemAlloc_v2", c.byref(pointer), c.c_size_t(c.sizeof(data)))
    allocations.append(pointer)
    call("cuMemcpyHtoD_v2", pointer, c.byref(data), c.c_size_t(c.sizeof(data)))
    return pointer.value


def height(x, z, kind):
    return 4 + x // 4 + z // 4 if kind == "stairs" else 4


def reference(kind):
    voxels = set()
    for z in range(32):
        for x in range(32):
            for y in range(32):
                h = height(x, z, kind)
                if kind == "water":
                    if y == 3:
                        voxels.add((x, y, z, 0xFFFFFF06))
                    continue
                if y >= h:
                    continue
                neighbors = ((x, y + 1, z), (x, y - 1, z), (x - 1, y, z),
                             (x + 1, y, z), (x, y, z - 1), (x, y, z + 1))
                if all(ny < height(nx, nz, kind) for nx, ny, nz in neighbors):
                    continue
                material = 1 if y + 1 >= h else 2 if h - y <= 4 else 3
                voxels.add((x, y, z, 0xFFFFFF00 | material))
    return voxels


call("cuInit", u32(0))
context = c.c_void_p()
call("cuDevicePrimaryCtxRetain", c.byref(context), i32(0))
call("cuCtxSetCurrent", context)
module, kernel = c.c_void_p(), c.c_void_p()
call("cuModuleLoad", c.byref(module), sys.argv[1].encode())
call("cuModuleGetFunction", c.byref(kernel), module, b"wg_lod_voxels")
global_pointer, global_size = u64(), c.c_size_t()
call("cuModuleGetGlobal_v2", c.byref(global_pointer), c.byref(global_size), module, b"vf_b")
try:
    for kind in ("flat", "stairs", "water"):
        expected = reference(kind)
        density = (c.c_float * (34 * 34 * 48))()
        for z in range(34):
            for x in range(34):
                for yi in range(48):
                    density[yi + (x + z * 34) * 48] = (
                        1 if kind != "water" and yi - 8 < height(x - 1, z - 1, kind) else -1)
        for capacity in (4096, 17):
            began = time.monotonic()
            lp = (i32 * 20)(0, 0, 48, 1, -64, 384, 4 if kind == "water" else -64,
                            capacity, 289, 1, 0xFFFFFF06, 0xFFFFFF03, 0xFFFFFF04, 0xFFFFFF05)
            output = (u32 * (capacity * 4))()
            count = (u32 * 1)()
            bindings = (u64 * 24)(
                buffer(density), buffer(lp), buffer((i32 * 4)(0, 0, 0, 0)),
                buffer(output), buffer(count),
                buffer((i32 * 8)(0xFFFFFF01, 0xFFFFFF02, 0xFFFFFF03, 0, 0, 0, 0, 0)),
                buffer((i32 * (289 * 17))()), buffer((i32 * 31)()),
                buffer((i32 * 2)(0, 0)), buffer((i32 * 1)(-8)))
            call("cuMemcpyHtoD_v2", global_pointer, c.byref(bindings), c.c_size_t(c.sizeof(bindings)))
            push = (i32 * 8)(1)
            args = (c.c_void_p * 1)(c.addressof(push))
            call("cuLaunchKernel", kernel, u32(8), u32(1), u32(1), u32(128), u32(1), u32(1),
                 u32(0), c.c_void_p(), args, c.c_void_p())
            call("cuCtxSynchronize")
            call("cuMemcpyDtoH_v2", c.byref(count), u64(bindings[4]), c.c_size_t(4))
            call("cuMemcpyDtoH_v2", c.byref(output), u64(bindings[3]), c.c_size_t(c.sizeof(output)))
            assert count[0] == len(expected), (kind, capacity, count[0], len(expected))
            actual = [tuple(output[i * 4:i * 4 + 4]) for i in range(min(capacity, count[0]))]
            assert len(set(actual)) == len(actual), "duplicate voxel"
            assert set(actual) <= expected, (kind, "unexpected voxel")
            if capacity >= len(expected):
                assert set(actual) == expected
            for pointer in allocations:
                call("cuMemFree_v2", pointer)
            allocations.clear()
            print(f"{kind}: {count[0]} voxels, capacity {capacity}: OK")
            time.sleep(max(0.01, time.monotonic() - began))
finally:
    call("cuCtxSynchronize")
    for pointer in allocations:
        call("cuMemFree_v2", pointer)
    call("cuModuleUnload", module)
    call("cuDevicePrimaryCtxRelease_v2", i32(0))
