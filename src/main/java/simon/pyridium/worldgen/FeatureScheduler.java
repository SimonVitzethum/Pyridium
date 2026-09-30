package simon.pyridium.worldgen;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import net.minecraft.util.Util;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Parallele FEATURES-Stufe. Vanilla fuehrt sie synchron auf dem seriellen Worldgen-Dispatcher aus,
 * damit sich Features benachbarter Chunks nicht ins Gehege kommen. Hier laeuft jeder Schritt auf
 * dem Hintergrund-Executor, sperrt aber vorher alles, was er schreibt: den 3x3-Bereich um den
 * Chunk (blockStateWriteRadius 1) und jeden Strukturstart, den der Chunk referenziert (Teile
 * aendern beim Platzieren eigene Felder). Nicht ueberlappende Schritte laufen gleichzeitig; das
 * Ergebnis entspricht einer moeglichen Vanilla-Reihenfolge (die ist auch dort nicht festgelegt).
 *
 * <p>Experimentell, standardmaessig aus (parallelFeatures=true in der Server-Config): gemessen
 * 427-504 statt ~250 fertige Chunks/s, ein moeglicher Haenger ist noch nicht ausgeschlossen.
 */
public final class FeatureScheduler {
    private static final Logger LOG = LoggerFactory.getLogger("pyridium/features");
    private static volatile boolean enabled = Boolean.getBoolean("pyridium.parallelFeatures");
    private static final ThreadLocal<Boolean> BYPASS = ThreadLocal.withInitial(() -> false);
    private static final Executor EXECUTOR = Util.backgroundExecutor().forName("features");
    private static final Set<Long> lockedChunks = new HashSet<>();
    private static final Set<Object> lockedStructures = new HashSet<>();
    private static final ArrayDeque<Job> waiting = new ArrayDeque<>();
    private static final AtomicLong started = new AtomicLong(), maxParallel = new AtomicLong();
    private static int running;

    private record StructureKey(Object structure, long startChunk) {
    }

    private record Job(long[] chunks, Set<Object> structures, Supplier<ChunkAccess> work, CompletableFuture<ChunkAccess> result) {
    }

    private FeatureScheduler() {
    }

    public static void configure(boolean on) {
        String prop = System.getProperty("pyridium.parallelFeatures");
        enabled = prop != null ? Boolean.parseBoolean(prop) : on;
        LOG.info(enabled ? "Features laufen parallel (nicht ueberlappende 3x3-Bereiche)" : "Features laufen seriell wie Vanilla");
    }

    public static boolean bypassed() {
        return !enabled || BYPASS.get();
    }

    /** FEATURES-Schritt fuer chunk parallel einplanen; work fuehrt Vanillas Schritt aus. */
    public static CompletableFuture<ChunkAccess> submit(ChunkAccess chunk, Supplier<ChunkAccess> work) {
        ChunkPos pos = chunk.getPos();
        long[] keys = new long[9];
        int i = 0;
        for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++) keys[i++] = ChunkPos.pack(pos.x() + dx, pos.z() + dz);
        Set<Object> structs = new HashSet<>();
        chunk.getAllReferences().forEach((structure, starts) -> starts.forEach(s -> structs.add(new StructureKey(structure, s))));
        Supplier<ChunkAccess> bypassed = () -> {
            BYPASS.set(true);
            try {
                return work.get();
            } finally {
                BYPASS.set(false);
            }
        };
        Job job = new Job(keys, structs, bypassed, new CompletableFuture<>());
        synchronized (FeatureScheduler.class) {
            if (free(job)) start(job);
            else waiting.add(job);
        }
        return job.result;
    }

    private static boolean free(Job j) {
        for (long k : j.chunks) if (lockedChunks.contains(k)) return false;
        for (Object s : j.structures) if (lockedStructures.contains(s)) return false;
        return true;
    }

    /** Unter Monitor: Sperren nehmen und ausfuehren. */
    private static void start(Job j) {
        for (long k : j.chunks) lockedChunks.add(k);
        lockedStructures.addAll(j.structures);
        running++;
        maxParallel.accumulateAndGet(running, Math::max);
        started.incrementAndGet();
        CompletableFuture.supplyAsync(j.work, EXECUTOR).whenComplete((c, e) -> {
            synchronized (FeatureScheduler.class) {
                for (long k : j.chunks) lockedChunks.remove(k);
                lockedStructures.removeAll(j.structures);
                running--;
                // Wartende in Ankunftsreihenfolge starten, sobald ihre Bereiche frei sind
                for (Iterator<Job> it = waiting.iterator(); it.hasNext(); ) {
                    Job w = it.next();
                    if (free(w)) {
                        it.remove();
                        start(w);
                    }
                }
            }
            if (e != null) j.result.completeExceptionally(e);
            else j.result.complete(c);
        });
    }

    public static void logStats() {
        if (started.get() > 0) LOG.info("Features: {} Schritte parallel ausgefuehrt, hoechstens {} gleichzeitig", started.get(), maxParallel.get());
    }
}
