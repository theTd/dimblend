package dimblend.experience.mixin.compat.gunsmith;

import javax.annotation.Nullable;

import dimblend.experience.compat.gunsmith.GunsmithAccess;
import dimblend.experience.compat.gunsmith.GunsmithData;
import dimblend.experience.compat.gunsmith.GunsmithMath;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.StringUtil;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.inventory.ItemCombinerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 铁砧上的「枪匠」全部规则（<b>priority 900，先于 gundb 的 1000</b>；
 * 严格对齐 LOCKED DECISION #20）：
 *
 * <ol>
 *   <li><b>左槽为配件（{@code IAttachment}）</b>：右槽非空一律置空拦截（禁止材料修、禁止两件配件合并）；
 *       右槽为空放行原版纯改名。</li>
 *   <li><b>左槽为受 gundb 管理的枪械（{@link GunsmithData#isGundbGun}）</b>：
 *     <ul>
 *       <li>右槽为空：放行原版纯改名；</li>
 *       <li>右槽为 {@code create:precision_mechanism}：按 7 类枪型满修数量（1/2/1/3/4/3/1）计算
 *           {@link GunsmithMath#computeAnvilRepair}：原耐久满（{@code damageValue <= 0}）时即便蓝条不满也拒绝；
 *           满足前置时可灌注至 200%（原耐久 + 蓝条），每消耗 1 件精密构件隐藏经验等级 +1（上限 50），
 *           经验消耗严格等于 {@code used}（同时改名 +1），并清零 {@code REPAIR_COST} 永不出现「过于昂贵」；</li>
 *       <li>右槽为 {@link Items#ENCHANTED_BOOK}：放行原版附魔书附魔（如耐久附魔）；</li>
 *       <li>右槽为其它任何物品（铁/铜/下界合金锭、第二把枪合并等）：置空拦截。</li>
 *     </ul>
 *   </li>
 * </ol>
 */
@Mixin(value = AnvilMenu.class, priority = 900)
public abstract class GunsmithAnvilMenuMixin extends ItemCombinerMenu {

    @Shadow @Final private DataSlot cost;
    @Shadow private int repairItemCountCost;
    @Shadow @Nullable private String itemName;

    protected GunsmithAnvilMenuMixin(MenuType<?> type, int containerId, Inventory playerInventory,
            ContainerLevelAccess access) {
        super(type, containerId, playerInventory, access);
    }

    @Inject(method = "createResult", at = @At("HEAD"), cancellable = true, require = 1, allow = 1)
    private void dimblend$gunsmithRepair(CallbackInfo ci) {
        ItemStack left = this.inputSlots.getItem(0);
        if (left.isEmpty()) {
            return;
        }
        ItemStack right = this.inputSlots.getItem(1);

        if (GunsmithAccess.isAttachmentItem(left)) {
            if (!right.isEmpty()) {
                this.dimblend$blank(ci);
            }
            return;
        }

        if (GunsmithData.isGundbGun(left)) {
            if (right.isEmpty()) {
                return;
            }
            if (this.dimblend$isPrecisionMechanism(right)) {
                this.dimblend$applyPrecisionMechanism(left, right, ci);
                return;
            }
            if (right.is(Items.ENCHANTED_BOOK)) {
                return;
            }
            // 禁止锭修枪、禁止同枪二合一绕过精密构件。
            this.dimblend$blank(ci);
        }
    }

    @Unique
    private void dimblend$applyPrecisionMechanism(ItemStack gun, ItemStack mechanisms, CallbackInfo ci) {
        String gunType = GunsmithAccess.gunType(gun);
        GunsmithMath.RepairResult repair = GunsmithMath.computeAnvilRepair(
                gun.getMaxDamage(),
                gun.getDamageValue(),
                GunsmithData.getOverDurability(gun),
                GunsmithData.getLevel(gun),
                gunType,
                mechanisms.getCount());
        if (!repair.allowed()) {
            this.dimblend$blank(ci);
            return;
        }

        ItemStack out = gun.copy();
        out.setDamageValue(repair.newDamage());
        GunsmithData.setOverDurability(out, repair.newOverDurability());
        GunsmithData.setLevel(out, repair.newLevel());
        out.set(DataComponents.REPAIR_COST, 0);

        int renameCost = 0;
        if (this.itemName != null && !StringUtil.isBlank(this.itemName)) {
            if (!this.itemName.equals(gun.getHoverName().getString())) {
                renameCost = 1;
                out.set(DataComponents.CUSTOM_NAME, Component.literal(this.itemName));
            }
        } else if (gun.has(DataComponents.CUSTOM_NAME)) {
            renameCost = 1;
            out.remove(DataComponents.CUSTOM_NAME);
        }

        this.repairItemCountCost = repair.usedMechanisms();
        this.cost.set(repair.xpCost() + renameCost);
        this.resultSlots.setItem(0, out);
        this.broadcastChanges();
        ci.cancel();
    }

    @Unique
    private void dimblend$blank(CallbackInfo ci) {
        this.resultSlots.setItem(0, ItemStack.EMPTY);
        this.cost.set(0);
        this.repairItemCountCost = 0;
        this.broadcastChanges();
        ci.cancel();
    }

    @Unique
    private boolean dimblend$isPrecisionMechanism(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return "create".equals(id.getNamespace()) && "precision_mechanism".equals(id.getPath());
    }
}
