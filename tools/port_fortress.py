#!/usr/bin/env python3
"""Entwicklungswerkzeug (nicht Teil der Laufzeit): uebertraegt die Bauanweisungen (postProcess) der Nether-Festungs-Teile in
Pyridiums eigene Klasse FortressPieces.java (Vorlagen-Bauer, siehe VirtualPiece). Erzeugt Java-Quelltext.

Aufruf: python3 tools/port_fortress.py <ordner mit dekompilierten structures/*.java> > .../FortressPieces.java
"""
import re
import sys

src = sys.argv[1]
text = open(src + "/NetherFortressPieces.java", encoding="utf-8").read()

PIECES = [
    # (Klasse, Breite, Hoehe, Tiefe)
    ("BridgeCrossing", 19, 10, 19),
    ("BridgeEndFiller", 5, 10, 8),
    ("BridgeStraight", 5, 10, 19),
    ("CastleCorridorStairsPiece", 5, 14, 10),
    ("CastleCorridorTBalconyPiece", 9, 7, 9),
    ("CastleEntrance", 13, 14, 13),
    ("CastleSmallCorridorCrossingPiece", 5, 7, 5),
    ("CastleSmallCorridorLeftTurnPiece", 5, 7, 5),
    ("CastleSmallCorridorPiece", 5, 7, 5),
    ("CastleSmallCorridorRightTurnPiece", 5, 7, 5),
    ("CastleStalkRoom", 13, 14, 13),
    ("MonsterThrone", 7, 8, 9),
    ("RoomCrossing", 7, 9, 7),
    ("StairsRoom", 7, 11, 7),
]


def class_range(name):
    m = re.search(r"public static class " + name + r" extends", text)
    start = m.start()
    nxt = re.search(r"\n   (public static class|private static class|private abstract static class)", text[m.end():])
    end = m.end() + nxt.start() if nxt else len(text)
    return text[start:end]


def method_body(chunk, marker):
    i = chunk.index(marker)
    j = chunk.index("{", i)
    d = 0
    k = j
    while True:
        c = chunk[k]
        if c == "{":
            d += 1
        elif c == "}":
            d -= 1
            if d == 0:
                break
        k += 1
    return chunk[j + 1:k]


def fix(code):
    code = code.replace("final WorldGenLevel level", "final Object level").replace("final BoundingBox chunkBB", "final Object chunkBB")
    code = re.sub(r"BuiltInLootTables\.[A-Z_]+", "null", code)
    code = code.replace("chunkBB.isInside(", "isInsideStub(")
    code = re.sub(r"if \(isInsideStub\(pos\)\) \{\s*level\.scheduleTick\(pos, Fluids\.LAVA, 0\);\s*\}", "", code)
    # Spawner: Block ohne Block-Entity
    code = re.sub(r"if \(!this\.hasPlacedSpawner\) \{.*?\n         \}\n", "this.placeBlock(level, Blocks.SPAWNER.defaultBlockState(), 3, 5, 5, chunkBB);\n", code, flags=re.S)
    return code


out = []
out.append("// GENERIERT von tools/port_fortress.py aus den Bauanweisungen der Nether-Festung; per Hand pflegbar.\n")
out.append("package simon.pyridium.worldgen.structures;\n\n")
out.append("import net.minecraft.core.BlockPos;\nimport net.minecraft.core.Direction;\nimport net.minecraft.util.RandomSource;\n"
           "import net.minecraft.world.level.block.Blocks;\nimport net.minecraft.world.level.block.FenceBlock;\nimport net.minecraft.world.level.block.StairBlock;\n"
           "import net.minecraft.world.level.block.state.BlockState;\nimport net.minecraft.world.level.block.state.properties.StairsShape;\n\n")
out.append("/** Vorlagen-Bauer der Nether-Festungs-Teile (jeweils im Teil-Koordinatensystem, Orientierung NORTH). */\nfinal class FortressPieces {\n    private FortressPieces() {\n    }\n\n")
names = []
for cls, w, h, d in PIECES:
    chunk = class_range(cls)
    body = fix(method_body(chunk, "public void postProcess("))
    out.append("    static final class %s extends VirtualPiece {\n" % cls)
    out.append("        %s(RandomSource random) {\n            super(random, %d, %d, %d);\n        }\n\n" % (cls, w, h, d))
    out.append("        @Override\n        void build() {\n            final Object level = null, chunkBB = null;\n")
    if cls == "BridgeEndFiller":
        out.append("            final RandomSource selfRandom = RandomSource.create(7L);\n")
    out.append(body.replace("RandomSource selfRandom = RandomSource.createThreadLocalInstance(this.selfSeed);", ""))
    out.append("        }\n    }\n\n")
    names.append(cls)
out.append("}\n")
print("".join(out))
