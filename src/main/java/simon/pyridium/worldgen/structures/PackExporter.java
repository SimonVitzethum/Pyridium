package simon.pyridium.worldgen.structures;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import simon.pyridium.worldgen.structures.tools.VanillaBoot;

/**
 * Offline-Werkzeug (nur Entwicklung, einmalig; Ausgabe wird committet): schreibt die mitgelieferten Daten
 * {@code src/main/resources/data/pyridium/} aus den fest einprogrammierten Vanilla-Bauanweisungen und den Vanilla-Strukturdefinitionen:
 * <ul>
 * <li>{@code structure/scattered/*.nbt}: Vorlagen der Streu-Bauwerke (Wuestenpyramide, Dschungeltempel, Sumpfhuette; aus {@link ScatteredPieces})</li>
 * <li>{@code worldgen/structure_override/minecraft/*.json}: Jigsaw-Ersatzdefinition je prozeduralem Vanilla-Strukturtyp (siehe
 * StructureCompiler: Biome/Schritt/Anpassung kommen aus der Original-Struktur, alles andere aus dieser Datei)</li>
 * </ul>
 * Zur Laufzeit liest nur noch der generische Jigsaw-Pfad diese Daten; die Bauanweisungen sind danach nicht mehr noetig.
 * Aufruf: {@code ./gradlew exportPack}.
 */
public final class PackExporter {
    private static String currentName;
    private static final Path OUT = Path.of("src/main/resources/data/pyridium");

    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        VanillaBoot boot = new VanillaBoot();
        StructureData data = StructureData.of(boot.resources);
        Map<String, JsonElement> structs = new TreeMap<>(data.json("worldgen/structure"));
        // --- Streu-Bauwerke -> NBT
        int[] startY = new int[3];
        String[] scattered = {"desert_pyramid", "jungle_temple", "swamp_hut"};
        VirtualPiece[] vps = {new ScatteredPieces.DesertPyramid(new net.minecraft.world.level.levelgen.LegacyRandomSource(1234L)),
                new ScatteredPieces.JungleTemple(new net.minecraft.world.level.levelgen.LegacyRandomSource(1234L)),
                new ScatteredPieces.SwampHut(new net.minecraft.world.level.levelgen.LegacyRandomSource(1234L))};
        for (int i = 0; i < 3; i++) startY[i] = writeTemplate(scattered[i], vps[i]);
        // --- Overrides
        int n = 0;
        for (var e : structs.entrySet()) {
            JsonObject o = e.getValue().getAsJsonObject();
            String type = o.get("type").getAsString();
            String name = e.getKey().substring(e.getKey().indexOf(':') + 1);
            JsonObject ov = null;
            currentName = name;
            switch (type) {
                case "minecraft:desert_pyramid" -> ov = single("pyridium:scattered/desert_pyramid", "WORLD_SURFACE_WG", startY[0] + 1);
                case "minecraft:jungle_temple" -> ov = single("pyridium:scattered/jungle_temple", "WORLD_SURFACE_WG", startY[1] + 1);
                case "minecraft:swamp_hut" -> ov = single("pyridium:scattered/swamp_hut", "WORLD_SURFACE_WG", startY[2] + 1);
                case "minecraft:igloo" -> ov = single("minecraft:igloo/top", "WORLD_SURFACE_WG", 0);
                case "minecraft:shipwreck" -> {
                    boolean beached = o.has("is_beached") && o.get("is_beached").getAsBoolean();
                    ov = pool(beached ? "WORLD_SURFACE_WG" : "OCEAN_FLOOR_WG", 0, shipwrecks(beached));
                }
                case "minecraft:ocean_ruin" -> ov = pool("OCEAN_FLOOR_WG", 0, oceanRuins(o));
                case "minecraft:buried_treasure" -> {
                    writeChest();
                    ov = single("pyridium:scattered/buried_treasure", "OCEAN_FLOOR_WG", -2);
                }
                case "minecraft:nether_fossil" -> {
                    List<Object[]> l = new ArrayList<>();
                    for (int k = 1; k <= 14; k++) l.add(new Object[]{"minecraft:nether_fossils/fossil_" + k, 1});
                    ov = pool("NONE", 0, l);
                    ov.add("start_height", o.get("height"));
                }
                case "minecraft:ruined_portal" -> ov = portal(o);
                default -> {
                }
            }
            if (ov == null) continue;
            write("worldgen/structure_override/minecraft/" + name + ".json", ov);
            n++;
        }
        fortress();
        n++;
        endCity(data);
        mansion(data);
        n += 2;
        System.out.println("Overrides geschrieben: " + n);
    }

    // --- Nether-Festung als Jigsaw-Netz ---------------------------------------------------------------------------
    // Teiltypen in der Reihenfolge von FortressPieces/FT_* : Breite, Hoehe, Tiefe, Versatz (ox, oy) des Boxursprungs zum Einstiegspunkt
    private static final int[][] FT_DIM = {{19, 10, 19, -8, -3}, {5, 10, 8, -1, -3}, {5, 10, 19, -1, -3}, {5, 14, 10, -1, -7}, {9, 7, 9, -3, 0}, {13, 14, 13, -5, -3},
            {5, 7, 5, -1, 0}, {5, 7, 5, -1, 0}, {5, 7, 5, -1, 0}, {5, 7, 5, -1, 0}, {13, 14, 13, -5, -3}, {7, 8, 9, -2, 0}, {7, 9, 7, -2, 0}, {7, 11, 7, -2, 0}};
    private static final String[] FT_NAME = {"bridge_crossing", "bridge_end", "bridge_straight", "corridor_stairs", "t_balcony", "entrance", "small_crossing", "left_turn",
            "small_corridor", "right_turn", "stalk_room", "throne", "room_crossing", "stairs_room"};
    /** je Typ: Kinder {Art (0 vorwaerts, 1 links, 2 rechts), Versatz a, Versatz b, Burgliste (1) / Bruecke (0)} */
    private static final int[][][] FT_CHILD = {
            {{0, 8, 3, 0}, {1, 3, 8, 0}, {2, 3, 8, 0}}, {}, {{0, 1, 3, 0}}, {{0, 1, 0, 1}}, {{1, 0, 1, 1}, {2, 0, 1, 1}}, {{0, 5, 3, 1}},
            {{0, 1, 0, 1}, {1, 0, 1, 1}, {2, 0, 1, 1}}, {{1, 0, 1, 1}}, {{0, 1, 0, 1}}, {{2, 0, 1, 1}}, {{0, 5, 3, 1}, {0, 5, 11, 1}}, {},
            {{0, 2, 0, 0}, {1, 0, 2, 0}, {2, 0, 2, 0}}, {{2, 6, 2, 0}}};

    private static void fortress() throws IOException {
        var r = new net.minecraft.world.level.levelgen.LegacyRandomSource(1234L);
        VirtualPiece[] ps = {new FortressPieces.BridgeCrossing(r), new FortressPieces.BridgeEndFiller(r), new FortressPieces.BridgeStraight(r),
                new FortressPieces.CastleCorridorStairsPiece(r), new FortressPieces.CastleCorridorTBalconyPiece(r), new FortressPieces.CastleEntrance(r),
                new FortressPieces.CastleSmallCorridorCrossingPiece(r), new FortressPieces.CastleSmallCorridorLeftTurnPiece(r),
                new FortressPieces.CastleSmallCorridorPiece(r), new FortressPieces.CastleSmallCorridorRightTurnPiece(r), new FortressPieces.CastleStalkRoom(r),
                new FortressPieces.MonsterThrone(r), new FortressPieces.RoomCrossing(r), new FortressPieces.StairsRoom(r)};
        for (int t = 0; t < ps.length; t++) {
            ps[t].build();
            int[] e = ps[t].extents();
            int w = FT_DIM[t][0], h = FT_DIM[t][1], d = FT_DIM[t][2], ox = FT_DIM[t][3], oy = FT_DIM[t][4];
            int x0 = Math.min(0, e[0]), y0 = Math.min(0, e[1]), z0 = Math.min(0, e[2]);
            int x1 = Math.max(w - 1, e[3]), y1 = Math.max(h - 1, e[4]), z1 = Math.max(d - 1, e[5]);
            int sx = x1 - x0 + 1, sy = y1 - y0 + 1, sz = z1 - z0 + 1;
            java.util.Map<String, CompoundTag> jig = new java.util.HashMap<>();
            List<BlockState> palette = new ArrayList<>();
            ListTag blocks = new ListTag();
            // Jigsaws (Zellen in Template-Koordinaten): Eintritt (nach Norden) und die Kinderanschluesse des Typs
            List<int[]> jigs = new ArrayList<>();      // x, y, z, Richtung (0 N,1 O,2 S,3 W), Pool (0 Bruecke, 1 Burg, 2 Eintritt)
            if (t != 0) jigs.add(new int[]{-ox - x0, -oy - y0, -z0, 0, 2});
            for (int[] c : FT_CHILD[t]) {
                int jx, jy, jz, dir;
                if (c[0] == 0) { jx = c[1]; jy = c[2]; jz = d - 1; dir = 2; }
                else if (c[0] == 1) { jx = 0; jy = c[1]; jz = c[2]; dir = 3; }
                else { jx = w - 1; jy = c[1]; jz = c[2]; dir = 1; }
                jigs.add(new int[]{jx - x0, jy - y0, jz - z0, dir, c[3]});
            }
            java.util.Set<Long> jigCells = new java.util.HashSet<>();
            for (int[] j : jigs) jigCells.add(((long) j[0] << 40) ^ ((long) j[1] << 20) ^ j[2]);
            for (int y = 0; y < sy; y++)
                for (int z = 0; z < sz; z++)
                    for (int x = 0; x < sx; x++) {
                        if (jigCells.contains(((long) x << 40) ^ ((long) y << 20) ^ z)) continue;
                        BlockState st = ps[t].at(x0 + x, y0 + y, z0 + z);
                        if (st == null) continue;
                        blocks.add(blockTag(x, y, z, indexOf(palette, st), null));
                    }
            net.minecraft.core.Direction[] dirs = {net.minecraft.core.Direction.NORTH, net.minecraft.core.Direction.EAST, net.minecraft.core.Direction.SOUTH, net.minecraft.core.Direction.WEST};
            for (int[] j : jigs) {
                BlockState js = Blocks.JIGSAW.defaultBlockState().setValue(net.minecraft.world.level.block.JigsawBlock.ORIENTATION,
                        net.minecraft.core.FrontAndTop.fromFrontAndTop(dirs[j[3]], net.minecraft.core.Direction.UP));
                CompoundTag nbt = new CompoundTag();
                nbt.putString("id", "minecraft:jigsaw");
                nbt.putString("name", "pyridium:fortress/link");
                nbt.putString("target", "pyridium:fortress/link");
                nbt.putString("pool", j[4] == 0 ? "pyridium:fortress/bridge" : j[4] == 1 ? "pyridium:fortress/castle" : "minecraft:empty");
                nbt.putString("joint", "rollable");
                nbt.putString("final_state", "minecraft:air");
                blocks.add(blockTag(j[0], j[1], j[2], indexOf(palette, js), nbt));
            }
            save("structure/fortress/" + FT_NAME[t] + ".nbt", sx, sy, sz, palette, blocks);
        }
        JsonObject bridge = new JsonObject(), castle = new JsonObject(), start = new JsonObject();
        poolFile("fortress/bridge", new int[][]{{2, 30}, {0, 10}, {12, 10}, {13, 10}, {11, 5}, {5, 5}}, 1);
        poolFile("fortress/castle", new int[][]{{8, 25}, {6, 15}, {9, 5}, {7, 5}, {3, 10}, {4, 7}, {10, 5}}, 1);
        poolFile("fortress/start", new int[][]{{0, 1}}, -1);
        JsonObject o = new JsonObject();
        o.addProperty("type", "minecraft:jigsaw");
        o.addProperty("start_pool", "pyridium:fortress/start");
        o.addProperty("size", 12);
        JsonObject sh = new JsonObject();
        sh.addProperty("type", "minecraft:uniform");
        JsonObject lo = new JsonObject(), hi = new JsonObject();
        lo.addProperty("absolute", 48);
        hi.addProperty("absolute", 64);
        sh.add("min_inclusive", lo);
        sh.add("max_inclusive", hi);
        o.add("start_height", sh);
        o.addProperty("max_distance_from_center", 112);
        o.addProperty("use_expansion_hack", false);
        write("worldgen/structure_override/minecraft/fortress.json", o);
    }

    // --- Vanilla-Vorlagen zusammensetzen (End City, Villa) ---------------------------------------------------------
    /** Blockkarte aus mehreren Vanilla-Vorlagen mit Versatz (spaetere ueberschreiben); Jigsaws kommen danach dazu */
    private static final class Comp {
        final java.util.Map<Long, BlockState> blocks = new java.util.HashMap<>();
        final java.util.List<Object[]> jigs = new ArrayList<>();      // x, y, z, Richtung (0 N,1 O,2 S,3 W,4 oben,5 unten), Pool
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;

        static long key(int x, int y, int z) {
            return ((long) (x + 4096) << 40) | ((long) (y + 4096) << 20) | (z + 4096);
        }

        void add(StructureData data, String id, int ox, int oy, int oz) throws IOException {
            byte[] raw = data.bytes("structure", id, ".nbt");
            if (raw == null) throw new IOException("Vorlage fehlt: " + id);
            CompoundTag tag = NbtIo.readCompressed(new java.io.ByteArrayInputStream(raw), net.minecraft.nbt.NbtAccounter.unlimitedHeap());
            ListTag pal = tag.getListOrEmpty("palette");
            BlockState[] ps = new BlockState[pal.size()];
            for (int i = 0; i < ps.length; i++) ps[i] = NbtUtils.readBlockState(net.minecraft.core.registries.BuiltInRegistries.BLOCK, pal.getCompoundOrEmpty(i));
            ListTag bl = tag.getListOrEmpty("blocks");
            for (int i = 0; i < bl.size(); i++) {
                CompoundTag b = bl.getCompoundOrEmpty(i);
                ListTag pos = b.getListOrEmpty("pos");
                BlockState st = ps[b.getIntOr("state", 0)];
                if (st.is(Blocks.STRUCTURE_BLOCK) || st.is(Blocks.JIGSAW)) continue;
                int x = pos.getIntOr(0, 0) + ox, y = pos.getIntOr(1, 0) + oy, z = pos.getIntOr(2, 0) + oz;
                if (st.isAir() && blocks.containsKey(key(x, y, z))) continue;      // Luft ueberschreibt nichts
                blocks.put(key(x, y, z), st);
                minX = Math.min(minX, x); minY = Math.min(minY, y); minZ = Math.min(minZ, z);
                maxX = Math.max(maxX, x); maxY = Math.max(maxY, y); maxZ = Math.max(maxZ, z);
            }
        }

        int cx() { return (minX + maxX) / 2; }

        int cz() { return (minZ + maxZ) / 2; }

        /** Jigsaw in Rohkoordinaten (vor der Normierung) */
        void jig(int x, int y, int z, int dir, String pool) {
            jigs.add(new Object[]{x, y, z, dir, pool});
            minX = Math.min(minX, x); minY = Math.min(minY, y); minZ = Math.min(minZ, z);
            maxX = Math.max(maxX, x); maxY = Math.max(maxY, y); maxZ = Math.max(maxZ, z);
        }

        void save(String rel, String link) throws IOException {
            List<BlockState> palette = new ArrayList<>();
            ListTag out = new ListTag();
            java.util.Set<Long> jc = new java.util.HashSet<>();
            for (Object[] j : jigs) jc.add(key((int) j[0], (int) j[1], (int) j[2]));
            for (var e : new java.util.TreeMap<>(blocks).entrySet()) {
                if (jc.contains(e.getKey())) continue;
                long k = e.getKey();
                int x = (int) (k >> 40) - 4096, y = (int) ((k >> 20) & 0xfffff) - 4096, z = (int) (k & 0xfffff) - 4096;
                out.add(blockTag(x - minX, y - minY, z - minZ, indexOf(palette, e.getValue()), null));
            }
            net.minecraft.core.Direction[] dirs = {net.minecraft.core.Direction.NORTH, net.minecraft.core.Direction.EAST, net.minecraft.core.Direction.SOUTH,
                    net.minecraft.core.Direction.WEST, net.minecraft.core.Direction.UP, net.minecraft.core.Direction.DOWN};
            for (Object[] j : jigs) {
                int d = (int) j[3];
                var front = d >= 4 ? net.minecraft.core.FrontAndTop.fromFrontAndTop(dirs[d], net.minecraft.core.Direction.NORTH)
                        : net.minecraft.core.FrontAndTop.fromFrontAndTop(dirs[d], net.minecraft.core.Direction.UP);
                BlockState js = Blocks.JIGSAW.defaultBlockState().setValue(net.minecraft.world.level.block.JigsawBlock.ORIENTATION, front);
                CompoundTag nbt = new CompoundTag();
                nbt.putString("id", "minecraft:jigsaw");
                nbt.putString("name", link + (d >= 4 ? "/vertical" : "/side"));
                nbt.putString("target", link + (d >= 4 ? "/vertical" : "/side"));
                nbt.putString("pool", (String) j[4]);
                nbt.putString("joint", "rollable");
                nbt.putString("final_state", "minecraft:air");
                out.add(blockTag((int) j[0] - minX, (int) j[1] - minY, (int) j[2] - minZ, indexOf(palette, js), nbt));
            }
            PackExporter.save("structure/" + rel + ".nbt", maxX - minX + 1, maxY - minY + 1, maxZ - minZ + 1, palette, out);
        }
    }

    private static void simplePool(String rel, String[][] elems, String fallback) throws IOException {
        JsonObject pool = new JsonObject();
        pool.addProperty("fallback", fallback);
        JsonArray arr = new JsonArray();
        for (String[] e : elems) arr.add(element(e[0], Integer.parseInt(e[1])));
        pool.add("elements", arr);
        write("worldgen/template_pool/" + rel + ".json", pool);
    }

    private static void jigStructure(String name, String startPool, int size, String project, int startHeight, int dist) throws IOException {
        JsonObject o = structureShell(project, startHeight);
        o.addProperty("size", size);
        o.addProperty("start_pool", startPool);
        o.addProperty("max_distance_from_center", dist);
        write("worldgen/structure_override/minecraft/" + name + ".json", o);
    }

    // --- End City als Jigsaw-Netz: Haus-Tuerme, Tuerme, dicke Tuerme, Bruecken, Schiff ----------------------------------------
    private static void endCity(StructureData data) throws IOException {
        String E = "minecraft:end_city/", P = "pyridium:end_city/", L = "pyridium:end_city";
        // Haus-Tuerme (Offsets wie EndCityPieces: Stockwerke -1/-1 versetzt, Dach 4 bzw. 8 hoeher)
        String[][] houses = {
                {"house_start", "base_floor", "0,0,0", "second_floor_1", "-1,0,-1", "third_floor_1", "-2,4,-2", "third_roof", "-3,12,-3"},
                {"house_0", "base_floor", "0,0,0", "base_roof", "-1,4,-1"},
                {"house_1", "base_floor", "0,0,0", "second_floor_2", "-1,0,-1", "second_roof", "-2,8,-2"},
                {"house_2", "base_floor", "0,0,0", "second_floor_2", "-1,0,-1", "third_floor_2", "-2,4,-2", "third_roof", "-3,12,-3"}};
        for (String[] h : houses) {
            Comp c = new Comp();
            for (int i = 1; i + 1 < h.length; i += 2) {
                String[] o = h[i + 1].split(",");
                c.add(data, E + h[i], Integer.parseInt(o[0]), Integer.parseInt(o[1]), Integer.parseInt(o[2]));
            }
            int cx = c.cx(), cz = c.cz();
            if (!h[0].equals("house_start")) {
                // Eintritt von Bruecken auf allen vier Seiten, Erdgeschoss
                c.jig(cx, c.minY + 2, c.minZ, 0, "minecraft:empty");
                c.jig(c.maxX, c.minY + 2, cz, 1, "minecraft:empty");
                c.jig(cx, c.minY + 2, c.maxZ, 2, "minecraft:empty");
                c.jig(c.minX, c.minY + 2, cz, 3, "minecraft:empty");
            }
            c.jig(cx, c.maxY, cz, 4, P + "tower");
            c.save("end_city/" + h[0], L);
        }
        // Tuerme: Basis + Stuecke, entweder mit Bruecken (Seitenanschluesse) oder mit dickem Turm obendrauf
        for (int variant = 0; variant < 6; variant++) {
            int extra = 1 + variant % 3;
            boolean bridges = variant < 3;
            Comp c = new Comp();
            c.add(data, E + "tower_base", 0, 0, 0);
            c.add(data, E + "tower_piece", 0, 7, 0);
            for (int i = 0; i < extra; i++) c.add(data, E + "tower_piece", 0, 11 + 4 * i, 0);
            int cx = c.cx(), cz = c.cz();
            c.jig(cx, c.minY, cz, 5, "minecraft:empty");
            if (bridges) {
                int by = c.minY + 9;
                c.jig(cx, by, c.minZ, 0, P + "bridge");
                c.jig(c.maxX, by, cz, 1, P + "bridge");
                if (extra > 1) {
                    c.jig(cx, by, c.maxZ, 2, P + "bridge");
                    c.jig(c.minX, by, cz, 3, P + "bridge");
                }
                c.add(data, E + "tower_top", -1, c.maxY + 1 - 0, -1);
                c.maxY = Math.max(c.maxY, c.maxY);
            } else {
                c.jig(cx, c.maxY, cz, 4, P + "fat_tower");
            }
            c.save("end_city/tower_" + variant, L);
        }
        // Dicke Tuerme mit Bruecken
        for (int variant = 0; variant < 3; variant++) {
            Comp c = new Comp();
            c.add(data, E + "fat_tower_base", 0, 0, 0);
            for (int i = 0; i <= variant; i++) c.add(data, E + "fat_tower_middle", 0, 4 + 8 * i, 0);
            c.add(data, E + "fat_tower_top", -2, 4 + 8 * (variant + 1), -2);
            int cx = c.cx(), cz = c.cz();
            c.jig(cx, c.minY, cz, 5, "minecraft:empty");
            int by = c.minY + 6;
            c.jig(cx, by, c.minZ, 0, P + "bridge");
            c.jig(c.maxX, by, cz, 1, P + "bridge");
            c.jig(cx, by, c.maxZ, 2, P + "bridge");
            c.jig(c.minX, by, cz, 3, P + "bridge");
            c.save("end_city/fat_tower_" + variant, L);
        }
        // Bruecken: Anfang, 1-4 Stuecke (auch Treppen), Ende an einem neuen Haus-Turm; eine Variante endet am Schiff
        for (int variant = 0; variant < 5; variant++) {
            int n = 1 + variant % 4;
            Comp c = new Comp();
            c.add(data, E + "bridge_end", 0, 0, 0);
            int z = -4;
            for (int i = 0; i < n; i++) {
                String piece = (variant == 3 && i == 1) ? "bridge_steep_stairs" : (variant == 2 && i == 1) ? "bridge_gentle_stairs" : "bridge_piece";
                c.add(data, E + piece, 0, 0, z);
                z -= piece.equals("bridge_gentle_stairs") ? 8 : 4;
            }
            boolean ship = variant == 4;
            if (ship) c.add(data, E + "ship", -8, 0, z - 14);
            int cx = c.cx(), y = c.minY + 1;
            c.jig(cx, y, c.maxZ, 2, "minecraft:empty");
            if (!ship) c.jig(cx, y, c.minZ, 0, P + "house");
            c.save("end_city/bridge_" + variant, L);
        }
        simplePool("end_city/start", new String[][]{{P + "house_start", "1"}}, "minecraft:empty");
        simplePool("end_city/house", new String[][]{{P + "house_0", "1"}, {P + "house_1", "1"}, {P + "house_2", "1"}}, "minecraft:empty");
        simplePool("end_city/tower", new String[][]{{P + "tower_0", "1"}, {P + "tower_1", "1"}, {P + "tower_2", "1"}, {P + "tower_3", "1"}, {P + "tower_4", "1"}, {P + "tower_5", "1"}}, "minecraft:empty");
        simplePool("end_city/fat_tower", new String[][]{{P + "fat_tower_0", "1"}, {P + "fat_tower_1", "1"}, {P + "fat_tower_2", "1"}}, "minecraft:empty");
        simplePool("end_city/bridge", new String[][]{{P + "bridge_0", "3"}, {P + "bridge_1", "3"}, {P + "bridge_2", "2"}, {P + "bridge_3", "2"}, {P + "bridge_4", "1"}}, "minecraft:empty");
        jigStructure("end_city", "pyridium:end_city/start", 9, "WORLD_SURFACE_WG", 0, 120);
    }

    // --- Woodland Mansion: Eingang + Raumvorlagen, Tueranschluesse (Raumraster als Verbinder-Wachstum) -----------------------------
    private static void mansion(StructureData data) throws IOException {
        String M = "minecraft:woodland_mansion/", P = "pyridium:mansion/", L = "pyridium:mansion";
        List<String> rooms = new ArrayList<>();
        for (int i = 1; i <= 5; i++) rooms.add("1x1_a" + i);
        for (int i = 1; i <= 5; i++) rooms.add("1x1_b" + i);
        for (int i = 1; i <= 9; i++) rooms.add("1x2_a" + i);
        for (int i = 1; i <= 5; i++) rooms.add("1x2_b" + i);
        for (int i = 1; i <= 4; i++) rooms.add("2x2_a" + i);
        for (int i = 1; i <= 5; i++) rooms.add("2x2_b" + i);
        List<String[]> pool = new ArrayList<>();
        for (String r : rooms) {
            Comp c = new Comp();
            c.add(data, M + r, 0, 0, 0);
            int cz = (c.minZ + c.maxZ) / 2, cx = (c.minX + c.maxX) / 2, y = c.minY + 1;
            c.jig(c.minX, y, cz, 3, P + "rooms");
            c.jig(c.maxX, y, cz, 1, P + "rooms");
            c.jig(cx, y, c.minZ, 0, P + "rooms");
            c.jig(cx, y, c.maxZ, 2, P + "rooms");
            c.save("mansion/" + r, L);
            pool.add(new String[]{P + r, r.startsWith("2x2") ? "1" : "2"});
        }
        Comp e = new Comp();
        e.add(data, M + "entrance", 0, 0, 0);
        int cz = (e.minZ + e.maxZ) / 2, cx = (e.minX + e.maxX) / 2;
        e.jig(cx, e.minY + 1, e.minZ, 0, P + "rooms");
        e.jig(e.minX, e.minY + 1, cz, 3, P + "rooms");
        e.jig(e.maxX, e.minY + 1, cz, 1, P + "rooms");
        e.save("mansion/entrance", L);
        simplePool("mansion/start", new String[][]{{P + "entrance", "1"}}, "minecraft:empty");
        simplePool("mansion/rooms", pool.toArray(new String[0][]), "minecraft:empty");
        jigStructure("mansion", "pyridium:mansion/start", 8, "WORLD_SURFACE_WG", 0, 60);
    }

    private static int indexOf(List<BlockState> palette, BlockState st) {
        int i = palette.indexOf(st);
        if (i < 0) {
            i = palette.size();
            palette.add(st);
        }
        return i;
    }

    private static CompoundTag blockTag(int x, int y, int z, int state, CompoundTag nbt) {
        CompoundTag b = new CompoundTag();
        b.putInt("state", state);
        ListTag pos = new ListTag();
        pos.add(IntTag.valueOf(x));
        pos.add(IntTag.valueOf(y));
        pos.add(IntTag.valueOf(z));
        b.put("pos", pos);
        if (nbt != null) b.put("nbt", nbt);
        return b;
    }

    /** Pool-Datei: Elemente {Typindex, Gewicht}; fallbackType >= 0: Fallback-Pool "pyridium:fortress/<end>" (BridgeEndFiller) */
    private static void poolFile(String rel, int[][] elems, int fallbackType) throws IOException {
        JsonObject pool = new JsonObject();
        pool.addProperty("fallback", fallbackType == 1 ? "pyridium:fortress/end" : "minecraft:empty");
        JsonArray arr = new JsonArray();
        for (int[] e : elems) arr.add(element("pyridium:fortress/" + FT_NAME[e[0]], e[1]));
        pool.add("elements", arr);
        write("worldgen/template_pool/" + rel + ".json", pool);
        if (rel.equals("fortress/bridge")) {
            JsonObject end = new JsonObject();
            end.addProperty("fallback", "minecraft:empty");
            JsonArray ea = new JsonArray();
            ea.add(element("pyridium:fortress/bridge_end", 1));
            end.add("elements", ea);
            write("worldgen/template_pool/fortress/end.json", end);
        }
    }

    // --- JSON-Bausteine ---------------------------------------------------------------------------------------------
    private static JsonObject element(String loc, int weight) {
        JsonObject el = new JsonObject();
        el.addProperty("weight", weight);
        JsonObject inner = new JsonObject();
        inner.addProperty("element_type", "minecraft:single_pool_element");
        inner.addProperty("location", loc);
        inner.addProperty("projection", "rigid");
        inner.addProperty("processors", "minecraft:empty");
        el.add("element", inner);
        return el;
    }

    private static JsonObject structureShell(String project, int startHeight) {
        JsonObject o = new JsonObject();
        o.addProperty("type", "minecraft:jigsaw");
        o.addProperty("size", 1);
        JsonObject sh = new JsonObject();
        sh.addProperty("absolute", startHeight);
        o.add("start_height", sh);
        if (!project.equals("NONE")) o.addProperty("project_start_to_heightmap", project);
        o.addProperty("max_distance_from_center", 24);
        o.addProperty("use_expansion_hack", false);
        return o;
    }

    private static JsonObject single(String loc, String project, int startHeight) {
        List<Object[]> l = new ArrayList<>();
        l.add(new Object[]{loc, 1});
        return pool(project, startHeight, l);
    }

    /** Struktur mit eingebettetem Pool: "start_pool" ist hier eine Inline-Definition (der Compiler akzeptiert Objekte) */
    private static JsonObject pool(String project, int startHeight, List<Object[]> elems) {
        JsonObject o = structureShell(project, startHeight);
        JsonObject pool = new JsonObject();
        pool.addProperty("fallback", "minecraft:empty");
        JsonArray arr = new JsonArray();
        for (Object[] e : elems) arr.add(element((String) e[0], (Integer) e[1]));
        pool.add("elements", arr);
        // Pool als eigene Datei (template_pool), Struktur verweist per Name
        try {
            write("worldgen/template_pool/override/" + currentName + ".json", pool);
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
        o.addProperty("start_pool", "pyridium:override/" + currentName);
        return o;
    }

    private static List<Object[]> shipwrecks(boolean beached) {
        String[] names = beached
                ? new String[]{"with_mast", "sideways_full", "sideways_fronthalf", "sideways_backhalf", "rightsideup_full", "rightsideup_fronthalf", "rightsideup_backhalf",
                "with_mast_degraded", "rightsideup_full_degraded", "rightsideup_fronthalf_degraded", "rightsideup_backhalf_degraded"}
                : new String[]{"with_mast", "upsidedown_full", "upsidedown_fronthalf", "upsidedown_backhalf", "sideways_full", "sideways_fronthalf", "sideways_backhalf",
                "rightsideup_full", "rightsideup_fronthalf", "rightsideup_backhalf", "with_mast_degraded", "upsidedown_full_degraded", "upsidedown_fronthalf_degraded",
                "upsidedown_backhalf_degraded", "sideways_full_degraded", "sideways_fronthalf_degraded", "sideways_backhalf_degraded", "rightsideup_full_degraded",
                "rightsideup_fronthalf_degraded", "rightsideup_backhalf_degraded"};
        List<Object[]> l = new ArrayList<>();
        for (String n : names) l.add(new Object[]{"minecraft:shipwreck/" + n, 1});
        return l;
    }

    private static List<Object[]> oceanRuins(JsonObject o) {
        boolean warm = o.has("biome_temp") && o.get("biome_temp").getAsString().equals("warm");
        double large = o.has("large_probability") ? o.get("large_probability").getAsDouble() : 0.3;
        int wSmall = (int) Math.round((1 - large) * 100), wBig = (int) Math.round(large * 100);
        List<Object[]> l = new ArrayList<>();
        String u = "minecraft:underwater_ruin/";
        if (warm) {
            for (int i = 1; i <= 8; i++) l.add(new Object[]{u + "warm_" + i, Math.max(1, wSmall / 8)});
            for (int i = 4; i <= 7; i++) l.add(new Object[]{u + "big_warm_" + i, Math.max(1, wBig / 4)});
        } else {
            for (String k : new String[]{"brick", "cracked", "mossy"}) for (int i = 1; i <= 8; i++) l.add(new Object[]{u + k + "_" + i, Math.max(1, wSmall / 24)});
            for (String k : new String[]{"brick", "cracked", "mossy"}) for (int i : new int[]{1, 2, 3, 8}) l.add(new Object[]{u + "big_" + k + "_" + i, Math.max(1, wBig / 12)});
        }
        return l;
    }

    private static JsonObject portal(JsonObject o) {
        // ruinedPortal: wichtigstes Setup (hoechstes Gewicht, bei Gleichstand das erste) bestimmt Hoehe/Projektion
        String placement = "on_land_surface";
        double best = -1;
        for (JsonElement e : o.getAsJsonArray("setups")) {
            JsonObject s = e.getAsJsonObject();
            double w = s.get("weight").getAsDouble();
            if (w > best) {
                best = w;
                placement = s.get("placement").getAsString();
            }
        }
        List<Object[]> l = new ArrayList<>();
        for (int i = 1; i <= 10; i++) l.add(new Object[]{"minecraft:ruined_portal/portal_" + i, 19});
        for (int i = 1; i <= 3; i++) l.add(new Object[]{"minecraft:ruined_portal/giant_portal_" + i, 3});
        return switch (placement) {
            case "partly_buried" -> pool("WORLD_SURFACE_WG", -4, l);
            case "on_ocean_floor" -> pool("OCEAN_FLOOR_WG", 0, l);
            case "in_mountain" -> pool("WORLD_SURFACE_WG", -8, l);
            case "underground" -> pool("WORLD_SURFACE_WG", -24, l);
            case "in_nether" -> {
                JsonObject r = pool("NONE", 0, l);
                JsonObject sh = new JsonObject();
                sh.addProperty("type", "minecraft:uniform");
                JsonObject lo = new JsonObject(), hi = new JsonObject();
                lo.addProperty("absolute", 32);
                hi.addProperty("absolute", 100);
                sh.add("min_inclusive", lo);
                sh.add("max_inclusive", hi);
                r.add("start_height", sh);
                yield r;
            }
            default -> pool("WORLD_SURFACE_WG", 0, l);
        };
    }

    // --- NBT --------------------------------------------------------------------------------------------------------
    /** Schreibt die Vorlage; Rueckgabe: virtuelles y der untersten Schicht (negativ = Fundament unter dem Boden) */
    private static int writeTemplate(String name, VirtualPiece vp) throws IOException {
        vp.build();
        int[] e = vp.extents();
        int sx = e[3] - e[0] + 1, sy = e[4] - e[1] + 1, sz = e[5] - e[2] + 1;
        List<BlockState> palette = new ArrayList<>();
        ListTag blocks = new ListTag();
        for (int y = 0; y < sy; y++)
            for (int z = 0; z < sz; z++)
                for (int x = 0; x < sx; x++) {
                    BlockState st = vp.at(e[0] + x, e[1] + y, e[2] + z);
                    if (st == null) continue;
                    int idx = palette.indexOf(st);
                    if (idx < 0) {
                        idx = palette.size();
                        palette.add(st);
                    }
                    CompoundTag b = new CompoundTag();
                    b.putInt("state", idx);
                    ListTag pos = new ListTag();
                    pos.add(IntTag.valueOf(x));
                    pos.add(IntTag.valueOf(y));
                    pos.add(IntTag.valueOf(z));
                    b.put("pos", pos);
                    blocks.add(b);
                }
        save("structure/scattered/" + name + ".nbt", sx, sy, sz, palette, blocks);
        return e[1];
    }

    private static void writeChest() throws IOException {
        List<BlockState> palette = List.of(Blocks.CHEST.defaultBlockState());
        ListTag blocks = new ListTag();
        CompoundTag b = new CompoundTag();
        b.putInt("state", 0);
        ListTag pos = new ListTag();
        pos.add(IntTag.valueOf(0));
        pos.add(IntTag.valueOf(0));
        pos.add(IntTag.valueOf(0));
        b.put("pos", pos);
        blocks.add(b);
        save("structure/scattered/buried_treasure.nbt", 1, 1, 1, palette, blocks);
    }

    private static void save(String rel, int sx, int sy, int sz, List<BlockState> palette, ListTag blocks) throws IOException {
        CompoundTag root = new CompoundTag();
        root.putInt("DataVersion", SharedConstants.getCurrentVersion().dataVersion().version());
        ListTag size = new ListTag();
        size.add(IntTag.valueOf(sx));
        size.add(IntTag.valueOf(sy));
        size.add(IntTag.valueOf(sz));
        root.put("size", size);
        ListTag pal = new ListTag();
        for (BlockState s : palette) pal.add(NbtUtils.writeBlockState(s));
        root.put("palette", pal);
        root.put("blocks", blocks);
        root.put("entities", new ListTag());
        Path p = OUT.resolve(rel);
        Files.createDirectories(p.getParent());
        NbtIo.writeCompressed(root, p);
        System.out.println("Vorlage " + rel + " (" + sx + "x" + sy + "x" + sz + ", " + blocks.size() + " Bloecke)");
    }

    private static void write(String rel, JsonObject o) throws IOException {
        Path p = OUT.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.writeString(p, new GsonBuilder().setPrettyPrinting().create().toJson(o));
    }
}
