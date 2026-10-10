package dimblend.asyncsave.mixin;

import com.mojang.logging.LogUtils;
import dimblend.asyncsave.AsyncSaveQueue;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import net.minecraft.Util;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.storage.PlayerDataStorage;
import net.neoforged.neoforge.event.EventHooks;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 玩家 .dat 异步写：主线程照旧 saveWithoutId 序列化出快照，写盘（临时文件 + gzip +
 * safeReplaceFile）挪到后台。读路径（玩家进服 / 重进）在 load 前 drain，避免断线异步写
 * 尚未落盘时读到旧数据造成回档/复制。
 */
@Mixin(PlayerDataStorage.class)
public abstract class PlayerDataStorageMixin {
    @Shadow
    @Final
    private File playerDir;

    @Inject(method = "save", at = @At("HEAD"), cancellable = true)
    private void dimblend_asyncsave$asyncSave(Player player, CallbackInfo ci) {
        if (!AsyncSaveQueue.shouldRunAsync()) {
            return;
        }
        CompoundTag tag;
        try {
            tag = player.saveWithoutId(new CompoundTag());
        } catch (Exception e) {
            // 序列化失败保持原版的日志与吞掉行为
            LogUtils.getLogger().warn("Failed to save player data for {}", player.getName().getString());
            ci.cancel();
            return;
        }
        Path dir = this.playerDir.toPath();
        String uuid = player.getStringUUID();
        Path target = dir.resolve(uuid + ".dat");
        AsyncSaveQueue.submit(target, () -> {
            Path temp = Files.createTempFile(dir, uuid + "-", ".dat");
            NbtIo.writeCompressed(tag, temp);
            Util.safeReplaceFile(target, temp, dir.resolve(uuid + ".dat_old"));
        });
        // NeoForge 原版语义是写盘成功后 fire PlayerEvent.SaveToFile；异步下提交即 fire，
        // 写失败本就只记日志（与原版一致），事件语义不退化。try/catch 对齐 NeoForge 基线：
        // 监听者抛异常只落得一条 warn，不能传播出去炸服。
        try {
            EventHooks.firePlayerSavingEvent(player, this.playerDir, uuid);
        } catch (Exception e) {
            LogUtils.getLogger().warn("Failed to fire PlayerEvent.SaveToFile for {}", player.getName().getString(), e);
        }
        ci.cancel();
    }

    @Inject(method = "load(Lnet/minecraft/world/entity/player/Player;)Ljava/util/Optional;", at = @At("HEAD"))
    private void dimblend_asyncsave$drainBeforeLoad(Player player, CallbackInfoReturnable<Optional<CompoundTag>> cir) {
        AsyncSaveQueue.drain();
    }
}
