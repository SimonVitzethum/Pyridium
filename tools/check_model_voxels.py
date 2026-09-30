"""Windowless CUDA model-surface reference check (50% duty, no Minecraft process).

Usage: python3 tools/check_model_voxels.py <pyridium_kernels.ptx>
"""
import ctypes as c
import sys
import time

cu = c.CDLL("libcuda.so.1")
u32, u64 = c.c_uint32, c.c_uint64


def call(name, *args):
    result = getattr(cu, name)(*args)
    if result:
        raise RuntimeError(f"{name}: CUDA error {result}")


class Face(c.Structure):
    _fields_ = [("texture", u64), ("width", u32), ("height", u32),
                ("color", u32), ("first", u32), ("steps", u32 * 2),
                ("p", (c.c_float * 3) * 3), ("uv", (c.c_float * 2) * 3),
                ("reserved", u32)]


assert c.sizeof(Face) == 96 and Face.uv.offset == 68
allocations = []


def upload(data):
    pointer = u64()
    call("cuMemAlloc_v2", c.byref(pointer), c.c_size_t(c.sizeof(data)))
    allocations.append(pointer.value)
    call("cuMemcpyHtoD_v2", pointer, c.byref(data), c.c_size_t(c.sizeof(data)))
    return pointer.value


def attribute(r, g, b):
    return ((r >> 3) << 11 | (g >> 2) << 5 | b >> 3) << 16 | 4094


call("cuInit", u32(0))
context = c.c_void_p()
call("cuDevicePrimaryCtxRetain", c.byref(context), c.c_int(0))
call("cuCtxSetCurrent", context)
module, kernel = c.c_void_p(), c.c_void_p()
call("cuModuleLoad", c.byref(module), sys.argv[1].encode())
call("cuModuleGetFunction", c.byref(kernel), module, b"pyd_k_model")
try:
    for tint in (0xFFFFFFFF, 0xFF8040FF, 0x00FFFFFF):
        start = time.monotonic()
        # A non-square RGBA texture exercises UV interpolation and row orientation.
        pixels = (c.c_ubyte * 16)(255, 0, 0, 255, 0, 255, 0, 255,
                                  0, 0, 255, 0, 255, 255, 255, 255)
        face = Face(texture=upload(pixels), width=2, height=2, color=tint,
                    first=0, steps=(u32 * 2)(4, 4),
                    p=((c.c_float * 3) * 3)((0, 0, 0), (4, 0, 0), (0, 4, 0)),
                    uv=((c.c_float * 2) * 3)((0, 0), (1, 0), (0, 1)))
        faces = (Face * 1)(face)
        output = (u32 * 64)()
        args = [u64(upload(faces)), u32(1), u64(upload(output)), u32(16), u32(3)]
        params = (c.c_void_p * len(args))(*(c.addressof(arg) for arg in args))
        call("cuLaunchKernel", kernel, u32(1), u32(1), u32(1), u32(256), u32(1), u32(1),
             u32(0), c.c_void_p(), params, c.c_void_p())
        call("cuCtxSynchronize")
        call("cuMemcpyDtoH_v2", c.byref(output), args[2], c.c_size_t(c.sizeof(output)))
        for y in range(4):
            for x in range(4):
                p = ((y // 2) * 2 + x // 2) * 4
                rgba = list(pixels[p:p + 4])
                if rgba[3] * (tint >> 24) < 128 * 255:
                    expected = (0, 0, 0, 0)
                else:
                    r = rgba[0] * ((tint >> 16) & 255) // 255
                    g = rgba[1] * ((tint >> 8) & 255) // 255
                    b = rgba[2] * (tint & 255) // 255
                    expected = (x, y, 0, attribute(r, g, b))
                actual = tuple(output[(y * 4 + x) * 4:(y * 4 + x + 1) * 4])
                assert actual == expected, (hex(tint), x, y, actual, expected)
        print(f"model CUDA reference OK: tint={tint:08x}, 16 surface samples")
        time.sleep(max(0.01, time.monotonic() - start))
finally:
    for pointer in allocations:
        call("cuMemFree_v2", u64(pointer))
    call("cuModuleUnload", module)
    call("cuDevicePrimaryCtxRelease_v2", c.c_int(0))
