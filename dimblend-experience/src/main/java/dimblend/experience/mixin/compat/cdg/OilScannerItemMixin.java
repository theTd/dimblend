package dimblend.experience.mixin.compat.cdg;

import com.jesz.createdieselgenerators.CDGConfig;
import com.jesz.createdieselgenerators.content.tools.OilScannerItem;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dimblend.experience.compat.cdg.CdgNewOilChunksSavedData;
import dimblend.experience.compat.cdg.CdgOilBalance;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 新生成区块上原油探测器的高低储量分界线同步缩放 10%。
 */
@Mixin(OilScannerItem.class)
public abstract class OilScannerItemMixin {

    @WrapOperation(
            method = "inventoryTick(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/Entity;IZ)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/neoforged/neoforge/common/ModConfigSpec$ConfigValue;get()Ljava/lang/Object;"),
            require = 4,
            allow = 4)
    private Object dimblend$scaleScannerThresholdsForNewChunk(
            ModConfigSpec.ConfigValue<?> instance,
            Operation<Object> original,
            @Local ServerLevel sl,
            @Local ChunkPos chunk) {
        Object raw = original.call(instance);
        if ((instance == CDGConfig.OIL_CHUNK_THRESHOLD
                        || instance == CDGConfig.OIL_CHUNK_INFINITE_THRESHOLD)
                && raw instanceof Integer threshold
                && CdgNewOilChunksSavedData.isNewChunk(sl, chunk)) {
            return CdgOilBalance.scaleNewChunkThreshold(threshold);
        }
        return raw;
    }
}
