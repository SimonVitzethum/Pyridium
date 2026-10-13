package simon.pyridium.worldgen;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Messwerkzeug (nur mit -Dpyridium.genBench=R): nach dem Serverstart je ein Quadrat von
 * (2R+1)^2 Chunks bis TERRAIN und ein zweites (anderswo) bis FULL erzeugen und die Zeiten loggen;
 * -Dpyridium.genBenchExit=true beendet danach den Server. Vergleich GPU/Vanilla ueber
 * -Dpyridium.gpuWorldgen=false.
 */
public final class WorldgenBench {
    private static final Logger LOG = LoggerFactory.getLogger("pyridium/genbench");

    private WorldgenBench() {
    }

    public static void serverStarted(MinecraftServer server) {
        int r = Integer.getInteger("pyridium.genBench", -1);
        if (r < 0) return;
        ServerLevel level = server.overworld();
        // -Dpyridium.genBenchJfr=true: Minecrafts JFR-Profil (ChunkGeneration-Events je Schritt)
        boolean jfr = Boolean.getBoolean("pyridium.genBenchJfr")
                && net.minecraft.util.profiling.jfr.JvmProfiler.INSTANCE.start(net.minecraft.util.profiling.jfr.Environment.SERVER);
        // Anfragen aus eigenem Thread: auf dem Server-Thread blockiert getChunkFuture je Chunk
        // (managedBlock) und die Messung waere seriell
        // Aufwaermen (JIT, Pipelines): eigenes Gebiet bis FULL, dann messen
        Thread.ofPlatform().name("pyridium-genbench").start(() -> run(server, level, Math.max(4, r / 2), 3000, -3000, ChunkStatus.FULL, () ->
                run(server, level, r, 1250, 1250, ChunkStatus.TERRAIN, () ->
                run(server, level, r, -1250, 1250, ChunkStatus.FULL, () -> {
                    if (jfr) LOG.info("genBench JFR: {}", net.minecraft.util.profiling.jfr.JvmProfiler.INSTANCE.stop());
                    String dump = System.getProperty("pyridium.fullDump");
                    if (dump != null) server.submit(() -> simon.pyridium.worldgen.full.FullDump.dump(level, -1250 / 1, 1250 / 1, Math.min(r, 12), java.nio.file.Path.of(dump))).join();
                    if (Boolean.getBoolean("pyridium.genBenchExit")) server.halt(false);
                }))));
    }

    private static void run(MinecraftServer server, ServerLevel level, int r, int cx, int cz, ChunkStatus status, Runnable then) {
        // -Dpyridium.genBenchSpread=N: Chunks im Abstand N statt dicht (unabhaengige Nachbarschaften)
        int spread = Integer.getInteger("pyridium.genBenchSpread", 1);
        List<net.minecraft.world.level.ChunkPos> positions = new ArrayList<>();
        for (int dz = -r; dz <= r; dz++) for (int dx = -r; dx <= r; dx++)
            positions.add(new net.minecraft.world.level.ChunkPos(cx + dx * spread, cz + dz * spread));
        // Alle Anfragen gleichzeitig (wie viele Spieler/Pre-Generation): dauerhafte Tickets auf dem
        // Server-Thread in einem Rutsch, die Futures nicht blockierend aus diesem Thread. Die Tickets
        // bleiben (nur Messwerkzeug; mit genBenchExit endet der Server danach).
        int ticketLevel = net.minecraft.server.level.ChunkLevel.byStatus(status);
        server.submit(() -> {
            for (var pos : positions)
                level.getChunkSource().addTicket(new net.minecraft.server.level.Ticket(net.minecraft.server.level.TicketType.FORCED, ticketLevel), pos);
        }).join();
        long t0 = System.nanoTime();
        var os = (com.sun.management.OperatingSystemMXBean) java.lang.management.ManagementFactory.getOperatingSystemMXBean();
        long cpu0 = os.getProcessCpuTime();
        List<CompletableFuture<?>> fs = new ArrayList<>();
        for (var pos : positions) fs.add(level.getChunkSource().getChunkFuture(pos.x(), pos.z(), status, true));
        int n = fs.size();
        CompletableFuture.allOf(fs.toArray(CompletableFuture[]::new)).whenComplete((v, e) -> Thread.ofPlatform().start(() -> {
            double s = (System.nanoTime() - t0) / 1e9;
            double cores = (os.getProcessCpuTime() - cpu0) / 1e9 / s;
            long ok = fs.stream().filter(f -> f.join() instanceof net.minecraft.server.level.ChunkResult<?> cr && cr.isSuccess()).count();
            if (ok != n) LOG.warn("genBench {}: nur {} von {} Chunks erfolgreich", status, ok, n);
            LOG.info("genBench {}: {} Chunks in {} s = {} Chunks/s, CPU {} Kerne im Mittel (GPU-Terrain {})", status, n, String.format("%.2f", s),
                    String.format("%.1f", n / s), String.format("%.1f", cores),
                    Boolean.parseBoolean(System.getProperty("pyridium.gpuWorldgen", "true")) ? "an" : "aus");
            // release the tickets so the chunks unload again (flat RAM; unedited GPU chunks are not saved)
            if (!Boolean.getBoolean("pyridium.genBenchKeep"))
                server.submit(() -> {
                    try {
                        var f = net.minecraft.server.level.ServerChunkCache.class.getDeclaredField("ticketStorage");
                        f.setAccessible(true);
                        var storage = (net.minecraft.world.level.TicketStorage) f.get(level.getChunkSource());
                        for (var pos : positions)
                            storage.removeTicket(new net.minecraft.server.level.Ticket(net.minecraft.server.level.TicketType.FORCED, ticketLevel), pos);
                    } catch (ReflectiveOperationException re) {
                        LOG.warn("Tickets nicht freigebbar: {}", re.toString());
                    }
                }).join();
            Runtime rt = Runtime.getRuntime();
            LOG.info("genBench Heap: {} MiB belegt von {} MiB", (rt.totalMemory() - rt.freeMemory()) >> 20, rt.maxMemory() >> 20);
            then.run();
        }));
    }
}
