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
import net.minecraft.world.level.GameType;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingEquipmentChangeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

/**
 * 两条诅咒的服务端判定（全走 NeoForge 事件与网络 payload，单个客户端 mixin 只负责上报点击）。
 *
 * <p><b>绑定诅咒</b>：{@link LivingEquipmentChangeEvent} 在服务端 {@code LivingEntity#tick} 里按装备槽
 * 与上一 tick 快照的差异触发，所以从背包里把绑定诅咒装备脱下也会命中。三个条件同时成立才挂效果：
 * 离开槽位的那件带 {@code PREVENT_ARMOR_CHANGE}、槽位变空、身上没有别的绑定诅咒装备；时长 72000 tick。
 * 反向：换上来的那件带绑定诅咒就立刻解除。切创造模式（{@link PlayerEvent.PlayerChangeGameModeEvent}）
 * 也解除。死亡不持久化，新 {@code ServerPlayer} 不复制效果，照常清空。</p>
 *
 * <p><b>消失诅咒</b>：触发从"受到伤害"改成"客户端每次左/右键物理按下"，见
 * {@link CurseClickPayload} 与 {@code dimblend.experience.mixin.client.MouseClickMixin}。
 * 服务端对四盔甲槽 + 双手上每件带 {@code PREVENT_EQUIPMENT_DROP} 的装备各掷一次 0.05%：
 * 命中就清空该槽（不掉落）、同时播工具损坏音效与唤魔者"准备变羊"音效、放 5 个物品粒子。
 * 创造模式不掷骰。</p>
 */
@EventBusSubscriber(modid = DimBlend.MODID)
public final class CurseEvents {

    /** 消失时的音效音量/音高：两条音效同一组参数。 */
    private static final float VANISH_SOUND_VOLUME = 1.0F;
    private static final float VANISH_SOUND_PITCH = 1.0F;
    /** 可选物品粒子数量（用户需求外的小反馈，与音效同一处触发）。 */
    private static final int VANISH_PARTICLE_COUNT = 5;

    @SubscribeEvent
    public static void onEquipmentChanged(LivingEquipmentChangeEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || player.isDeadOrDying()) {
            return;
        }
        ItemStack from = event.getFrom();
        ItemStack to = event.getTo();
        // 1.21.1：绑定诅咒是附魔效果组件，用原版 EnchantmentHelper 判定（与 ArmorSlot#mayPickup 同口径）。
        boolean toHasBindingCurse = !to.isEmpty()
                && EnchantmentHelper.has(to, EnchantmentEffectComponents.PREVENT_ARMOR_CHANGE);
        if (CurseRules.shouldClearBindingCurseOnEquip(to.isEmpty(), toHasBindingCurse)) {
            player.removeEffect(CurseEffects.BINDING_CURSE);
            return;
        }
        boolean fromHasBindingCurse = !from.isEmpty()
                && EnchantmentHelper.has(from, EnchantmentEffectComponents.PREVENT_ARMOR_CHANGE);
        boolean wearsOtherBindingCurse = wearsOtherBindingCurse(player, event.getSlot());
        if (!CurseRules.shouldApplyBindingCurseOnRemoval(
                from.isEmpty(), to.isEmpty(), fromHasBindingCurse, wearsOtherBindingCurse)) {
            return;
        }
        // 再脱一件：同等级效果 addEffect 走 MobEffectInstance#update，时长刷新到 60 分钟，不叠加。
        player.addEffect(new MobEffectInstance(
                CurseEffects.BINDING_CURSE,
                BindingCurseMobEffect.DURATION_TICKS,
                BindingCurseMobEffect.AMPLIFIER));
    }

    @SubscribeEvent
    public static void onGameModeChanged(PlayerEvent.PlayerChangeGameModeEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        if (event.getNewGameMode() == GameType.CREATIVE) {
            player.removeEffect(CurseEffects.BINDING_CURSE);
        }
    }

    /**
     * 消失诅咒的一次点击结算：由 {@link CurseClickPayload} 在服务端主线程调用。
     * 每件带消失诅咒的装备独立掷骰，互不影响。
     */
    public static void rollVanishingOnClick(ServerPlayer player) {
        if (player.isCreative()) {
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
            if (CurseRules.shouldVanishOnClick(player.isCreative(), hasVanishingCurse, player.getRandom().nextDouble())) {
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
            player.serverLevel().playSound(null, player.getX(), player.getY(), player.getZ(),
                    SoundEvents.EVOKER_PREPARE_WOLOLO, SoundSource.PLAYERS, VANISH_SOUND_VOLUME, VANISH_SOUND_PITCH);
            player.serverLevel().sendParticles(new ItemParticleOption(ParticleTypes.ITEM, stack),
                    player.getX(), player.getY() + 1.0D, player.getZ(),
                    VANISH_PARTICLE_COUNT, 0.2D, 0.2D, 0.2D, 0.05D);
        }
    }

    /** 除本次变化的槽位之外，身上是否还穿着/拿着别的绑定诅咒装备。 */
    private static boolean wearsOtherBindingCurse(ServerPlayer player, EquipmentSlot changedSlot) {
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            if (slot == changedSlot) {
                continue;
            }
            ItemStack worn = player.getItemBySlot(slot);
            if (!worn.isEmpty()
                    && EnchantmentHelper.has(worn, EnchantmentEffectComponents.PREVENT_ARMOR_CHANGE)) {
                return true;
            }
        }
        return false;
    }

    private CurseEvents() {
    }
}
