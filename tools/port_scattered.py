#!/usr/bin/env python3
"""Entwicklungswerkzeug (nicht Teil der Laufzeit): uebertraegt die Bauanweisungen der drei Wüsten-/Dschungel-/
Sumpf-Strukturen (Vanilla-Algorithmus: generateBox/placeBlock-Folgen) in Pyridiums eigene Klasse
ScatteredPieces.java. Erzeugt Java-Quelltext, der im Repository liegt und von Hand nachgepflegt werden darf.

Aufruf: python3 tools/port_scattered.py <ordner mit dekompilierten structures/*.java> > .../ScatteredPieces.java
Die Teile werden beim Laden einmal zu Vorlagen gebacken (ScatteredBake); zur Laufzeit rechnet nur CUDA.
"""
import re
import sys

src = sys.argv[1]


def body(path, start_marker):
    text = open(path, encoding="utf-8").read()
    i = text.index(start_marker)
    j = text.index("{", i)
    depth = 0
    k = j
    while True:
        c = text[k]
        if c == "{":
            depth += 1
        elif c == "}":
            depth -= 1
            if depth == 0:
                break
        k += 1
    return text[j + 1:k]


def helpers(path, names):
    text = open(path, encoding="utf-8").read()
    out = []
    for n in names:
        m = re.search(r"\n   (private|protected|public)[^\n]*\b" + n + r"\(", text)
        if not m:
            continue
        i = m.start()
        j = text.index("{", i)
        depth = 0
        k = j
        while True:
            c = text[k]
            if c == "{":
                depth += 1
            elif c == "}":
                depth -= 1
                if depth == 0:
                    break
            k += 1
        out.append(text[i:k + 1])
    return out


def fix(code):
    code = code.replace("final WorldGenLevel level", "final Object level").replace("final BoundingBox chunkBB", "final Object chunkBB")
    code = code.replace("final ServerLevelAccessor level", "final Object level")
    code = re.sub(r"this\.updateHeightPositionToLowestGroundHeight\(level, -random\.nextInt\(3\)\)", "true", code)
    code = re.sub(r"this\.updateAverageGroundHeight\(level, chunkBB, 0\)", "true", code)
    code = re.sub(r"BuiltInLootTables\.[A-Z_]+", "null", code)
    code = code.replace("level.getRandom()", "random")
    code = code.replace("RandomSource.createThreadLocalInstance(level.getSeed()).forkPositional().at(this.getWorldPos(x0, y0, z0))", "random")
    code = code.replace("RandomSource random = random;", "")
    return code


def strip_entities(code):
    # Witch/Katze: Entitaeten erzeugen wir nicht
    code = re.sub(r"\n         if \(!this\.spawnedWitch\) \{.*?\n         \}\n\n         this\.spawnCat\(level, chunkBB\);", "", code, flags=re.S)
    return code


out = []
out.append("// GENERIERT von tools/port_scattered.py aus den Bauanweisungen der drei Strukturen; per Hand pflegbar.\n")
out.append("package simon.pyridium.worldgen.structures;\n\n")
out.append("import net.minecraft.core.Direction;\nimport net.minecraft.util.RandomSource;\nimport net.minecraft.world.level.block.Blocks;\n"
           "import net.minecraft.world.level.block.StairBlock;\nimport net.minecraft.world.level.block.state.BlockState;\n"
           "import net.minecraft.world.level.block.state.properties.StairsShape;\nimport net.minecraft.world.level.block.RedstoneWireBlock;\n"
           "import net.minecraft.world.level.block.TripWireBlock;\nimport net.minecraft.world.level.block.TripWireHookBlock;\n"
           "import net.minecraft.world.level.block.LeverBlock;\nimport net.minecraft.world.level.block.VineBlock;\nimport net.minecraft.world.level.block.RepeaterBlock;\n"
           "import net.minecraft.world.level.block.piston.PistonBaseBlock;\nimport net.minecraft.world.level.block.state.properties.RedstoneSide;\nimport net.minecraft.world.level.block.state.properties.AttachFace;\n"
           "import net.minecraft.world.level.block.state.properties.BlockStateProperties;\nimport net.minecraft.core.BlockPos;\nimport net.minecraft.world.level.block.Rotation;\n\n")
out.append("/** Vorlagen-Bauer der drei Streu-Strukturen (Wuestenpyramide, Dschungeltempel, Sumpfhuette). */\nfinal class ScatteredPieces {\n    private ScatteredPieces() {\n    }\n\n")

for cls, path, w, h, d, extra in (
    ("DesertPyramid", src + "/DesertPyramidPiece.java", 21, 15, 21, ["addCellar", "addCellarStairs", "addCellarRoom", "placeSand", "placeSandBox", "placeCollapsedRoofPiece", "placeCollapsedRoof"]),
    ("JungleTemple", src + "/JungleTemplePiece.java", 12, 10, 15, []),
    ("SwampHut", src + "/SwampHutPiece.java", 7, 7, 9, []),
):
    code = body(path, "public void postProcess(")
    code = fix(strip_entities(code))
    out.append("    static final class %s extends VirtualPiece {\n" % cls)
    out.append("        %s(RandomSource random) {\n            super(random, %d, %d, %d);\n        }\n\n" % (cls, w, h, d))
    out.append("        @Override\n        void build() {\n            final Object level = null, chunkBB = null;\n")
    out.append(code)
    out.append("        }\n")
    for hsrc in helpers(path, extra):
        out.append("\n        " + fix(hsrc).strip().replace("\n   ", "\n        ") + "\n")
    if cls == "JungleTemple":
        out.append("\n        private static final MossStoneSelector STONE_SELECTOR = new MossStoneSelector();\n\n"
                   "        private static class MossStoneSelector extends BlockSelector {\n"
                   "            @Override\n            void next(RandomSource random, int worldX, int worldY, int worldZ, boolean isEdge) {\n"
                   "                if (random.nextFloat() < 0.4F) this.next = Blocks.COBBLESTONE.defaultBlockState();\n"
                   "                else this.next = Blocks.MOSSY_COBBLESTONE.defaultBlockState();\n            }\n        }\n")
    out.append("    }\n\n")
out.append("}\n")
print("".join(out))
