"""Minimaler NBT-Schreiber fuer Strukturvorlagen (gzip) und Hilfen fuer Jigsaw-Vorlagen. Wird von gen_structures.py und testpack benutzt."""
import gzip, struct

def _s(v): b = v.encode(); return struct.pack(">H", len(b)) + b
def named(tag, name, payload): return bytes([tag]) + _s(name) + payload
def i32(v): return struct.pack(">i", v)
def lst(tag, items): return bytes([tag]) + i32(len(items)) + b"".join(items)
def comp(fields): return b"".join(fields) + b"\x00"
def string(v): return _s(v)

class Template:
    """Voxelvorlage: set/fill/box + jig(); save() schreibt Vanilla-kompatibles .nbt"""
    def __init__(self, sx, sy, sz):
        self.size = (sx, sy, sz)
        self.blocks = {}
    def set(self, x, y, z, name, props=None, nbt=None):
        if 0 <= x < self.size[0] and 0 <= y < self.size[1] and 0 <= z < self.size[2]:
            self.blocks[(x, y, z)] = (name, tuple(sorted((props or {}).items())), nbt)
    def fill(self, x0, y0, z0, x1, y1, z1, name, props=None, hollow=False, keep=False):
        for y in range(y0, y1 + 1):
            for z in range(z0, z1 + 1):
                for x in range(x0, x1 + 1):
                    edge = x in (x0, x1) or y in (y0, y1) or z in (z0, z1)
                    if hollow and not edge: continue
                    if keep and (x, y, z) in self.blocks: continue
                    self.set(x, y, z, name, props)
    def jig(self, x, y, z, front, name, target, pool, joint="rollable", final="minecraft:air"):
        nbt = comp([named(8, "id", string("minecraft:jigsaw")), named(8, "name", string(name)), named(8, "target", string(target)),
                    named(8, "pool", string(pool)), named(8, "joint", string(joint)), named(8, "final_state", string(final))])
        self.set(x, y, z, "minecraft:jigsaw", {"orientation": front + "_up"}, nbt)
    def save(self, path):
        pal, idx, bl = [], {}, []
        for (x, y, z), (name, props, nbt) in sorted(self.blocks.items()):
            key = (name, props)
            if key not in idx:
                idx[key] = len(pal); pal.append(key)
            f = [named(3, "state", i32(idx[key])), named(9, "pos", lst(3, [i32(x), i32(y), i32(z)]))]
            if nbt is not None: f.append(named(10, "nbt", nbt))
            bl.append(comp(f))
        palette = lst(10, [comp([named(8, "id", string(n))] + ([named(10, "properties", comp([named(8, k, string(v)) for k, v in pr]))] if pr else [])) for n, pr in pal])
        root = comp([named(3, "DataVersion", i32(5023)), named(9, "size", lst(3, [i32(v) for v in self.size])),
                     named(9, "palette", palette), named(9, "blocks", lst(10, bl)), named(9, "entities", lst(10, []))])
        import os
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "wb") as f:
            f.write(gzip.compress(bytes([10]) + _s("") + root))
