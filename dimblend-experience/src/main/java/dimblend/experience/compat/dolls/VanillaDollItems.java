package dimblend.experience.compat.dolls;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/** Use the upstream vanilla collection, excluding donor/entity/custom collections. */
public final class VanillaDollItems {
    private static final TagKey<Item> VANILLA_DOLLS = TagKey.create(Registries.ITEM,
            ResourceLocation.fromNamespaceAndPath("kaleidoscope_doll", "vanilla_dolls"));

    public static boolean contains(ItemStack stack) {
        return stack.is(VANILLA_DOLLS);
    }

    private VanillaDollItems() {}
}
