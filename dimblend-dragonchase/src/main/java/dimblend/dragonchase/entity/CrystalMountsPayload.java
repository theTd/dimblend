package dimblend.dragonchase.entity;

import dimblend.dragonchase.DimBlendDragonChase;
import dimblend.dragonchase.client.ClientCrystalMounts;
import io.netty.handler.codec.DecoderException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * Server to client: which end crystals ride which car, and on which plot cell.
 *
 * <p><b>Why a payload instead of trusting the entity's own movement.</b> The crystal is a vanilla
 * {@code EndCrystal}, whose entity type sets {@code updateInterval(Integer.MAX_VALUE)}
 * ({@code EntityType.java:311-318}); {@code ServerEntity} only sends a position when that interval
 * elapses, when {@code hasImpulse} is set, or when synced data changes, and even then the client
 * lerps over three ticks ({@code ClientPacketListener#handleMoveEntity}, {@code lerpTo(..., 3)}).
 * At 30 m/s that is a permanent ~1.5-block lag, and the healing beam reads the same coordinates. The
 * car's pose is already known to every client (Sable interpolates its own sub-level), so the mount
 * cell travels explicitly and the client recomputes the crystal's world position every frame from
 * that pose — zero sub-frame lag, and the server hitbox and the visual agree exactly because both
 * use {@code Vec3#atCenterOf(plotPos)} as the local point.
 *
 * <p><b>Plot coordinates, not world coordinates.</b> A plot cell is stable while the car moves, and
 * the client's {@code ClientSubLevel#renderPose(partialTick)} turns it into the frame's world
 * position. Sending world positions would reintroduce one network tick of lag.
 */
public record CrystalMountsPayload(List<Entry> entries) implements CustomPacketPayload {

    /** Server-side bound: three cars x four crystals, with slack for several chases. */
    public static final int MAX_ENTRIES = 128;

    /** One mounted crystal: its entity id, the car (sub-level) it rides, and its plot cell. */
    public record Entry(int entityId, UUID carId, int plotX, int plotY, int plotZ) {
    }

    public static final CustomPacketPayload.Type<CrystalMountsPayload> TYPE =
            new CustomPacketPayload.Type<>(DimBlendDragonChase.id("crystal_mounts"));

    public static final StreamCodec<RegistryFriendlyByteBuf, CrystalMountsPayload> STREAM_CODEC =
            new StreamCodec<RegistryFriendlyByteBuf, CrystalMountsPayload>() {
                @Override
                public CrystalMountsPayload decode(RegistryFriendlyByteBuf buffer) {
                    int count = buffer.readVarInt();
                    if (count < 0 || count > MAX_ENTRIES) {
                        throw new DecoderException("CrystalMountsPayload count out of range: " + count);
                    }
                    List<Entry> entries = new ArrayList<>(count);
                    for (int i = 0; i < count; i++) {
                        entries.add(new Entry(buffer.readVarInt(), buffer.readUUID(),
                                buffer.readVarInt(), buffer.readVarInt(), buffer.readVarInt()));
                    }
                    return new CrystalMountsPayload(entries);
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buffer, CrystalMountsPayload payload) {
                    List<Entry> entries = payload.entries;
                    buffer.writeVarInt(entries.size());
                    for (Entry entry : entries) {
                        buffer.writeVarInt(entry.entityId());
                        buffer.writeUUID(entry.carId());
                        buffer.writeVarInt(entry.plotX());
                        buffer.writeVarInt(entry.plotY());
                        buffer.writeVarInt(entry.plotZ());
                    }
                }
            };

    public CrystalMountsPayload {
        entries = List.copyOf(entries);
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** Registration hook; the module's payload registrar calls this once. */
    public static void register(PayloadRegistrar registrar) {
        registrar.playToClient(TYPE, STREAM_CODEC, CrystalMountsPayload::handle);
    }

    public static void handle(CrystalMountsPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (FMLEnvironment.dist == Dist.CLIENT) {
                ClientCrystalMounts.accept(payload);
            }
        });
    }
}
