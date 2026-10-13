package simon.pyridium.worldgen.structures.tools;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.RegistryDataLoader;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.RegistryLayer;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.ServerPacksSource;
import net.minecraft.server.packs.resources.CloseableResourceManager;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import net.minecraft.tags.TagLoader;
import net.minecraft.core.LayeredRegistryAccess;

/** Testwerkzeug: Minecraft-Registries mit Vanilla-Daten ohne Server/Welt hochfahren (nur StructureHarness). */
public final class VanillaBoot {
    public final CloseableResourceManager resources;
    public final RegistryAccess.Frozen registries;
    /** Statische + Worldgen-Registries (Bloecke, Tags ...), wie der Server sie den Strukturen gibt */
    public final RegistryAccess.Frozen fullAccess;

    public VanillaBoot() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        var repo = ServerPacksSource.createVanillaTrustedRepository();
        repo.reload();
        repo.setSelected(repo.getAvailableIds());
        resources = new MultiPackResourceManager(PackType.SERVER_DATA, repo.openAllSelected());
        LayeredRegistryAccess<RegistryLayer> layers = RegistryLayer.createRegistryAccess();
        List<Registry.PendingTags<?>> staticTags = TagLoader.loadTagsForExistingRegistries(resources, layers.getLayer(RegistryLayer.STATIC));
        for (Registry.PendingTags<?> p : staticTags) p.apply();
        RegistryAccess.Frozen worldCtx = layers.getAccessForLoading(RegistryLayer.WORLD);
        List<HolderLookup.RegistryLookup<?>> ctx = TagLoader.buildUpdatedLookups(worldCtx, staticTags);
        registries = RegistryDataLoader.load(resources, ctx, RegistryDataLoader.WORLD_REGISTRIES, Runnable::run).join();
        // Tags der geladenen Registries (Biom-Tags der Strukturen) binden
        List<Registry.PendingTags<?>> pending = new ArrayList<>(TagLoader.loadTagsForExistingRegistries(resources, registries));
        for (Registry.PendingTags<?> p : pending) p.apply();
        fullAccess = layers.replaceFrom(RegistryLayer.WORLD, registries).compositeAccess();
    }
}
