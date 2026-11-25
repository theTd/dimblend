package dimblend.experience.mixin.compat.gunsmith;

import dimblend.experience.compat.gunsmith.GunsmithData;
import dimblend.experience.compat.gunsmith.GunsmithMath;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * 卡壳排障路径 2（{@code StartJamWorkerPacket}）按隐藏经验等级缩短排障毫秒时长
 * （0–50 级线性 {@code 1.0x..3.0x}，仅客户端注册）。
 *
 * <p>gundb {@code InspectKeybindHook} 在客户端按检视动画毫秒时长构造
 * {@code new StartJamWorkerPacket(lengthMs)} 发给服务端；服务端
 * {@code StartJamWorkerPacket.decode} 在 Netty IO 线程也会调用同一个 {@code <init>(I)V}。
 * 因此必须用 {@link Minecraft#isSameThread()} 门控：仅在客户端渲染线程发包构造时缩放一次，
 * 彻底杜绝单人集成服下 Netty 线程解码时二次除法（否则会变成 9x 与动画脱节）！</p>
 */
@Pseudo
@Mixin(targets = "mod.cdv.gdb.network.StartJamWorkerPacket", remap = false)
public abstract class GunsmithUnjamSpeedMixin {

    @ModifyVariable(method = "<init>(I)V", at = @At("HEAD"), argsOnly = true, require = 1, allow = 1)
    private static int dimblend$shortenUnjam(int length) {
        if (length <= 1 || FMLEnvironment.dist != Dist.CLIENT) {
            return length;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || !minecraft.isSameThread() || minecraft.player == null) {
            return length;
        }
        ItemStack held = minecraft.player.getMainHandItem();
        int level = GunsmithData.getLevel(held);
        if (level <= 0) {
            return length;
        }
        return GunsmithMath.scaledDurationMs(length, level);
    }
}
