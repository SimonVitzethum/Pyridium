package simon.pyridium.worldgen.exact;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import net.minecraft.world.level.levelgen.densityfunction.DensitySampler;

/**
 * Pyridium: die echten Oberflaechenregeln (SurfaceRules des Datapacks) fuer die LOD-Spalten. Dieselbe
 * Regelmaschine wie ExactMaterials/wg_exact_surface.cu, aber ohne Chunk-Zustand: wg_lod_surface.cu
 * wertet sie je Voxelspalte an vier Tiefen aus (Kontext: Hoehe, Hang, Biom, Wasser aus der LOD-Spalte).
 * Die Tabellen entstehen einmal beim Laden aus der Registry (keine Generierung auf der CPU).
 */
public final class ExactLodSurface {
    public final List<String> unsupported = new ArrayList<>();
    /** GPU-Puffer: Aufgaben, Parameter, Lagen, Stapel, Segmente, Punktprogramm, Punktparameter, Regeltabelle */
    public final long[] bTask, bParam, bLayer, bStack, bSeg, bCode, bPParam, bSf;
    /** Block-Zustands-IDs, die die Regeln (oder Tonbaender, Standardblock) liefern koennen */
    public final int[] stateIds;

    public ExactLodSurface(GpuCompute gpu, RandomState rs, NoiseGeneratorSettings settings, WorldGenerationContext genCtx, ExactBiomes eb,
                           int minY, int height, long seed) {
        // Wirtsplan nur fuer die Rauschstapel der Regeln (Dichte rechnet das LOD selbst)
        ExactPlan host = ExactPlan.forRoots(List.<DensitySampler>of(rs.getSampler(rs.surfaceSystem().preliminarySurfaceFunction())),
            List.of(new ExactPlan.Vol(1, 1, 1, 0, 0, 0, 1, 1, 1, 0)), false);
        ExactMaterials mats = new ExactMaterials(rs, settings, genCtx, eb, host, host.points);
        mats.prepareSimplex();
        mats.preliminarySurfaceTask = host.tasks.get(host.roots.getInt(0)).offset;
        unsupported.addAll(host.unsupported);
        unsupported.addAll(host.points.unsupported);
        unsupported.addAll(mats.unsupported);
        int[] tasks = host.encodeTasks();
        bTask = gpu.upload(tasks);
        bParam = gpu.upload(host.params.toIntArray());
        bLayer = gpu.upload(host.encodeLayers());
        bStack = gpu.upload(host.encodeStacks());
        bSeg = gpu.upload(host.encodeSegs());
        bCode = gpu.upload(host.points.encodeCode());
        bPParam = gpu.upload(host.points.params.toIntArray());
        int[] sf = mats.encode(minY, height, minY + height - 1, seed);
        bSf = gpu.upload(sf);
        // Zustaende: Regelprogramm [sf[8], sf[9]), Tonbaender (192 ab sf[13]), Standardblock
        IntArrayList ids = new IntArrayList();
        ids.add(sf[0]);
        for (int i = sf[8]; i < sf[9]; ) {
            int op = sf[i];
            if (op == 1) i += 3;                       // R_IF
            else if (op == 2) { ids.add(sf[i + 1]); i += 2; }   // R_BLOCK
            else if (op == 4) i += 2;                  // R_ORE
            else if (op == 5) break;                   // R_END
            else i += 1;                               // R_BANDS
        }
        for (int i = 0; i < 192; i++) ids.add(sf[sf[13] + i]);
        stateIds = new it.unimi.dsi.fastutil.ints.IntLinkedOpenHashSet(ids).toIntArray();
    }
}
