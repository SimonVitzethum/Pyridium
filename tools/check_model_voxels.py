"""Windowless CUDA model-surface reference check (50% duty, no Minecraft process).

Usage: python3 tools/check_model_voxels.py <pyridium_kernels.ptx>
"""
import ctypes as c
import math
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
                ("first", u32), ("steps", u32 * 2), ("reserved", u32),
                ("p", (c.c_float * 3) * 4), ("uv", (c.c_float * 2) * 4),
                ("color", u32 * 4)]


assert c.sizeof(Face) == 128 and Face.uv.offset == 80 and Face.color.offset == 112
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
    cases = [(f"tint={tint:08x}", [tint] * 4, True, False, 1)
             for tint in (0xFFFFFFFF, 0xFF8040FF, 0x00FFFFFF)]
    cases += [("skewed quad / corner colors", [0xFFFF0000, 0xFF00FF00, 0xFF0000FF, 0xFFFFFFFF], True, True, 1),
              ("untextured / corner colors", [0xFFFF0000, 0xFF00FF00, 0xFF0000FF, 0xFFFFFFFF], False, True, 1),
              ("257 contiguous face ranges", [0xFF8040FF] * 4, True, True, 257)]
    for label, colors, textured, skewed, nfaces in cases:
        start = time.monotonic()
        # Four distinct texels exercise UV interpolation, alpha and row orientation.
        pixels = (c.c_ubyte * 16)(255, 0, 0, 255, 0, 255, 0, 255,
                                  0, 0, 255, 0, 255, 255, 255, 255)
        texture = upload(pixels) if textured else 0
        faces = (Face * nfaces)()
        expected = []
        for fi in range(nfaces):
            nu, nv = (4, 4) if nfaces == 1 else (2 + fi % 4, 3 + fi % 3)
            face = Face(texture=texture, width=2, height=2,
                        color=(u32 * 4)(*colors), first=len(expected), steps=(u32 * 2)(nu, nv),
                        p=((c.c_float * 3) * 4)((0, 0, 0), (4, 0, 0),
                             (6, 3, 2) if skewed else (4, 4, 0), (0, 4, 0)),
                        uv=((c.c_float * 2) * 4)((0, 0), (1, 0),
                             (0.75, 0.875) if skewed else (1, 1), (0, 1)))
            faces[fi] = face
            for y in range(nv):
                for x in range(nu):
                    u, v = (x + 0.5) / nu, (y + 0.5) / nv
                    # Independent CPU triangle interpolation along diagonal 0->2.
                    if u >= v:
                        corners, weights = (0, 1, 2), (1 - u, u - v, v)
                    else:
                        corners, weights = (0, 2, 3), (1 - v, u, v - u)
                    pos = [sum(face.p[k][a] * w for k, w in zip(corners, weights)) for a in range(3)]
                    uv = [sum(face.uv[k][a] * w for k, w in zip(corners, weights)) for a in range(2)]
                    channels = [math.floor(sum(((colors[k] >> (a * 8)) & 255) * w
                                              for k, w in zip(corners, weights)) + 0.5) for a in range(4)]
                    px, py = [max(0, min(1, math.floor(t * 2))) for t in uv]
                    p = (py * 2 + px) * 4
                    rgba = list(pixels[p:p + 4]) if textured else [255] * 4
                    if rgba[3] * channels[3] < 128 * 255:
                        expected.append((0, 0, 0, 0))
                    else:
                        r, g, b = [rgba[a] * channels[2 - a] // 255 for a in range(3)]
                        expected.append(tuple(max(0, min(7, math.floor(t - 0.0001))) for t in pos) + (attribute(r, g, b),))
        count = len(expected)
        output = (u32 * (count * 4))()
        args = [u64(upload(faces)), u32(nfaces), u64(upload(output)), u32(count), u32(3)]
        params = (c.c_void_p * len(args))(*(c.addressof(arg) for arg in args))
        call("cuLaunchKernel", kernel, u32((count + 255) // 256), u32(1), u32(1), u32(256), u32(1), u32(1),
             u32(0), c.c_void_p(), params, c.c_void_p())
        call("cuCtxSynchronize")
        call("cuMemcpyDtoH_v2", c.byref(output), args[2], c.c_size_t(c.sizeof(output)))
        for i, reference in enumerate(expected):
            actual = tuple(output[i * 4:(i + 1) * 4])
            assert actual == reference, (label, i, actual, reference)
        print(f"model CUDA reference OK: {label}, {count} surface samples")
        time.sleep(max(0.01, time.monotonic() - start))
finally:
    for pointer in allocations:
        call("cuMemFree_v2", u64(pointer))
    call("cuModuleUnload", module)
    call("cuDevicePrimaryCtxRelease_v2", c.c_int(0))
