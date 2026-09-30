package simon.pyridium.client.world;

import net.minecraft.client.Minecraft;

/** Explicit opt-in fixture for disposable, windowless integration-test worlds. */
public final class RendererTestFixture {
    private static boolean installed;
    private static long installedAt;
    private static int lifecycleStage;
    private RendererTestFixture() {}
    public static void reset() { installed = false; installedAt = 0; lifecycleStage = 0; }

    public static void tick(Minecraft mc) {
        if (!Boolean.getBoolean("pyridium.headless") || !Boolean.getBoolean("pyridium.testEntities") ||
            mc.level == null || mc.player == null || mc.getSingleplayerServer() == null || !PyritWorld.isReady()) return;
        if (installed) {
            if (Boolean.getBoolean("pyridium.testEntityLifecycle")) {
                long elapsed = System.currentTimeMillis() - installedAt;
                var server = mc.getSingleplayerServer();
                if (lifecycleStage == 0 && elapsed >= 20000) {
                    lifecycleStage = 1;
                    server.execute(() -> server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "kill @e[tag=pyridium_test]"));
                } else if (lifecycleStage == 1 && elapsed >= 40000) {
                    lifecycleStage = 2;
                    server.execute(() -> server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),
                        "summon minecraft:pig 1001 180 1005 {NoAI:1b,NoGravity:1b,PersistenceRequired:1b,Tags:[\"pyridium_test\"]}"));
                }
            }
            return;
        }
        installed = true;
        installedAt = System.currentTimeMillis();
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
            if (Boolean.getBoolean("pyridium.testMaterials")) {
                String[] materials = {
                    "fill 995 180 1010 995 183 1010 minecraft:oak_leaves[persistent=true]",
                    "setblock 997 180 1010 minecraft:oak_log[axis=x]",
                    "setblock 998 180 1010 minecraft:oak_log[axis=y]",
                    "setblock 1000 180 1010 minecraft:oak_log[axis=z]",
                    "setblock 1002 180 1010 minecraft:iron_block",
                    "setblock 1003 180 1010 minecraft:gold_block",
                    "setblock 1005 180 1010 minecraft:grass_block",
                    "setblock 1006 180 1010 minecraft:copper_block"
                };
                for (String command : materials) server.getCommands().performPrefixedCommand(source, command);
            }
            if (Boolean.getBoolean("pyridium.testEntityFeatures")) {
                String[] features = {
                    "summon minecraft:item 999 181 1003 {NoGravity:1b,PickupDelay:32767s,Item:{id:\"minecraft:diamond\",count:1},Tags:[\"pyridium_test\"]}",
                    "summon minecraft:item 1002 181 1003 {NoGravity:1b,PickupDelay:32767s,Item:{id:\"minecraft:grass_block\",count:1},Tags:[\"pyridium_test\"]}",
                    "item replace entity @e[type=minecraft:zombie,tag=pyridium_test,limit=1] weapon.mainhand with minecraft:diamond_sword",
                    "item replace entity @e[type=minecraft:zombie,tag=pyridium_test,limit=1] armor.head with minecraft:diamond_helmet",
                    "summon minecraft:dragon_fireball 997 182 1004 {NoGravity:1b,Motion:[0.0d,0.0d,0.0d],Tags:[\"pyridium_test\"]}",
                    "summon minecraft:experience_orb 1000 181 1004 {NoGravity:1b,Value:5s,Tags:[\"pyridium_test\"]}"
                };
                for (String command : features) server.getCommands().performPrefixedCommand(source, command);
            }
        });
    }
}
