package dimblend.spyglass.server;

import dimblend.spyglass.DimBlendSpyglass;
import dimblend.spyglass.api.SourceCatalog;
import dimblend.spyglass.net.SpyglassSourcesPayload;
import dimblend.spyglass.structure.SpyglassStructureTags;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Server side of the source switch panel: builds the catalog, checks permission, writes the config
 * and forces scoping players to rebuild.
 *
 * <p>Server thread only (the payload handlers run on the main thread). Reading the catalog is
 * permission-gated too: the panel only opens from {@code /dbx tune} (level 2), but a forged packet
 * is still checked here.
 */
public final class SpyglassSourceService {
    public static void open(ServerPlayer player) {
        if (!player.hasPermissions(2)) {
            sendCatalog(player, "no_permission");
            return;
        }
        sendCatalog(player, "");
    }

    public static void edit(ServerPlayer player, String namespace, boolean enabled) {
        if (!player.hasPermissions(2)) {
            sendCatalog(player, "no_permission");
            return;
        }
        if (!SpyglassSourceFilter.isValidNamespace(namespace)) {
            sendCatalog(player, "invalid_source");
            return;
        }
        List<SourceCatalog.Source> sources = catalog(player.serverLevel());
        boolean known = false;
        for (SourceCatalog.Source source : sources) {
            if (source.namespace().equals(namespace)) {
                known = true;
                break;
            }
        }
        if (!known) {
            sendCatalog(player, "unknown_source");
            return;
        }
        Set<String> disabled = new LinkedHashSet<>(SpyglassServerSettings.disabledSources());
        if (enabled) {
            disabled.remove(namespace);
        } else {
            disabled.add(namespace);
        }
        try {
            SpyglassServerSettings.setDisabledSources(disabled);
        } catch (RuntimeException failure) {
            DimBlendSpyglass.LOGGER.error("Could not save spyglass source settings", failure);
            sendCatalog(player, "save_failed");
            return;
        }
        DimBlendSpyglass.LOGGER.info(
                "Spyglass sources changed: disabled={} by {}",
                SpyglassServerSettings.disabledSources(),
                player.getGameProfile().getName());
        SpyglassServerService.invalidateAll();
        sendCatalog(player, "saved");
    }

    /** The panel rows for this level, in the order the panel shows them. */
    public static List<SourceCatalog.Source> catalog(ServerLevel level) {
        Registry<Structure> registry = level.registryAccess().registryOrThrow(Registries.STRUCTURE);
        List<ResourceLocation> ids = new ArrayList<>();
        for (Holder<Structure> holder : registry.getTagOrEmpty(SpyglassStructureTags.VISIBLE)) {
            ResourceLocation id = registry.getKey(holder.value());
            if (id != null) {
                ids.add(id);
            }
        }
        Set<String> disabled = SpyglassServerSettings.disabledSources();
        return SourceCatalogBuilder.build(ids, disabled::contains, SpyglassSourceService::displayName);
    }

    /** Mod display name for a namespace, or {@code null} when no mod claims that namespace. */
    private static String displayName(String namespace) {
        return ModList.get()
                .getModContainerById(namespace)
                .map(container -> container.getModInfo().getDisplayName())
                .orElse(null);
    }

    private static void sendCatalog(ServerPlayer player, String message) {
        List<SourceCatalog.Source> sources = catalog(player.serverLevel());
        PacketDistributor.sendToPlayer(
                player, new SpyglassSourcesPayload(sources, player.hasPermissions(2), message));
    }

    private SpyglassSourceService() {
    }
}
