package dimblend.dragonchase.music;

import dimblend.dragonchase.DimBlendDragonChase;
import dimblend.dragonchase.client.music.ClientChaseMusicController;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

/**
 * Server to client: the chase BGM phase and the track index that phase's event table should play.
 *
 * <p>The server owns the choice (one roll per phase, persisted so a restart resumes the same
 * track); the client owns every audible detail — the loop, the fades and the MUSIC blocking. Fades
 * travel as the {@code crossfade} flag rather than as durations, because the client is the only
 * side that knows whether it was already playing Phase A.
 *
 * <p>Sent on {@code startChase}, the first drop below 50% HP, the killing blow (a single
 * {@code PHASE_END} track), every chase-ending transition, and to each player on login / dimension
 * change. {@link #send} never puts an unregistered payload on the wire: a client without this
 * channel is skipped, exactly like the band-train sync.
 *
 * <p>Wire layout stays {@code [phase ordinal byte][track byte][crossfade bool]}. The phase set grew
 * from three values to four ({@code IDLE 0}, {@code PHASE_A 1}, {@code PHASE_B 2}, {@code PHASE_END
 * 3}), which is still one byte and within {@link ChaseMusicPhase#values()} on both ends — so the
 * registrar version stays {@code "1"}: no field was added, removed or resized. Both ends ship in
 * the same jar (the controller only ever answers a phase this very enum declares), and a decode
 * above the range throws rather than silently aliasing a track table.
 */
public record ChaseMusicStatePayload(ChaseMusicPhase phase, int track, boolean crossfade)
        implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<ChaseMusicStatePayload> TYPE =
            new CustomPacketPayload.Type<>(DimBlendDragonChase.id("chase_music_state"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ChaseMusicStatePayload> STREAM_CODEC =
            new StreamCodec<RegistryFriendlyByteBuf, ChaseMusicStatePayload>() {
                @Override
                public ChaseMusicStatePayload decode(RegistryFriendlyByteBuf buffer) {
                    int ordinal = buffer.readByte();
                    // Covers every current phase ordinal (0..3) without hardcoding a count:
                    // reordering or extending the enum cannot silently map to another phase.
                    if (ordinal < 0 || ordinal >= ChaseMusicPhase.values().length) {
                        throw new DecoderException("ChaseMusicStatePayload phase out of range: " + ordinal);
                    }
                    int track = buffer.readByte();
                    return new ChaseMusicStatePayload(ChaseMusicPhase.byOrdinal(ordinal), track, buffer.readBoolean());
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buffer, ChaseMusicStatePayload payload) {
                    buffer.writeByte(payload.phase.ordinal());
                    buffer.writeByte(payload.track);
                    buffer.writeBoolean(payload.crossfade);
                }
            };

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(ChaseMusicStatePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (FMLEnvironment.dist == Dist.CLIENT) {
                ClientChaseMusicController.accept(payload.phase(), payload.track(), payload.crossfade());
            }
        });
    }

    /** Wiring line for {@code DragonChaseRegistries.registerPayloads}. */
    public static void register(RegisterPayloadHandlersEvent event) {
        event.registrar("1").playToClient(TYPE, STREAM_CODEC, ChaseMusicStatePayload::handle);
    }

    /** The chase phase that is audible now, {@code IDLE} outside {@code ACTIVE}. */
    public static ChaseMusicPhase audiblePhase(ChaseMusicPhase phase, boolean active) {
        return active && phase.isAudible() ? phase : ChaseMusicPhase.IDLE;
    }

    public static void send(ServerPlayer player, ChaseMusicPhase phase, int track, boolean crossfade) {
        if (!NetworkRegistry.hasChannel(player.connection, TYPE.id())) {
            return;
        }
        PacketDistributor.sendToPlayer(player, new ChaseMusicStatePayload(phase, track, crossfade));
    }

    /** Every player in the chase dimension; players elsewhere never hear it, so they are not sent. */
    public static void broadcast(ServerLevel level, ChaseMusicPhase phase, int track, boolean crossfade) {
        for (ServerPlayer player : level.players()) {
            send(player, phase, track, crossfade);
        }
    }
}
