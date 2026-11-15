package dimblend.experience.compat.camerapture;

import java.util.UUID;
import me.chrr.camerapture.Camerapture;
import me.chrr.camerapture.item.CameraItem;
import me.chrr.camerapture.net.clientbound.RequestUploadPacket;
import me.chrr.camerapture.picture.ServerPictureStore;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * Camera film rules: durability is the film, and paper only reloads it.
 *
 * <p>A camera has {@link #MAX_DURABILITY} shots, starts empty ({@code DAMAGE = MAX_DAMAGE}), and a
 * shot consumes one point of durability without ever breaking or shrinking the stack. Paper is
 * loaded by right-clicking the camera in an inventory; the camera refuses to be enchanted or
 * repaired by an anvil, crafting grid or grindstone.
 *
 * <p>Setting {@link #CREATIVE_BYPASSES_DURABILITY} to {@code true} restores the old creative-mode
 * behaviour: infinite materials bypass an empty camera and shoot without consuming durability.
 */
public final class CameraFilmRules {
    /** Whether Creative mode bypasses the empty-camera block and the durability drain. */
    public static final boolean CREATIVE_BYPASSES_DURABILITY = false;

    /** Default and maximum camera durability; also the number of loaded shots. */
    public static final int MAX_DURABILITY = 64;

    /** Shots left in {@code stack}: {@code maxDamage - damageValue}, never negative. */
    public static int remainingShots(ItemStack stack) {
        return remainingShots(stack.getMaxDamage(), stack.getDamageValue());
    }

    /** Pure form of {@link #remainingShots(ItemStack)}. */
    public static int remainingShots(int maxDamage, int damageValue) {
        if (maxDamage <= 0) {
            return 0;
        }
        return Math.max(0, maxDamage - Math.max(0, damageValue));
    }

    /**
     * Whether the camera has a shot available. {@code hasInfiniteMaterials} is the player's creative
     * flag; it only matters when {@link #CREATIVE_BYPASSES_DURABILITY} is enabled.
     */
    public static boolean canShoot(int remainingShots, boolean hasInfiniteMaterials) {
        return bypassesDurability(hasInfiniteMaterials) || remainingShots > 0;
    }

    /** Damage after consuming one shot, clamped to {@code maxDamage}; the stack is never broken. */
    public static int consumedDamage(int maxDamage, int damageValue) {
        return Math.min(Math.max(0, maxDamage), Math.max(0, damageValue) + 1);
    }

    /**
     * Damage to pass to vanilla for a camera, clamped so {@code damageValue} never reaches
     * {@code maxDamage}: external damage sources cannot break the item.
     */
    public static int clampedDamage(int maxDamage, int damageValue, int amount) {
        if (amount <= 0 || maxDamage <= 0) {
            return 0;
        }
        int room = maxDamage - Math.max(0, damageValue) - 1;
        return Math.max(0, Math.min(amount, room));
    }

    /**
     * Client viewfinder gate: an empty camera shows the upstream blinking {@code no_paper} text and
     * blocks the shot. Paper in the inventory no longer matters.
     */
    public static boolean canTakePicture(Player player) {
        if (bypassesDurability(player.hasInfiniteMaterials())) {
            return true;
        }
        return filmInCamera(player) > 0;
    }

    /** Shots left in the held camera, or 0 when no camera is held. */
    public static int filmInCamera(Player player) {
        CameraItem.HeldCamera camera = CameraItem.find(player, false);
        return camera == null ? 0 : remainingShots(camera.stack());
    }

    /**
     * Consumes one shot from the held camera; returns {@code false} when it is empty (and not
     * bypassed). Never shrinks or breaks the stack.
     */
    public static boolean consumeShot(Player player, ItemStack stack) {
        if (bypassesDurability(player.hasInfiniteMaterials())) {
            return true;
        }
        int remaining = remainingShots(stack);
        if (remaining <= 0) {
            return false;
        }
        stack.setDamageValue(consumedDamage(stack.getMaxDamage(), stack.getDamageValue()));
        return true;
    }

    /**
     * Replacement for CameraCapture's {@code NewPicturePacket} handler: no inventory paper check or
     * consumption, one camera durability point, and the capture position recorded under the
     * reserved picture UUID so {@code PictureItem.create} can attach it to the finished picture.
     */
    public static void handleNewPicture(ServerPlayer player) {
        CameraItem.HeldCamera camera = CameraItem.find(player, false);
        if (camera == null) {
            return;
        }
        if (!consumeShot(player, camera.stack())) {
            return;
        }
        if (CameraItem.isActive(camera.stack())) {
            player.serverLevel().playSound(
                    null, player, Camerapture.CAMERA_SHUTTER, SoundSource.PLAYERS, 1.0F, 1.0F);
        }
        CameraItem.setActive(camera.stack(), false);
        player.getCooldowns().addCooldown(Camerapture.CAMERA, 60);
        player.swing(camera.hand(), true);
        player.awardStat(Camerapture.PICTURES_TAKEN);
        UUID id = ServerPictureStore.getInstance().reserveId();
        PhotoOriginData.record(id, new PhotoOriginData.Origin(
                player.serverLevel().dimension().location(),
                player.getX(),
                player.getEyeY(),
                player.getZ()));
        Camerapture.NETWORK.sendToClient(player, new RequestUploadPacket(id));
    }

    private static boolean bypassesDurability(boolean hasInfiniteMaterials) {
        return CREATIVE_BYPASSES_DURABILITY && hasInfiniteMaterials;
    }

    private CameraFilmRules() {
    }
}
