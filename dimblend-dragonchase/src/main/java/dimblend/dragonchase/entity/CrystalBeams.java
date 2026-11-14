package dimblend.dragonchase.entity;

import dimblend.dragonchase.DimBlendDragonChase;
import dimblend.dragonchase.client.ClientCrystalBeams;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Server to client: the crystal entity ids that currently heal one dragon.
 *
 * <p>requirements.md §3.4 (decision crystal-heal) allows any number of crystals at any distance and
 * requires a healing beam to <em>each</em> of them. The vanilla renderer only knows the single
 * {@code EnderDragon.nearestCrystal} field, so the list travels explicitly and is drawn by
 * {@code ChaseDragonRenderer}. Sent by {@link ChaseDragon#setHealingCrystals} and refreshed
 * periodically so late-tracking players get the beams too.
 */
public record CrystalBeams(int dragonId, int[] crystalIds) implements CustomPacketPayload {
    /** Server-side bound: a crystal car carries at most four, several cars plus slack. */
    public static final int MAX_CRYSTALS = 64;

    public static final CustomPacketPayload.Type<CrystalBeams> TYPE =
            new CustomPacketPayload.Type<>(DimBlendDragonChase.id("crystal_beams"));

    public static final StreamCodec<RegistryFriendlyByteBuf, CrystalBeams> STREAM_CODEC =
            new StreamCodec<RegistryFriendlyByteBuf, CrystalBeams>() {
                @Override
                public CrystalBeams decode(RegistryFriendlyByteBuf buffer) {
                    int dragonId = buffer.readVarInt();
                    int count = buffer.readVarInt();
                    if (count < 0 || count > MAX_CRYSTALS) {
                        throw new DecoderException("CrystalBeams count out of range: " + count);
                    }
                    int[] ids = new int[count];
                    for (int i = 0; i < count; i++) {
                        ids[i] = buffer.readVarInt();
                    }
                    return new CrystalBeams(dragonId, ids);
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buffer, CrystalBeams payload) {
                    int[] ids = payload.crystalIds;
                    buffer.writeVarInt(payload.dragonId);
                    buffer.writeVarInt(ids.length);
                    for (int id : ids) {
                        buffer.writeVarInt(id);
                    }
                }
            };

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(CrystalBeams payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (FMLEnvironment.dist == Dist.CLIENT) {
                ClientCrystalBeams.accept(payload);
            }
        });
    }
}
