package dimblend.mixin;

import dimblend.DimBlendRegistries;
import dimblend.client.ClientBandLane;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Optional cosmetic compat with Biome Notifier (client-only mod): while the
 * player stands in an underground slice band of the rotating dimension, the
 * biome toast gets a "地下" suffix (e.g. 平原 → 平原地下). The base name is a
 * translatable built inside {@code displayBiomeNotification}; the chained
 * {@code withStyle} runs after our redirect, so the appended sibling inherits
 * the biome colour.
 *
 * <p>{@code @Pseudo} keeps environments without biomnotifier safe (the whole
 * mixin is skipped when the target class is absent). {@code require = 0} is a
 * deliberate deviation from the global defaultRequire: a future biomnotifier
 * version that renames the method or inlines the translatable call must
 * degrade silently, never crash the game over a cosmetic suffix.
 */
@Pseudo
@Mixin(targets = "com.hicham.biomnotifier.BiomeNotifierMod", remap = false)
public abstract class BiomeNotifierMixin {
    @Redirect(
            method = "displayBiomeNotification"
                    + "(Lnet/minecraft/resources/ResourceLocation;"
                    + "Lcom/hicham/biomnotifier/ModConfig$ModConfigData;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/network/chat/Component;"
                            + "translatable(Ljava/lang/String;)"
                            + "Lnet/minecraft/network/chat/MutableComponent;"),
            require = 0)
    private MutableComponent dimblend$appendUndergroundSuffix(String translationKey) {
        MutableComponent name = Component.translatable(translationKey);
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null
                && mc.level.dimension() == DimBlendRegistries.ROTATING_LEVEL
                && ClientBandLane.underground()) {
            name.append(Component.translatable("dimblend.biome_suffix.underground"));
        }
        return name;
    }
}
