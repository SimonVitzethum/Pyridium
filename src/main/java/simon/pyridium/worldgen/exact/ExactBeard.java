package simon.pyridium.worldgen.exact;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import java.lang.reflect.Field;
import java.util.List;
import net.minecraft.world.level.levelgen.Beardifier;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.pools.JigsawJunction;
import org.jspecify.annotations.Nullable;

/**
 * Beardifier eines Chunks fuer OP_BEARD (wg_exact_task_body.cu): betroffene Box, Teile
 * (Box, Anpassung, groundLevelDelta) und Jigsaw-Kreuzungen in Vanillas Reihenfolge (die float-
 * Summe ist reihenfolgeabhaengig).
 */
public final class ExactBeard {
    private static final Field PIECES, JUNCTIONS, BOX;

    static {
        try {
            PIECES = Beardifier.class.getDeclaredField("pieces");
            JUNCTIONS = Beardifier.class.getDeclaredField("junctions");
            BOX = Beardifier.class.getDeclaredField("affectedBox");
            PIECES.setAccessible(true);
            JUNCTIONS.setAccessible(true);
            BOX.setAccessible(true);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private ExactBeard() {
    }

    /** Daten {Box(6), Teile, Kreuzungen, je Teil 8, je Kreuzung 3} oder null (leer/ohne Box). */
    @SuppressWarnings("unchecked")
    public static int @Nullable [] encode(Beardifier b) {
        try {
            BoundingBox box = (BoundingBox) BOX.get(b);
            if (box == null) return null;
            List<Beardifier.Rigid> pieces = (List<Beardifier.Rigid>) PIECES.get(b);
            List<JigsawJunction> junctions = (List<JigsawJunction>) JUNCTIONS.get(b);
            IntArrayList a = new IntArrayList(8 + pieces.size() * 8 + junctions.size() * 3);
            a.add(box.minX());
            a.add(box.minY());
            a.add(box.minZ());
            a.add(box.maxX());
            a.add(box.maxY());
            a.add(box.maxZ());
            a.add(pieces.size());
            a.add(junctions.size());
            for (Beardifier.Rigid r : pieces) {
                BoundingBox pb = r.box();
                a.add(pb.minX());
                a.add(pb.minY());
                a.add(pb.minZ());
                a.add(pb.maxX());
                a.add(pb.maxY());
                a.add(pb.maxZ());
                a.add(r.terrainAdjustment().ordinal()); // NONE, BURY, BEARD_THIN, BEARD_BOX, ENCAPSULATE
                a.add(r.groundLevelDelta());
            }
            for (JigsawJunction j : junctions) {
                a.add(j.getSourceX());
                a.add(j.getSourceGroundY());
                a.add(j.getSourceZ());
            }
            return a.toIntArray();
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }
}
