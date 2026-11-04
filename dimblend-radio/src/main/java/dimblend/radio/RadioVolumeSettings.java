package dimblend.radio;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.JukeboxBlockEntity;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

/**
 * The per-jukebox volume setting the user asked for on 2026-11-03.
 *
 * <p><b>Why per block, not one world value.</b> The requirement says the setting belongs to "电台
 * tune", and tune is opened by right-clicking one particular jukebox: a player tuning one radio
 * in a station must not retune every other radio on the map. So the value is stored on the
 * jukebox block entity itself and travels with the block.
 *
 * <p><b>Why an attachment and not a custom block entity.</b> The block is vanilla's
 * {@code minecraft:jukebox}; replacing it would break every vanilla disc and every other mod's
 * jukebox integration. NeoForge attachments exist exactly for adding per-instance data to a
 * block entity that is not ours, and {@code BlockEntity} is an {@code IAttachmentHolder}.
 *
 * <p><b>Why the pairing rule below.</b> A stored value that is off the 10% grid (an old save, a
 * hand-edited NBT) is snapped rather than rejected — see {@link RadioVolumeRule#clampToStep}.
 * Rejecting it would silently mute a radio the player can still see is on.
 */
public final class RadioVolumeSettings {

    public static final DeferredRegister<AttachmentType<?>> ATTACHMENTS =
            DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, DimBlendRadio.MODID);

    /**
     * The stored volume in whole percent: {@link RadioVolumeRule#DYNAMIC} (0) or 10..150.
     * Serialized, so it survives a save and a chunk unload.
     */
    public static final DeferredHolder<AttachmentType<?>, AttachmentType<Integer>> VOLUME =
            ATTACHMENTS.register("jukebox_volume", () -> AttachmentType
                    .builder(() -> RadioVolumeRule.DYNAMIC)
                    .serialize(com.mojang.serialization.Codec.INT)
                    .build());

    /** The stored value, snapped to the grid, for one jukebox. */
    public static int get(JukeboxBlockEntity jukebox) {
        return RadioVolumeRule.clampToStep(jukebox.getData(VOLUME));
    }

    /** Stores a value for one jukebox. The caller has already validated the permission. */
    public static void set(JukeboxBlockEntity jukebox, int percent) {
        jukebox.setData(VOLUME, RadioVolumeRule.clampToStep(percent));
        jukebox.setChanged();
    }

    /**
     * The stored value for the jukebox at {@code pos}, or {@link RadioVolumeRule#DYNAMIC} when
     * there is no jukebox there.
     *
     * <p>The server asks this whenever it writes a radio state entry, so a missing block entity
     * has to read as "dynamic" rather than throw: a jukebox can be broken between the redstone
     * change and this read.
     */
    public static int at(ServerLevel level, BlockPos pos) {
        return level.getBlockEntity(pos) instanceof JukeboxBlockEntity jukebox
                ? get(jukebox) : RadioVolumeRule.DYNAMIC;
    }

    private RadioVolumeSettings() {
    }
}