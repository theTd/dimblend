package dimblend.radio.server;

import dimblend.radio.RadioVolumeRule;
import dimblend.radio.RadioVolumeSettings;
import dimblend.radio.api.RadioTuning;
import dimblend.radio.net.RadioTunePayloads;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.JukeboxBlockEntity;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Server side of the jukebox tuning screen: opening it, and applying an edit.
 *
 * <p><b>Everything is re-checked here.</b> The screen's own greying-out of controls is a
 * convenience for the player; the server decides. Two things it refuses: a position where no
 * jukebox is, and a player without the same permission the old panel required. A client can send
 * any {@code BlockPos} it likes, so neither test may live only on the client.
 *
 * <p><b>Why the radio must be running to open it.</b> The requirement says "在唱片机进入电台状态后
 * 右键唱片机打开" — the screen belongs to a radio, so a jukebox that is not one has nothing to
 * tune. Refusing also keeps the screen from covering vanilla's own right-click behaviour on a
 * plain jukebox.
 */
public final class RadioTuneService {

    /** The permission level the old {@code dbx tune} panel required, kept unchanged. */
    public static final int REQUIRED_PERMISSION = 2;

    /** Answers a {@link RadioTunePayloads.Request} by sending the snapshot that opens the screen. */
    public static void open(ServerPlayer player, RadioTunePayloads.Request request) {
        if (!(player.level() instanceof ServerLevel level)) {
            return;
        }
        BlockPos pos = request.pos();
        if (!level.isLoaded(pos) || !(level.getBlockEntity(pos) instanceof JukeboxBlockEntity jukebox)) {
            send(player, request.pos(), "no_jukebox");
            return;
        }
        if (dimblend.radio.RadioState.get(level.dimension().location().toString(), pos).isEmpty()) {
            send(player, request.pos(), "not_a_radio");
            return;
        }
        send(player, pos, "");
    }

    /**
     * Applies one edit and answers with the new snapshot.
     *
     * <p>A value that arrives out of range is refused rather than clamped: the requirements fix a
     * 0..150 range in steps of 10, and silently clamping would let a broken client look correct.
     */
    public static void edit(ServerPlayer player, RadioTunePayloads.Edit edit) {
        if (!(player.level() instanceof ServerLevel level)) {
            return;
        }
        if (!player.hasPermissions(REQUIRED_PERMISSION)) {
            send(player, edit.pos(), "no_permission");
            return;
        }
        if (!(level.getBlockEntity(edit.pos()) instanceof JukeboxBlockEntity jukebox)) {
            send(player, edit.pos(), "no_jukebox");
            return;
        }
        if (RadioTunePayloads.VOLUME_OPTION.equals(edit.option())) {
            applyVolume(level, player, jukebox, edit);
            return;
        }
        applyGlobal(player, level, edit);
    }

    private static void applyVolume(ServerLevel level, ServerPlayer player, JukeboxBlockEntity jukebox,
            RadioTunePayloads.Edit edit) {
        double requested = edit.value();
        if (!Double.isFinite(requested) || requested < 0.0 || requested > RadioVolumeRule.MAX
                || requested != Math.rint(requested) || !RadioVolumeRule.isOnGrid((int) requested)) {
            send(player, edit.pos(), "invalid");
            return;
        }
        RadioVolumeSettings.set(jukebox, (int) requested);
        // The stored value changes what the radio should be playing right now, so the authoritative
        // state has to be recomputed before anyone is told the new number.
        RadioSync.onNeighborChanged(level, edit.pos());
        send(player, edit.pos(), "saved");
    }

    private static void applyGlobal(ServerPlayer player, ServerLevel level, RadioTunePayloads.Edit edit) {
        double value = edit.value();
        boolean valid = switch (edit.option()) {
            case "radio_static" -> RadioTuning.validNoiseVolume(value);
            case "radio_end_voice_frequency" -> RadioTuning.validEndVoiceFrequency(value);
            case "radio_end_voice_volume" -> RadioTuning.validEndVoiceVolume(value);
            case "radio_acoustic_intensity" -> RadioTuning.validIntensity(value);
            default -> false;
        };
        if (!valid) {
            send(player, edit.pos(), "invalid");
            return;
        }
        try {
            switch (edit.option()) {
                case "radio_static" -> RadioTuning.setReceptionNoiseVolume(value);
                case "radio_end_voice_frequency" -> RadioTuning.setEndVoiceFrequency(value);
                case "radio_end_voice_volume" -> RadioTuning.setEndVoiceVolume(value);
                case "radio_acoustic_intensity" -> RadioTuning.setAcousticIntensity(value);
                default -> throw new IllegalArgumentException("unknown radio option " + edit.option());
            }
        } catch (RuntimeException failure) {
            dimblend.radio.DimBlendRadio.LOGGER.error("Could not save radio option {}", edit.option(), failure);
            send(player, edit.pos(), "save_failed");
            return;
        }
        send(player, edit.pos(), "saved");
    }

    /** Sends the whole screen state, with {@code message} as a lang suffix ("" for none). */
    public static void send(ServerPlayer player, BlockPos pos, String message) {
        int volume = player.level() instanceof ServerLevel level ? RadioVolumeSettings.at(level, pos)
                : RadioVolumeRule.DYNAMIC;
        double[] globals = {
                RadioTuning.receptionNoiseVolume(),
                RadioTuning.endVoiceFrequency(),
                RadioTuning.endVoiceVolume(),
                RadioTuning.acousticIntensity()
        };
        PacketDistributor.sendToPlayer(player, new RadioTunePayloads.Snapshot(
                pos, volume, globals, player.hasPermissions(REQUIRED_PERMISSION), message));
    }

    private RadioTuneService() {
    }
}