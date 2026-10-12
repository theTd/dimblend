package dimblend.asyncsave.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import dimblend.asyncsave.WorldGenSettingsTagCache;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.storage.PrimaryLevelData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * level.dat 快照里的 WorldGenSettings 段走 {@link WorldGenSettingsTagCache}：同一世界只编码
 * 一次，之后的存档复用副本，自动存档不再在服务器线程上重跑整套维度 Codec。
 */
@Mixin(PrimaryLevelData.class)
public abstract class PrimaryLevelDataWorldGenSettingsMixin {
    @WrapOperation(
        method = "setTagData",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/levelgen/WorldGenSettings;encode(Lcom/mojang/serialization/DynamicOps;Lnet/minecraft/world/level/levelgen/WorldOptions;Lnet/minecraft/core/RegistryAccess;)Lcom/mojang/serialization/DataResult;"
        )
    )
    private <T> DataResult<T> dimblend_asyncsave$reuseWorldGenSettings(DynamicOps<T> ops, WorldOptions options,
                                                                        RegistryAccess access,
                                                                        Operation<DataResult<T>> original) {
        return WorldGenSettingsTagCache.encode(options, access, () -> original.call(ops, options, access));
    }
}
