package dimblend.experience.compat.gunsmith;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;

/**
 * 「配件当场损坏」与扩容弹匣换弹耐久写回的统一运行时实现。
 *
 * <p>损坏收尾顺序（与 gundb 一致，但<b>绝不把 0 耐久配件塞回背包</b>，并改为原版标准破裂音）：</p>
 * <ol>
 *   <li>{@code IGun#unloadAttachment}：从枪的 NBT 摘除该配件槽；</li>
 *   <li>{@code AttachmentPropertyManager#postChangeEvent}：立刻刷新枪械属性缓存；</li>
 *   <li>若为扩展弹匣，调 {@code IGun#dropAllAmmo} 退回匣内子弹；</li>
 *   <li>{@code Player#inventoryMenu#broadcastChanges}：同步给客户端；</li>
 *   <li>在射手位置广播原版 {@code SoundEvents.ITEM_BREAK}（{@code 0.8f / 0.8~1.2f}）。</li>
 * </ol>
 */
public final class GunsmithRuntime {

    private static final String ATTACHMENT_EXTENDED_MAG_KEY = "AttachmentEXTENDED_MAG";

    /** 卸下并销毁枪上所有已到 0 耐久的配件；返回销毁数量。 */
    public static int destroyBrokenAttachments(LivingEntity shooter, ItemStack gun) {
        if (shooter == null || gun.isEmpty()) {
            return 0;
        }
        Object[] types = GunsmithAccess.attachmentTypes();
        if (types.length == 0) {
            return 0;
        }
        HolderLookup.Provider lookup = lookupOf(shooter);
        if (lookup == null) {
            return 0;
        }
        int destroyed = 0;
        for (Object type : types) {
            ItemStack attachment = GunsmithAccess.attachment(gun, type, lookup);
            if (attachment.isEmpty()) {
                continue;
            }
            int maxDamage = attachment.getMaxDamage();
            if (maxDamage <= 0 || attachment.getDamageValue() < maxDamage) {
                continue;
            }
            destroyOne(shooter, gun, type, lookup);
            destroyed++;
        }
        return destroyed;
    }

    /**
     * 在换弹启动/结束前拦截已损坏（或本轮即将损坏）的扩容弹匣，防止 gundb 把它退回玩家背包。
     *
     * @param includeNextUse {@code true} 时若 {@code damage + 1 >= maxDamage} 即立刻销毁
     *                       （供 {@code defaultReloadFinishing} HEAD 使用）；
     *                       {@code false} 时仅销毁已 {@code damage >= maxDamage} 的历史残件
     *                       （供 {@code startReload} HEAD 使用）。
     */
    public static boolean breakExtendedMagIfDepleted(
            LivingEntity shooter, ItemStack gun, boolean includeNextUse) {
        if (!(shooter instanceof Player) || gun.isEmpty() || shooter.level().isClientSide()) {
            return false;
        }
        Object extMagType = GunsmithAccess.extendedMagType();
        HolderLookup.Provider lookup = lookupOf(shooter);
        if (extMagType == null || lookup == null) {
            return false;
        }
        ItemStack mag = GunsmithAccess.attachment(gun, extMagType, lookup);
        if (mag.isEmpty()) {
            return false;
        }
        int maxDamage = mag.getMaxDamage();
        if (maxDamage <= 0) {
            return false;
        }
        int nextDamage = mag.getDamageValue() + (includeNextUse ? 1 : 0);
        if (nextDamage >= maxDamage) {
            destroyOne(shooter, gun, extMagType, lookup);
            return true;
        }
        return false;
    }

    /**
     * 修补 gundb {@code ModernKineticGunMixin#finishReload} 丢失写回的 Bug：
     * gundb 只在 {@code getAttachment} 返回的临时副本上调了 {@code setDamageValue}，
     * 从未写回枪械 {@code CUSTOM_DATA}。这里在 {@code defaultReloadFinishing} TAIL 处
     * 将扩容弹匣耐久 +1 写回 {@code AttachmentEXTENDED_MAG.components."minecraft:damage"}，
     * 并在归零时销毁。
     */
    public static void damageExtendedMagOnReload(LivingEntity shooter, ItemStack gun) {
        if (!(shooter instanceof Player) || gun.isEmpty() || shooter.level().isClientSide()) {
            return;
        }
        Object extMagType = GunsmithAccess.extendedMagType();
        HolderLookup.Provider lookup = lookupOf(shooter);
        if (extMagType == null || lookup == null) {
            return;
        }
        ItemStack mag = GunsmithAccess.attachment(gun, extMagType, lookup);
        if (mag.isEmpty()) {
            return;
        }
        int maxDamage = mag.getMaxDamage();
        if (maxDamage <= 0) {
            return;
        }
        int newDamage = mag.getDamageValue() + 1;
        if (newDamage >= maxDamage) {
            destroyOne(shooter, gun, extMagType, lookup);
            return;
        }
        writeAttachmentDamage(gun, ATTACHMENT_EXTENDED_MAG_KEY, newDamage);
    }

    /** 将指定配件槽的新损耗值写回枪械 {@code CUSTOM_DATA -> Attachment<TYPE> -> components -> minecraft:damage}。 */
    public static void writeAttachmentDamage(ItemStack gun, String attachmentKey, int newDamage) {
        CustomData.update(DataComponents.CUSTOM_DATA, gun, tag -> {
            if (!tag.contains(attachmentKey, CompoundTag.TAG_COMPOUND)) {
                return;
            }
            CompoundTag attachmentTag = tag.getCompound(attachmentKey);
            CompoundTag components = attachmentTag.getCompound("components");
            components.putInt("minecraft:damage", Math.max(0, newDamage));
            attachmentTag.put("components", components);
        });
    }

    /** 判断某枪上是否有已到 0 耐久的配件（只读，不销毁）。 */
    public static boolean hasBrokenAttachment(LivingEntity shooter, ItemStack gun) {
        if (shooter == null || gun.isEmpty()) {
            return false;
        }
        Object[] types = GunsmithAccess.attachmentTypes();
        HolderLookup.Provider lookup = lookupOf(shooter);
        if (lookup == null) {
            return false;
        }
        for (Object type : types) {
            ItemStack attachment = GunsmithAccess.attachment(gun, type, lookup);
            if (attachment.isEmpty()) {
                continue;
            }
            int maxDamage = attachment.getMaxDamage();
            if (maxDamage > 0 && attachment.getDamageValue() >= maxDamage) {
                return true;
            }
        }
        return false;
    }

    /** 这个物品堆是否是「已到 0 耐久」的 TaCZ 配件（给背包守卫用）。 */
    public static boolean isBrokenAttachment(ItemStack stack) {
        if (stack.isEmpty() || !GunsmithAccess.isAttachmentItem(stack)) {
            return false;
        }
        int maxDamage = stack.getMaxDamage();
        return maxDamage > 0 && stack.getDamageValue() >= maxDamage;
    }

    /** 在实体位置广播原版标准工具破裂音效（0.8 音量、0.8~1.2 音高）。 */
    public static void playBreakSound(LivingEntity entity) {
        Level level = entity.level();
        RandomSource random = level.getRandom();
        level.playSound(null, entity.getX(), entity.getY(), entity.getZ(),
                SoundEvents.ITEM_BREAK, SoundSource.PLAYERS, 0.8f, 0.8f + random.nextFloat() * 0.4f);
    }

    private static void destroyOne(LivingEntity shooter, ItemStack gun, Object type, HolderLookup.Provider lookup) {
        boolean extendedMag = GunsmithAccess.isExtendedMag(type);
        GunsmithAccess.unloadAttachment(gun, type, lookup);
        GunsmithAccess.postChangeEvent(shooter, gun);
        if (extendedMag && shooter instanceof Player player) {
            GunsmithAccess.dropAllAmmo(player, gun);
        }
        if (shooter instanceof Player player) {
            player.inventoryMenu.broadcastChanges();
        }
        playBreakSound(shooter);
    }

    private static HolderLookup.Provider lookupOf(LivingEntity entity) {
        Level level = entity.level();
        return level == null ? null : level.registryAccess();
    }

    private GunsmithRuntime() {
    }
}
