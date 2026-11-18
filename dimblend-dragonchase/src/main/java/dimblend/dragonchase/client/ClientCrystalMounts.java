package dimblend.dragonchase.client;

import dev.ryanhcode.sable.api.sublevel.ClientSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dimblend.dragonchase.entity.CrystalMountsPayload;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/**
 * Keeps every mounted end crystal glued to its moving car on the client, every frame.
 *
 * <p><b>Why per frame.</b> The server can only move an entity once a tick (and the vanilla
 * {@code EndCrystal} is worse: {@code updateInterval(Integer.MAX_VALUE)} plus a three-tick client
 * lerp), while the car's pose is interpolated by Sable every frame. Reading
 * {@code ClientSubLevel#renderPose(partialTick)} and writing the crystal's position before entities
 * are rendered puts the crystal model, its hitbox shadow and the dragon's healing beam on the same
 * sub-frame position as the deck — at 30 m/s the un-fixed version trails by over a block.
 *
 * <p><b>{@code AFTER_SKY} is the last moment that is still before entities.</b> The stage fires after
 * the sky and before terrain and entities are drawn, so one write per frame is enough. The client
 * tick hook repeats it at tick end so anything that reads entity positions between frames (particles,
 * TaCZ client-side aiming) also sees the car's position.
 *
 * <p><b>Same local point as the server.</b> The payload carries the <i>plot</i> cell of the crystal;
 * both sides use {@code Vec3#atCenterOf(plotPos)}, matching
 * {@code CarAssembler#plantCrystals} → {@code CarMotion#worldOf} → {@code CarTicker#glueCrystals}.
 * A missing entity or a car that is not loaded is skipped: the next broadcast re-lists it.
 */
public final class ClientCrystalMounts {

    private static volatile List<CrystalMountsPayload.Entry> entries = List.of();

    /** Client thread, from the payload handler. */
    public static void accept(CrystalMountsPayload payload) {
        entries = payload.entries();
    }

    /** Dropped on logout (and on a dimension push that carries no mounts) so stale ids never match. */
    public static void reset() {
        entries = List.of();
    }

    /** Tick-end pass: the same lock at the tick boundary, for readers outside the renderer. */
    public static void onClientTick(ClientTickEvent.Post event) {
        apply(1.0F);
    }

    /** Frame pass, immediately before entities are drawn. */
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_SKY) {
            apply(event.getPartialTick().getGameTimeDeltaPartialTick(false));
        }
    }

    private static void apply(float partialTick) {
        List<CrystalMountsPayload.Entry> snapshot = entries;
        if (snapshot.isEmpty()) {
            return;
        }
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return;
        }
        ClientSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) {
            return;
        }
        for (CrystalMountsPayload.Entry entry : snapshot) {
            Entity entity = level.getEntity(entry.entityId());
            if (!(entity instanceof EndCrystal crystal) || crystal.isRemoved()) {
                continue;
            }
            if (!(container.getSubLevel(entry.carId()) instanceof ClientSubLevel car) || car.isRemoved()) {
                continue;
            }
            Vec3 world = car.renderPose(partialTick).transformPosition(
                    new Vec3(entry.plotX() + 0.5, entry.plotY() + 0.5, entry.plotZ() + 0.5));
            crystal.setPos(world.x, world.y, world.z);
            // The renderers interpolate from xOld/yOld/zOld to x/y/z; making them equal makes every
            // frame's draw land exactly on the pose instead of smoothing toward it.
            crystal.setOldPosAndRot();
        }
    }

    private ClientCrystalMounts() {
    }
}
