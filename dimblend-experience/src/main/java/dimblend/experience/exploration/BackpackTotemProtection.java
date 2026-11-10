package dimblend.experience.exploration;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

/** One actual death: exchange one inventory totem before drops, retain XP and the S.O.L record. */
public final class BackpackTotemProtection {
    private static final String KEY = "dimblend_experience:backpack_totem_death";
    private static final ResourceLocation POWDER =
            ResourceLocation.fromNamespaceAndPath("kaleidoscope_grilling", "totem_powder");

    /** Called only after NeoForge has accepted death, before vanilla drops/clears the inventory. */
    public static void begin(ServerPlayer player) {
        if (active(player)) return;
        // No substitute item is invented when the optional content mod is absent.
        if (!BuiltInRegistries.ITEM.containsKey(POWDER)) return;
        var inventory = player.getInventory();
        for (int slot = 0; slot < inventory.items.size(); slot++) {
            ItemStack stack = inventory.items.get(slot);
            if (!stack.is(Items.TOTEM_OF_UNDYING)) continue;
            CompoundTag death = new CompoundTag();
            death.putInt("level", player.experienceLevel);
            death.putFloat("progress", player.experienceProgress);
            death.putInt("total", player.totalExperience);
            player.getPersistentData().put(KEY, death);
            ItemStack powder = new ItemStack(BuiltInRegistries.ITEM.get(POWDER));
            if (stack.getCount() == 1) {
                inventory.items.set(slot, powder);
            } else {
                stack.shrink(1);
                // Inventory.add discards uninserted items for creative players. This is
                // exactly one powder: resolve real capacity before transferring it.
                int powderSlot = inventory.getSlotWithRemainingSpace(powder);
                if (powderSlot == -1) powderSlot = inventory.getFreeSlot();
                if (powderSlot == -1) {
                    // Wait for death-drop capture; an early toss bypasses its cancellation.
                    death.putBoolean("overflow_powder", true);
                } else {
                    ItemStack destination = inventory.getItem(powderSlot);
                    if (destination.isEmpty()) {
                        inventory.setItem(powderSlot, powder);
                        destination = powder;
                    } else {
                        destination.grow(1);
                    }
                    destination.setPopTime(5);
                }
            }
            inventory.setChanged();
            return;
        }
    }

    public static boolean active(Player player) {
        return player.getPersistentData().contains(KEY, CompoundTag.TAG_COMPOUND);
    }

    /** Add overflow through the ordinary player drop/capture pipeline in the early drop listener. */
    public static void collectOverflowPowder(LivingDropsEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || !active(player)) return;
        CompoundTag death = player.getPersistentData().getCompound(KEY);
        if (!death.getBoolean("overflow_powder")) return;
        death.remove("overflow_powder");
        var previous = player.captureDrops(event.getDrops());
        try {
            player.drop(new ItemStack(BuiltInRegistries.ITEM.get(POWDER)), true, false);
        } finally {
            player.captureDrops(previous);
        }
    }

    /** The old-player marker remains through every Clone listener, including S.O.L's. */
    public static boolean restore(PlayerEvent.Clone event) {
        if (!event.isWasDeath() || !active(event.getOriginal())) return false;
        restoreExperience(event.getOriginal(), event.getEntity());
        return true;
    }

    /**
     * Finalize after all Clone listeners. The snapshot, rather than the corpse's current
     * XP or a listener's replacement values, is authoritative for a protected death.
     * A failed clone leaves the original marker intact so a retry cannot consume twice.
     */
    public static void finishClone(ServerPlayer original, ServerPlayer replacement, boolean keepEverything) {
        if (!keepEverything && active(original)) restoreExperience(original, replacement);
        clear(original);
        // Some mods copy all persistent data; never let a death marker reach the next life.
        clear(replacement);
    }

    private static void restoreExperience(Player original, Player player) {
        CompoundTag death = original.getPersistentData().getCompound(KEY);
        player.experienceLevel = death.getInt("level");
        player.experienceProgress = death.getFloat("progress");
        player.totalExperience = death.getInt("total");
    }

    public static void clear(Player player) {
        player.getPersistentData().remove(KEY);
    }

    private BackpackTotemProtection() {}
}
