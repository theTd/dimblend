package dimblend.experience.mixin.compat.cca;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.mrh0.createaddition.blocks.liquid_blaze_burner.LiquidBlazeBurnerBlockEntity;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.fluids.capability.templates.FluidTank;

/**
 * Create Crafts & Additions 鐨勬恫浣撶儓鐒颁汉鐐夛細瀹归噺 1000mB銆佹嫆缁濅竴鍒囧浐浣撶噧鏂欍€? *
 * <p>鐢ㄦ埛鍙ｅ緞锛?026-11-25锛夛細娑蹭綋鐐夋棦鐒跺彧鐑ф祦浣擄紝灏卞埆鍐嶅仛"濉炵叅涔熻兘鐑?鐨勯檷绾ц矾寰勨€斺€? * 鍘熺増 {@code tryUpdateFuel} 閲屽浐浣撶噧鏂欙紙鐓?鏈ㄧ偔/鐑堢劙铔嬬硶/鐑啺鈥︼級鑳芥敼鍙?{@code activeFuel}
 * 涓庣噧鐑ф椂闂达紝绛変簬鎶婃恫浣撶倝褰撴垚鏅€氱儓鐒颁汉鐐夌敤锛岀牬鍧忎簡"娑蹭綋鐕冩枡涓撶敤"鐨勮璁°€?/p>
 *
 * <p>涓夊鏀瑰姩锛坽@code javap -p -c} 鏍稿疄锛夛細</p>
 * <ul>
 *   <li>{@code createInventory}锛歿@code sipush 4000}锛坽@code MAX_HEAT_CAPACITY} 琚唴鑱旓級
 *       鈫?{@code 1000}锛屾补绠卞閲?1000mB锛?/li>
 *   <li>{@code read} TAIL锛氳€佸瓨妗ｉ噷 4000mB 鐨勫瓨閲忚澶瑰埌 1000锛屽惁鍒?tank 鐨?amount 浼氳秴杩囨柊瀹归噺
 *       锛坽@code FluidTank} 鍙嶅簭鍒楀寲涓嶆寜瀹归噺澶癸級锛?/li>
 *   <li>{@code tryUpdateFuel} HEAD锛氭暣浣撳彇娑堬紝鐩存帴杞粰 {@code tryUpdateLiquid}
 *       锛堟恫浣撶噧鏂欒矾寰勶級銆傚垱閫犳ā寮忕儓鐒拌泲绯曞湪 {@code tryInsert} 閲屾洿鏃╁鐞嗭紝涓嶇粡杩囪繖閲岋紝淇濇寔鍘熸牱銆?/li>
 * </ul>
 *
 * <p>{@code tryUpdateLiquid} 鏄?{@code private}锛氱敤 {@code @Shadow} 鎷垮埌锛堝悓涓€鐩爣绫诲唴鍚堝苟鍚? * 灏辨槸鏅€?INVOKESPECIAL锛屼笉闇€瑕佸弽灏勶級銆?/p>
 */
@Pseudo
@Mixin(value = LiquidBlazeBurnerBlockEntity.class, remap = false)
public abstract class LiquidBlazeBurnerFuelMixin {

    /** 鏂板閲忥紙mB锛夈€?*/
    private static final int dimblend$CAPACITY_MB = 1000;

    @Shadow
    protected FluidTank tankInventory;

    @Shadow
    private boolean tryUpdateLiquid(ItemStack stack, boolean simulate) {
        throw new AssertionError("shadow");
    }

    @ModifyConstant(method = "createInventory", constant = @Constant(intValue = 4000),
            remap = false, require = 1, allow = 1)
    private int dimblend$shrinkTankTo1000(int original) {
        return dimblend$CAPACITY_MB;
    }

    @Inject(method = "read", at = @At("TAIL"), remap = false, require = 1, allow = 1)
    private void dimblend$clampLoadedFuel(CompoundTag tag, HolderLookup.Provider registries,
            boolean clientPacket, CallbackInfo ci) {
        if (this.tankInventory == null) {
            return;
        }
        int amount = this.tankInventory.getFluidAmount();
        if (amount > dimblend$CAPACITY_MB) {
            this.tankInventory.drain(amount - dimblend$CAPACITY_MB, IFluidHandler.FluidAction.EXECUTE);
        }
    }

    @Inject(method = "tryUpdateFuel", at = @At("HEAD"), cancellable = true, remap = false,
            require = 1, allow = 1)
    private void dimblend$onlyLiquidFuel(ItemStack stack, boolean doNotConsume, boolean forceOverflow,
            CallbackInfoReturnable<Boolean> cir) {
        // 鍥轰綋鐕冩枡涓€寰嬭蛋"褰撴恫浣撹瘯涓€涓?锛氫笉鏄恫浣撳氨鏄?false锛屼笉鍐嶆敼 activeFuel / burnTime銆?        cir.setReturnValue(this.tryUpdateLiquid(stack, doNotConsume));
    }
}

