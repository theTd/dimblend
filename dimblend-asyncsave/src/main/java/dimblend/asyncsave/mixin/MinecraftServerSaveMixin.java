package dimblend.asyncsave.mixin;

import dimblend.asyncsave.SaveMode;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 同步存档窗口的进出点。自动存档调 saveEverything(true, false, false)，三个参数依次是
 * suppressLog / flush / force；手动 /save-all 为 (true, false, true)，/save-all flush 为
 * (true, true, true)，关服为 saveAllChunks(false, true, false)。flush 或 force 为真时
 * 全程保持原版同步语义（并在开头 drain 异步队列），只有纯自动存档放行异步。
 */
@Mixin(MinecraftServer.class)
public abstract class MinecraftServerSaveMixin {
    @Inject(method = "saveEverything", at = @At("HEAD"))
    private void dimblend_asyncsave$enterSyncEverything(boolean suppressLog, boolean flush, boolean force, CallbackInfoReturnable<Boolean> cir) {
        if (flush || force) {
            SaveMode.enterSync();
        }
    }

    @Inject(method = "saveEverything", at = @At("RETURN"))
    private void dimblend_asyncsave$exitSyncEverything(boolean suppressLog, boolean flush, boolean force, CallbackInfoReturnable<Boolean> cir) {
        if (flush || force) {
            SaveMode.exitSync();
        }
    }

    @Inject(method = "saveAllChunks", at = @At("HEAD"))
    private void dimblend_asyncsave$enterSyncAllChunks(boolean suppressLog, boolean flush, boolean force, CallbackInfoReturnable<Boolean> cir) {
        if (flush || force) {
            SaveMode.enterSync();
        }
    }

    @Inject(method = "saveAllChunks", at = @At("RETURN"))
    private void dimblend_asyncsave$exitSyncAllChunks(boolean suppressLog, boolean flush, boolean force, CallbackInfoReturnable<Boolean> cir) {
        if (flush || force) {
            SaveMode.exitSync();
        }
    }
}
