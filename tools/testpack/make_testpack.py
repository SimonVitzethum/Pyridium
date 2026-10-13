#!/usr/bin/env python3
"""Erzeugt das Test-Datapack 'pyridiumtest' (eigenes Jigsaw-Bauwerk + eigener Biom-Tag) in tools/testpack/data/.
Alles Daten: structure, structure_set, template_pool, Biom-Tag und zwei NBT-Vorlagen; keine Codeaenderung noetig, damit es im LOD-Strukturlayer
und im Vollchunk-Haken erscheint. Aufruf: python3 tools/testpack/make_testpack.py   (Overlay zur Laufzeit: -Dpyridium.extraData=tools/testpack)"""
import gzip, json, os, struct

root = os.path.join(os.path.dirname(os.path.abspath(__file__)), "data", "pyridiumtest")

def w(path, text):
    p = os.path.join(root, path)
    os.makedirs(os.path.dirname(p), exist_ok=True)
    with open(p, "w") as f:
        f.write(text)

# --- minimaler NBT-Schreiber -------------------------------------------------------------
def s(v): b = v.encode(); return struct.pack(">H", len(b)) + b
def named(tag, name, payload): return bytes([tag]) + s(name) + payload
def i32(v): return struct.pack(">i", v)
def lst(tag, items): return bytes([tag]) + i32(len(items)) + b"".join(items)
def comp(fields): return b"".join(fields) + b"\x00"
def string(v): return s(v)

def template(size, blocks, palette):
    """blocks: {(x,y,z): (paletteIndex, nbtcompound|None)}"""
    pal = lst(10, [comp([named(8, "id", string(n))] + ([named(10, "properties", comp([named(8, k, string(v)) for k, v in pr.items()]))] if pr else [])) for n, pr in palette])
    bl = []
    for (x, y, z), (idx, nbt) in sorted(blocks.items()):
        f = [named(3, "state", i32(idx)), named(9, "pos", lst(3, [i32(x), i32(y), i32(z)]))]
        if nbt is not None: f.append(named(10, "nbt", nbt))
        bl.append(comp(f))
    root_c = comp([named(3, "DataVersion", i32(5023)), named(9, "size", lst(3, [i32(v) for v in size])),
                   named(9, "palette", pal), named(9, "blocks", lst(10, bl)), named(9, "entities", lst(10, []))])
    return gzip.compress(bytes([10]) + s("") + root_c)

def jigsaw(name, target, pool):
    return comp([named(8, "id", string("minecraft:jigsaw")), named(8, "name", string(name)), named(8, "target", string(target)),
                 named(8, "pool", string(pool)), named(8, "joint", string("rollable")), named(8, "final_state", string("minecraft:air"))])

PAL = [("minecraft:air", None), ("minecraft:oak_planks", None), ("minecraft:oak_log", {"axis": "y"}), ("minecraft:cobblestone", None),
       ("minecraft:jigsaw", {"orientation": "east_up"}), ("minecraft:jigsaw", {"orientation": "west_up"}), ("minecraft:glass", None),
       ("minecraft:stone_bricks", None)]

def hut():
    b = {}
    for x in range(5):
        for z in range(5):
            b[(x, 0, z)] = (3, None)                                      # Fundament
            b[(x, 4, z)] = (1, None)                                      # Dach
            for y in (1, 2, 3):
                edge = x in (0, 4) or z in (0, 4)
                b[(x, y, z)] = ((2 if (x in (0, 4) and z in (0, 4)) else 1, None) if edge else (0, None))
    b[(2, 1, 0)] = (0, None); b[(2, 2, 0)] = (0, None)                   # Tuer
    b[(0, 2, 2)] = (6, None)                                              # Fenster
    b[(4, 1, 2)] = (4, jigsaw("pyridiumtest:hut_out", "pyridiumtest:tower_in", "pyridiumtest:tower_pool"))
    return template((5, 5, 5), b, PAL)

def tower():
    b = {}
    for x in range(3):
        for z in range(3):
            b[(x, 0, z)] = (3, None)
            for y in range(1, 9):
                edge = not (x == 1 and z == 1)
                b[(x, y, z)] = (7, None) if edge else (0, None)
            b[(x, 9, z)] = (3, None)
    b[(0, 1, 1)] = (5, jigsaw("pyridiumtest:tower_in", "pyridiumtest:hut_out", "minecraft:empty"))
    return template((3, 10, 3), b, PAL)

def write_bin(path, data):
    p = os.path.join(root, path)
    os.makedirs(os.path.dirname(p), exist_ok=True)
    with open(p, "wb") as f:
        f.write(data)

write_bin("structure/hut.nbt", hut())
write_bin("structure/tower.nbt", tower())
w("worldgen/template_pool/start.json", json.dumps({"fallback": "minecraft:empty", "elements": [
    {"weight": 1, "element": {"element_type": "minecraft:single_pool_element", "location": "pyridiumtest:hut", "projection": "rigid", "processors": "minecraft:empty"}}]}, indent=1))
w("worldgen/template_pool/tower_pool.json", json.dumps({"fallback": "minecraft:empty", "elements": [
    {"weight": 1, "element": {"element_type": "minecraft:single_pool_element", "location": "pyridiumtest:tower", "projection": "rigid", "processors": "minecraft:empty"}}]}, indent=1))
w("worldgen/structure/test_outpost.json", json.dumps({"type": "minecraft:jigsaw", "biomes": "#pyridiumtest:has_test_outpost", "step": "surface_structures",
    "spawn_overrides": {}, "terrain_adaptation": "beard_thin", "start_pool": "pyridiumtest:start", "size": 2, "start_height": {"absolute": 0},
    "project_start_to_heightmap": "WORLD_SURFACE_WG", "max_distance_from_center": 40, "use_expansion_hack": False}, indent=1))
w("worldgen/structure_set/test_outposts.json", json.dumps({"structures": [{"structure": "pyridiumtest:test_outpost", "weight": 1}],
    "placement": {"type": "minecraft:random_spread", "spacing": 6, "separation": 3, "salt": 20260101}}, indent=1))
w("tags/worldgen/biome/has_test_outpost.json", json.dumps({"values": ["minecraft:plains", "minecraft:forest", "minecraft:birch_forest", "minecraft:bamboo_jungle", "minecraft:jungle",
    "minecraft:sunflower_plains", "minecraft:taiga", "minecraft:meadow", "minecraft:savanna", "minecraft:dark_forest"]}, indent=1))
# Test: Struktur mit erfundenem Typ muss geloggt und uebersprungen werden (kein Absturz)
w("worldgen/structure/unknown_type.json", json.dumps({"type": "pyridiumtest:made_up_type", "biomes": "#pyridiumtest:has_test_outpost", "step": "surface_structures",
    "spawn_overrides": {}, "terrain_adaptation": "none"}, indent=1))
w("worldgen/structure_set/unknown_sets.json", json.dumps({"structures": [{"structure": "pyridiumtest:unknown_type", "weight": 1}],
    "placement": {"type": "minecraft:random_spread", "spacing": 7, "separation": 3, "salt": 20260102}}, indent=1))
print("Test-Datapack geschrieben:", root)
