package dimblend.experience.curse;

import java.util.ArrayList;
import java.util.List;
import dimblend.experience.DimBlend;
import net.minecraft.core.particles.ItemParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentEffectComponents;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingEquipmentChangeEvent;

/**
 * R19/R20 诅咒事件（全走 NeoForge 事件，不需要 mixin）。
 *
 * <p>R19：{@link LivingEquipmentChangeEvent} 在服务端 {@code LivingEntity#tick} 里按装备槽与上一 tick
 * 快照的差异触发，所以玩家从背包里把绑定诅咒盔甲脱下也会命中；{@code from} 非空、{@code to} 为空、
 * 且离开的那件带 {@code PREVENT_ARMOR_CHANGE} 附魔效果（= 绑定诅咒）时给玩家加「诅咒中毒」。</p>
 *
 * <p>R20：{@link LivingDamageEvent.Post} 只在服务端真正结算伤害后触发（{@code getNewDamage() > 0}
 * 才算真正造成伤害，因此火/摔落/中毒等环境伤害也算「受到一次攻击」）。对该玩家四盔甲槽 + 双手上
 * 每件带 {@code PREVENT_EQUIPMENT_DROP}（= 消失诅咒）的装备各掷一次 0.2%：命中就清空该槽
 * （不掉落）、播工具损坏音效并放 5 个物品粒子。创造模式不触发。</p>
 */
@EventBusSubscriber(modid = DimBlend.MODID)
public final class CurseEvents {

    /** R20：消失时播的工具损坏音效。 */
    private static final float VANISH_SOUND_VOLUME = 1.0F;
    private static final float VANISH_SOUND_PITCH = 1.0F;
    /** R20：可选物品粒子数量（用户需求外的小反馈，与音效同一处触发）。 */
    private static final int VANISH_PARTICLE_COUNT = 5;

    @SubscribeEvent
    public static void onEquipmentChanged(LivingEquipmentChangeEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || player.isDeadOrDying()) {
            return;
        }
        ItemStack from = event.getFrom();
        ItemStack to = event.getTo();
        // 1.21.1：绑定诅咒是附魔效果组件，用原版 EnchantmentHelper 判定（与 ArmorSlot#mayPickup 同口径）。
        boolean hasBindingCurse = !from.isEmpty()
                && EnchantmentHelper.has(from, EnchantmentEffectComponents.PREVENT_ARMOR_CHANGE);
        if (!CurseRules.isBindingCurseArmorRemoval(event.getSlot(), from.isEmpty(), to.isEmpty(), hasBindingCurse)) {
            return;
        }
        // 再脱一件：同等级效果 addEffect 走 MobEffectInstance#update，时长刷新到 60 分钟，不叠加。
        player.addEffect(new MobEffectInstance(
                CurseEffects.CURSED_POISON,
                CursedPoisonMobEffect.DURATION_TICKS,
                CursedPoisonMobEffect.AMPLIFIER));
    }

    @SubscribeEvent
    public static void onDamagePost(LivingDamageEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        boolean damageDealt = event.getNewDamage() > 0.0F;
        boolean creative = player.isCreative();
        if (!damageDealt || creative) {
            return;
        }
        List<EquipmentSlot> vanishedSlots = new ArrayList<>();
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            if (!CurseRules.isCursedEquipmentSlot(slot)) {
                continue;
            }
            ItemStack stack = player.getItemBySlot(slot);
            if (stack.isEmpty()) {
                continue;
            }
            // 1.21.1：消失诅咒同样是附魔效果组件，与 Player#dropEquipment 同口径。
            boolean hasVanishingCurse = EnchantmentHelper.has(stack, EnchantmentEffectComponents.PREVENT_EQUIPMENT_DROP);
            if (!hasVanishingCurse) {
                continue;
            }
            // 每件装备各自独立掷骰，互不影响。
            if (CurseRules.shouldVanishOnHit(damageDealt, creative, hasVanishingCurse, player.getRandom().nextDouble())) {
                vanishedSlots.add(slot);
            }
        }
        for (EquipmentSlot slot : vanishedSlots) {
            ItemStack stack = player.getItemBySlot(slot);
            if (stack.isEmpty()) {
                continue;
            }
            // 直接清空，模拟「消失」而不是掉落。
            player.setItemSlot(slot, ItemStack.EMPTY);
            player.serverLevel().playSound(null, player.getX(), player.getY(), player.getZ(),
                    SoundEvents.ITEM_BREAK, SoundSource.PLAYERS, VANISH_SOUND_VOLUME, VANISH_SOUND_PITCH);
            player.serverLevel().sendParticles(new ItemParticleOption(ParticleTypes.ITEM, stack),
                    player.getX(), player.getY() + 1.0D, player.getZ(),
                    VANISH_PARTICLE_COUNT, 0.2D, 0.2D, 0.2D, 0.05D);
        }
    }

    private CurseEvents() {
    }
}
