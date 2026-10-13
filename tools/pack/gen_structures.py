#!/usr/bin/env python3
"""Erzeugt die mitgelieferten Strukturen Bergwerk (2 Arten), Festung (Stronghold) und Ozeanmonument als Jigsaw-Netze in
src/main/resources/data/pyridium/ (Vorlagen mit Jigsaw-Bloecken, Pools, Strukturersetzungen). Vanilla-aehnlich, nicht identisch.
Aufruf: python3 tools/pack/gen_structures.py   (Ausgabe wird committet; zur Laufzeit liest nur der generische Jigsaw-Pfad)"""
import json, os, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from nbt import Template

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "src", "main", "resources", "data", "pyridium")

def wj(rel, obj):
    p = os.path.join(ROOT, rel)
    os.makedirs(os.path.dirname(p), exist_ok=True)
    with open(p, "w") as f:
        json.dump(obj, f, indent=1)

def element(loc, weight):
    return {"weight": weight, "element": {"element_type": "minecraft:single_pool_element", "location": loc, "projection": "rigid", "processors": "minecraft:empty"}}

def pool(rel, elems, fallback="minecraft:empty"):
    wj("worldgen/template_pool/" + rel + ".json", {"fallback": fallback, "elements": [element(l, w) for l, w in elems]})

def structure(name, start_pool, size, start_height, project, dist, extra=None):
    o = {"type": "minecraft:jigsaw", "start_pool": start_pool, "size": size, "start_height": start_height,
         "max_distance_from_center": dist, "use_expansion_hack": False}
    if project: o["project_start_to_heightmap"] = project
    if extra: o.update(extra)
    wj("worldgen/structure_override/minecraft/" + name + ".json", o)

def tpl(rel, t): t.save(os.path.join(ROOT, "structure", rel + ".nbt"))

# ------------------------------------------------------------------------------------------------ Bergwerk
def mineshaft(kind, wood, fence, log):
    P = "pyridium:mineshaft/" + kind
    T = lambda *a: Template(*a)
    def posts(t, xs, y1, y2, z0, z1):
        for x in xs:
            for z in (z0, z1):
                for y in range(y1, y2 + 1): t.set(x, y, z, fence)
    # Gang entlang x: 5 x 4 x 3
    c = T(5, 4, 3)
    c.fill(0, 0, 0, 4, 0, 2, wood)
    for x in (0, 4): posts(c, [x], 1, 2, 0, 2); [c.set(x, 3, z, wood) for z in range(3)]
    for x in range(1, 4): c.set(x, 1, 1, "minecraft:rail", {"shape": "east_west"})
    c.jig(0, 1, 1, "west", P + "/shaft", P + "/shaft", P + "_pool"); c.jig(4, 1, 1, "east", P + "/shaft", P + "/shaft", P + "_pool")
    tpl("mineshaft/%s_corridor" % kind, c)
    # Kreuzung 5x4x5
    x = T(5, 4, 5)
    x.fill(0, 0, 0, 4, 0, 4, wood)
    for px, pz in ((0, 0), (4, 0), (0, 4), (4, 4)):
        for y in (1, 2): x.set(px, y, pz, log)
    x.fill(0, 3, 0, 4, 3, 4, wood, hollow=True)
    x.jig(0, 1, 2, "west", P + "/shaft", P + "/shaft", P + "_pool"); x.jig(4, 1, 2, "east", P + "/shaft", P + "/shaft", P + "_pool")
    x.jig(2, 1, 0, "north", P + "/shaft", P + "/shaft", P + "_pool"); x.jig(2, 1, 4, "south", P + "/shaft", P + "/shaft", P + "_pool")
    tpl("mineshaft/%s_crossing" % kind, x)
    # Raum 9x5x9
    r = T(9, 5, 9)
    r.fill(0, 0, 0, 8, 0, 8, wood)
    for px, pz in ((1, 1), (7, 1), (1, 7), (7, 7), (4, 1), (4, 7), (1, 4), (7, 4)):
        for y in (1, 2, 3): r.set(px, y, pz, fence)
    r.fill(1, 4, 1, 7, 4, 7, wood, hollow=True)
    r.set(4, 1, 4, "minecraft:chest", {"facing": "north", "type": "single"})
    r.jig(0, 1, 4, "west", P + "/shaft", P + "/shaft", P + "_pool"); r.jig(8, 1, 4, "east", P + "/shaft", P + "/shaft", P + "_pool")
    r.jig(4, 1, 0, "north", P + "/shaft", P + "/shaft", P + "_pool"); r.jig(4, 1, 8, "south", P + "/shaft", P + "/shaft", P + "_pool")
    tpl("mineshaft/%s_room" % kind, r)
    pool("mineshaft/%s_start" % kind, [(P + "_room", 2), (P + "_crossing", 1)])
    pool("mineshaft/%s_pool" % kind, [(P + "_corridor", 8), (P + "_crossing", 3), (P + "_room", 1)])

# ------------------------------------------------------------------------------------------------ Festung
def stronghold():
    P = "pyridium:stronghold"
    SB, MB, CR = "minecraft:stone_bricks", "minecraft:mossy_stone_bricks", "minecraft:cracked_stone_bricks"
    def hall(sx, sy, sz):
        t = Template(sx, sy, sz)
        t.fill(0, 0, 0, sx - 1, sy - 1, sz - 1, SB, hollow=True)
        t.fill(1, 1, 1, sx - 2, sy - 2, sz - 2, "minecraft:air")
        for i in range(0, sx * sz // 6):                       # Moos/Risse verstreut (zaehlerfreie Verteilung, deterministisch)
            x, z = (i * 7 + 3) % sx, (i * 11 + 5) % sz
            t.set(x, 0, z, MB if i % 2 else CR)
        return t
    def conn(t, x, y, z, side):
        t.jig(x, y, z, side, P + "/hall", P + "/hall", P + "/pool")
    # Gang 5x5x5 (x-Achse)
    c = hall(5, 5, 5); conn(c, 0, 1, 2, "west"); conn(c, 4, 1, 2, "east"); c.set(2, 3, 2, "minecraft:wall_torch", {"facing": "south"}); tpl("stronghold/corridor", c)
    # Kurve (West + Sued)
    k = hall(5, 5, 5); conn(k, 0, 1, 2, "west"); conn(k, 2, 1, 4, "south"); tpl("stronghold/corner", k)
    # Kreuzung
    x = hall(7, 5, 7)
    for d, (px, pz) in {"west": (0, 3), "east": (6, 3), "north": (3, 0), "south": (3, 6)}.items(): conn(x, px, 1, pz, d)
    tpl("stronghold/crossing", x)
    # Treppe: tiefer Anschluss im Osten
    s = hall(5, 9, 5)
    for i in range(4):
        s.fill(1 + i // 1 if False else 1, 0, 0, 3, 0, 0, SB)
    s.fill(1, 1, 1, 3, 7, 3, "minecraft:air")
    for i in range(4):
        for z in (1, 2, 3):
            s.set(1 + i, 5 - i, z, "minecraft:stone_brick_stairs", {"facing": "east", "half": "bottom", "shape": "straight"})
            for yy in range(1, 5 - i): s.set(1 + i, yy, z, SB)
    conn(s, 0, 6, 2, "west"); conn(s, 4, 1, 2, "east")
    tpl("stronghold/stairs", s)
    # Raeume
    r = hall(11, 6, 11)
    for px, pz in ((2, 2), (8, 2), (2, 8), (8, 8)):
        for y in (1, 2, 3, 4): r.set(px, y, pz, SB)
    for d, (px, pz) in {"west": (0, 5), "east": (10, 5), "north": (5, 0), "south": (5, 10)}.items(): conn(r, px, 1, pz, d)
    tpl("stronghold/room", r)
    lib = hall(14, 8, 10)
    for xx in range(2, 12):
        for yy in (1, 2, 3):
            lib.set(xx, yy, 1, "minecraft:bookshelf"); lib.set(xx, yy, 8, "minecraft:bookshelf")
    lib.fill(1, 6, 1, 12, 6, 8, "minecraft:oak_planks", hollow=True)
    conn(lib, 0, 1, 5, "west"); conn(lib, 13, 1, 5, "east")
    tpl("stronghold/library", lib)
    pr = hall(9, 6, 9)
    for xx in (2, 6):
        for yy in (1, 2): pr.set(xx, yy, 4, "minecraft:iron_bars")
    conn(pr, 0, 1, 4, "west"); conn(pr, 8, 1, 4, "east"); tpl("stronghold/prison", pr)
    # Portalraum 11x8x16
    po = hall(11, 8, 16)
    po.fill(3, 1, 8, 7, 1, 12, "minecraft:lava")
    for xx in range(4, 7):
        po.set(xx, 2, 7, "minecraft:end_portal_frame", {"eye": "true", "facing": "south"})
        po.set(xx, 2, 13, "minecraft:end_portal_frame", {"eye": "false", "facing": "north"})
    for zz in range(8, 13):
        po.set(3, 2, zz, "minecraft:end_portal_frame", {"eye": "true", "facing": "east"})
        po.set(7, 2, zz, "minecraft:end_portal_frame", {"eye": "true", "facing": "west"})
    po.set(5, 1, 3, "minecraft:spawner")
    po.jig(5, 1, 0, "north", P + "/hall", P + "/hall", "minecraft:empty")
    tpl("stronghold/portal_room", po)
    # Start: Raum mit je einem Anschluss in die Haupt- und einen Portalraum-Pool
    st = hall(11, 7, 11)
    st.jig(0, 1, 5, "west", P + "/hall", P + "/hall", P + "/pool"); st.jig(10, 1, 5, "east", P + "/hall", P + "/hall", P + "/pool")
    st.jig(5, 1, 0, "north", P + "/hall", P + "/hall", P + "/pool")
    st.jig(5, 1, 10, "south", P + "/hall", P + "/hall", P + "/portal_pool")
    tpl("stronghold/start", st)
    pool("stronghold/start", [(P + "/start", 1)])
    pool("stronghold/portal_pool", [(P + "/portal_room", 1)])
    pool("stronghold/pool", [(P + "/corridor", 9), (P + "/corner", 4), (P + "/crossing", 3), (P + "/stairs", 3), (P + "/room", 2), (P + "/library", 1), (P + "/prison", 1)])

# ------------------------------------------------------------------------------------------------ Monument
def monument():
    P = "pyridium:monument"
    PB, DP, SL, WA = "minecraft:prismarine_bricks", "minecraft:dark_prismarine", "minecraft:sea_lantern", "minecraft:water"
    core = Template(24, 16, 24)
    core.fill(0, 0, 0, 23, 0, 23, PB)
    core.fill(0, 0, 0, 23, 15, 23, PB, hollow=True)
    core.fill(1, 1, 1, 22, 14, 22, WA)
    for px, pz in ((3, 3), (20, 3), (3, 20), (20, 20), (11, 11), (12, 12), (11, 12), (12, 11)):
        for y in range(1, 15): core.set(px, y, pz, DP)
    for px, pz in ((1, 1), (22, 1), (1, 22), (22, 22)): core.set(px, 14, pz, SL); core.set(px, 1, pz, SL)
    core.fill(8, 8, 8, 15, 8, 15, PB)          # Zwischenboden
    core.fill(10, 9, 10, 13, 12, 13, "minecraft:gold_block", hollow=True)   # Schatzkammer
    core.set(11, 10, 11, "minecraft:gold_block")
    for z in (9, 10, 11, 12): core.set(0, 1, z, "minecraft:air"); core.set(0, 2, z, "minecraft:air")
    core.jig(0, 3, 11, "west", P + "/wing", P + "/wing", P + "/wing_pool"); core.jig(23, 3, 11, "east", P + "/wing", P + "/wing", P + "/wing_pool")
    core.jig(11, 3, 0, "north", P + "/wing", P + "/wing", P + "/wing_pool"); core.jig(11, 3, 23, "south", P + "/wing", P + "/wing", P + "/wing_pool")
    tpl("monument/core", core)
    wing = Template(14, 10, 12)
    wing.fill(0, 0, 0, 13, 0, 11, PB); wing.fill(0, 0, 0, 13, 9, 11, PB, hollow=True)
    wing.fill(1, 1, 1, 12, 8, 10, WA)
    for xx in (3, 10):
        for y in range(1, 9): wing.set(xx, y, 5, DP)
    wing.set(6, 8, 5, SL); wing.set(7, 8, 5, SL)
    wing.jig(13, 3, 5, "east", P + "/wing", P + "/wing", "minecraft:empty")
    tpl("monument/wing", wing)
    pool("monument/start", [(P + "/core", 1)])
    pool("monument/wing_pool", [(P + "/wing", 1)])

mineshaft("normal", "minecraft:oak_planks", "minecraft:oak_fence", "minecraft:oak_log")
mineshaft("mesa", "minecraft:dark_oak_planks", "minecraft:dark_oak_fence", "minecraft:dark_oak_log")
stronghold()
monument()
HG = {"type": "minecraft:uniform", "min_inclusive": {"absolute": -40}, "max_inclusive": {"absolute": 40}}
structure("mineshaft", "pyridium:mineshaft/normal_start", 6, HG, None, {"horizontal": 80, "vertical": 40})
structure("mineshaft_mesa", "pyridium:mineshaft/mesa_start", 6, HG, None, {"horizontal": 80, "vertical": 40})
structure("stronghold", "pyridium:stronghold/start", 7, {"type": "minecraft:uniform", "min_inclusive": {"absolute": -48}, "max_inclusive": {"absolute": 24}}, None, {"horizontal": 80, "vertical": 60})
structure("monument", "pyridium:monument/start", 2, {"absolute": 0}, "OCEAN_FLOOR_WG", 48)
print("Bergwerk, Festung, Monument geschrieben")
