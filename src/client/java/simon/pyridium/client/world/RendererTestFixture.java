package simon.pyridium.client.world;

import net.minecraft.client.Minecraft;

/** Explicit opt-in fixture for disposable, windowless integration-test worlds. */
public final class RendererTestFixture {
    private static boolean installed;
    private static long installedAt;
    private static int lifecycleStage;
    private static int spectatorStage;
    private static int settingsStage;
    // Survive native context rebuilds during this explicit one-shot test.
    private static int featureStage;
    private static long featureTestStart;
    private RendererTestFixture() {}
    public static void reset() { installed = false; installedAt = 0; lifecycleStage = 0; spectatorStage = 0; settingsStage = 0; }

    public static void tick(Minecraft mc) {
        if (!Boolean.getBoolean("pyridium.headless") || !Boolean.getBoolean("pyridium.testEntities") ||
            mc.level == null || mc.player == null || mc.getSingleplayerServer() == null || !PyritWorld.isReady()) return;
        if (installed) {
            long elapsed = System.currentTimeMillis() - installedAt;
            if (Boolean.getBoolean("pyridium.testRendererToggles")) {
                if (featureTestStart == 0) {
                    featureTestStart = installedAt;
                    // Persist only in the disposable test run directory, then exercise
                    // the same settings API used by the actual GUI callbacks.
                    for (var feature : RendererSettings.Feature.values())
                        RendererSettings.enabled(feature, RendererSettings.enabled(feature));
                    for (String key : new String[]{"rayReconstruction", "rayEffects", "pathTracing", "hardwareRaytracing"})
                        System.clearProperty("pyridium." + key);
                }
                long featureElapsed = System.currentTimeMillis() - featureTestStart;
                if (featureStage == 0 && featureElapsed >= 20000) {
                    featureStage = 1; RendererSettings.enabled(RendererSettings.Feature.RAY_RECONSTRUCTION, false);
                } else if (featureStage == 1 && featureElapsed >= 35000) {
                    featureStage = 2; RendererSettings.enabled(RendererSettings.Feature.PATH_TRACING, false);
                } else if (featureStage == 2 && featureElapsed >= 50000) {
                    featureStage = 3; RendererSettings.enabled(RendererSettings.Feature.RAY_EFFECTS, false);
                } else if (featureStage == 3 && featureElapsed >= 65000) {
                    featureStage = 4;
                    RendererSettings.enabled(RendererSettings.Feature.RAY_RECONSTRUCTION, true);
                    RendererSettings.enabled(RendererSettings.Feature.PATH_TRACING, true);
                    RendererSettings.enabled(RendererSettings.Feature.RAY_EFFECTS, true);
                } else if (featureStage == 4 && featureElapsed >= 80000) {
                    featureStage = 5;
                    RendererSettings.enabled(RendererSettings.Feature.RAY_RECONSTRUCTION, false);
                    RendererSettings.enabled(RendererSettings.Feature.PATH_TRACING, false);
                    RendererSettings.enabled(RendererSettings.Feature.RAY_EFFECTS, false);
                    RendererSettings.enabled(RendererSettings.Feature.HARDWARE_RT, false);
                } else if (featureStage == 5 && featureElapsed >= 100000) {
                    featureStage = 6;
                    RendererSettings.enabled(RendererSettings.Feature.RAY_RECONSTRUCTION, true);
                    RendererSettings.enabled(RendererSettings.Feature.PATH_TRACING, true);
                    RendererSettings.enabled(RendererSettings.Feature.RAY_EFFECTS, true);
                    RendererSettings.enabled(RendererSettings.Feature.HARDWARE_RT, true);
                    org.slf4j.LoggerFactory.getLogger("RendererTestFixture").info("Pyridium: Renderer-Schalter wiederhergestellt");
                }
            }
            if (Boolean.getBoolean("pyridium.testVideoSettings")) {
                if (settingsStage == 0 && elapsed >= 12000) {
                    settingsStage = 1;
                    mc.gui.setScreen(new net.minecraft.client.gui.screens.options.VideoSettingsScreen(null, mc, mc.options));
                } else if (settingsStage == 1) {
                    settingsStage = 2;
                    mc.gui.setScreen(null);
                    org.slf4j.LoggerFactory.getLogger("RendererTestFixture").info("Pyridium: Grafikeinstellungen fensterlos geoeffnet und geschlossen");
                }
            }
            if (Boolean.getBoolean("pyridium.testSpectator")) {
                var server = mc.getSingleplayerServer();
                if (spectatorStage == 0 && elapsed >= 25000) {
                    spectatorStage = 1;
                    server.execute(() -> server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "gamemode spectator @a"));
                } else if (spectatorStage == 1 && elapsed >= 45000) {
                    spectatorStage = 2;
                    server.execute(() -> {
                        var source = server.createCommandSourceStack();
                        String[] cavity = {
                            "fill 992 64 992 1008 83 1015 minecraft:stone",
                            "fill 994 66 994 1006 81 1013 minecraft:air",
                            "fill 994 81 994 1006 81 1013 minecraft:glowstone",
                            "setblock 1000 67 1006 minecraft:gold_block",
                            "execute as @a run tp @s 1000.5 69 998.5 0 10"
                        };
                        for (String command : cavity) server.getCommands().performPrefixedCommand(source, command);
                        org.slf4j.LoggerFactory.getLogger("RendererTestFixture").info("Pyridium: Spectator-Testkamera im geschlossenen unterirdischen Hohlraum");
                    });
                } else if (spectatorStage == 2 && elapsed >= 70000) {
                    spectatorStage = 3;
                    System.clearProperty("pyridium.spectatorCache"); // only a disposable test override
                    server.execute(() -> {
                        var source = server.createCommandSourceStack();
                        server.getCommands().performPrefixedCommand(source, "execute as @a run tp @s 1000 182 1000 0 10");
                        server.getCommands().performPrefixedCommand(source, "gamemode creative @a");
                    });
                }
            }
            if (Boolean.getBoolean("pyridium.testEntityLifecycle")) {
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
