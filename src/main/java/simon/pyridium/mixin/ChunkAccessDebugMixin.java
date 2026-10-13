package simon.pyridium.mixin;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Diagnosis (-Dpyridium.fullDebug=true): who marks chunks unsaved (counted per caller, logged at shutdown). */
@Mixin(ChunkAccess.class)
public abstract class ChunkAccessDebugMixin {
    private static final boolean DEBUG = Boolean.getBoolean("pyridium.fullDebug");
    private static final Map<String, AtomicInteger> CALLERS = new ConcurrentHashMap<>();

    static {
        if (DEBUG) {
            Thread t = new Thread(() -> {
                var log = org.slf4j.LoggerFactory.getLogger("pyridium/full-debug");
                for (; ; ) {
                    try {
                        Thread.sleep(4000);
                    } catch (InterruptedException e) {
                        return;
                    }
                    CALLERS.entrySet().stream().sorted((a, b) -> b.getValue().get() - a.getValue().get()).limit(6)
                            .forEach(e -> log.info("markUnsaved {}x von {}", e.getValue().get(), e.getKey()));
                }
            }, "pyridium-debug");
            t.setDaemon(true);
            t.start();
        }
    }

    @Inject(method = "markUnsaved", at = @At("HEAD"))
    private void pyridium$trace(CallbackInfo ci) {
        if (!DEBUG) return;
        StackWalker.getInstance().walk(s -> s.skip(1).limit(4).map(f -> f.getClassName().substring(f.getClassName().lastIndexOf('.') + 1) + "." + f.getMethodName()).toList())
                .stream().reduce((a, b) -> a + " < " + b).ifPresent(k -> CALLERS.computeIfAbsent(k, x -> new AtomicInteger()).incrementAndGet());
    }
}
