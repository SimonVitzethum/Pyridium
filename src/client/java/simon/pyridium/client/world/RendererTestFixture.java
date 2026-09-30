package simon.pyridium.client.world;

import net.minecraft.client.Minecraft;

/** Explicit opt-in fixture for disposable, windowless integration-test worlds. */
public final class RendererTestFixture {
    private static boolean installed;
    private RendererTestFixture() {}
    public static void reset() { installed = false; }

    public static void tick(Minecraft mc) {
        if (installed || !Boolean.getBoolean("pyridium.headless") || !Boolean.getBoolean("pyridium.testEntities") ||
            mc.level == null || mc.player == null || mc.getSingleplayerServer() == null || !PyritWorld.isReady()) return;
        installed = true;
        var server = mc.getSingleplayerServer();
        server.execute(() -> {
            var source = server.createCommandSourceStack();
            // Wait for all fixture chunks before fill/setblock (teleport loading is asynchronous).
            for (int x = 62; x <= 63; x++) for (int z = 62; z <= 63; z++) server.overworld().getChunk(x, z);
            String[] commands = {
                "execute as @a run tp @s 1000 182 1000 0 10",
                "fill 992 179 994 1008 179 1015 minecraft:stone",
                "fill 992 180 994 1008 190 1015 minecraft:air",
                "kill @e[tag=pyridium_test]",
                "summon minecraft:cow 998 180 1005 {NoAI:1b,NoGravity:1b,PersistenceRequired:1b,Tags:[\"pyridium_test\"]}",
                "summon minecraft:pig 1001 180 1005 {NoAI:1b,NoGravity:1b,PersistenceRequired:1b,Tags:[\"pyridium_test\"]}",
                "summon minecraft:zombie 1004 180 1005 {NoAI:1b,NoGravity:1b,PersistenceRequired:1b,Invulnerable:1b,Tags:[\"pyridium_test\"]}",
                "setblock 999 180 1008 minecraft:chest",
                "time set midnight"
            };
            for (String command : commands) server.getCommands().performPrefixedCommand(source, command);
        });
    }
}
