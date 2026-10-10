package dimblend.asyncsave.mixin;

import dimblend.asyncsave.AsyncSaveQueue;
import java.nio.file.Path;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.WorldData;
import net.neoforged.neoforge.common.CommonHooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * level.dat 异步写：主线程照旧调 WorldData.createTag 生成快照（读活的游戏状态，不能离开
 * 服务器线程），写盘部分复用原版私有方法 saveLevelData（临时文件 + gzip + SYNC 选项 +
 * safeReplaceFile 语义原样保留），只是挪到后台写线程执行。
 */
@Mixin(targets = "net.minecraft.world.level.storage.LevelStorageSource$LevelStorageAccess")
public abstract class LevelStorageAccessMixin {
    @Invoker("saveLevelData")
    protected abstract void dimblend_asyncsave$invokeSaveLevelData(CompoundTag tag);

    @Inject(
        method = "saveDataTag(Lnet/minecraft/core/RegistryAccess;Lnet/minecraft/world/level/storage/WorldData;Lnet/minecraft/nbt/CompoundTag;)V",
        at = @At("HEAD"),
        cancellable = true
    )
    private void dimblend_asyncsave$asyncSaveDataTag(RegistryAccess registryAccess, WorldData worldData, CompoundTag playerTag, CallbackInfo ci) {
        if (!AsyncSaveQueue.shouldRunAsync()) {
            return;
        }
        CompoundTag tag = new CompoundTag();
        tag.put("Data", worldData.createTag(registryAccess, playerTag));
        // NeoForge patch 会在 saveLevelData 前往 level.dat 根写入 fml.LoadingModList，
        // 供下次进档做 mod 版本差异检测（ModMismatchEvent）；异步路径必须补齐，否则常态
        // 自动存档写出的 level.dat 始终缺这一段。
        CommonHooks.writeAdditionalLevelSaveData(worldData, tag);
        Path target = ((LevelStorageSource.LevelStorageAccess)(Object)this).getLevelPath(LevelResource.LEVEL_DATA_FILE);
        AsyncSaveQueue.submit(target, () -> this.dimblend_asyncsave$invokeSaveLevelData(tag));
        ci.cancel();
    }
}
