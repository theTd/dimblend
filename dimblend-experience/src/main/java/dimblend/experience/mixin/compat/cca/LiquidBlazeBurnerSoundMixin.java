package dimblend.experience.mixin.compat.cca;

import com.mrh0.createaddition.blocks.liquid_blaze_burner.LiquidBlazeBurnerBlockEntity;
import dimblend.experience.compat.sable.SableWorldPosition;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * 液态烈焰人燃烧室的环境音跟车走（2026-11-25 用户口径「sable 载具上的声音类方块」）。
 *
 * <p>三条发声路径都用 {@code level.playSound(null, worldPosition, ...)} 播方块音：
 * {@code playSound()}（换燃料时的石楠声）、{@code burningTick()} 与
 * {@code tryUpdateFuel()} 里的烈焰人氛围声。{@code worldPosition} 是 <b>plot 局部坐标</b>，
 * 装在载具上时声音留在原地、载具开走仍在响。这里把 BlockPos 参数投影成真实世界位置。
 * 每处各用一个 {@code @ModifyArg(require=1, allow=1)}，避免"整体不匹配却静默放行"。</p>
 */
@Mixin(value = LiquidBlazeBurnerBlockEntity.class, remap = false)
public abstract class LiquidBlazeBurnerSoundMixin {

    private BlockPos dimblend$project(BlockPos pos) {
        return SableWorldPosition.projectBlock(((LiquidBlazeBurnerBlockEntity) (Object) this).getLevel(), pos);
    }

    @ModifyArg(
            method = "playSound",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/Level;playSound(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/core/BlockPos;Lnet/minecraft/sounds/SoundEvent;Lnet/minecraft/sounds/SoundSource;FF)V"
            ),
            index = 1,
            require = 1,
            allow = 1
    )
    private BlockPos dimblend$projectPlaySound(BlockPos pos) {
        return dimblend$project(pos);
    }

    @ModifyArg(
            method = "burningTick",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/Level;playSound(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/core/BlockPos;Lnet/minecraft/sounds/SoundEvent;Lnet/minecraft/sounds/SoundSource;FF)V"
            ),
            index = 1,
            require = 1,
            allow = 1
    )
    private BlockPos dimblend$projectBurningTickAmbient(BlockPos pos) {
        return dimblend$project(pos);
    }

    @ModifyArg(
            method = "tryUpdateFuel",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/Level;playSound(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/core/BlockPos;Lnet/minecraft/sounds/SoundEvent;Lnet/minecraft/sounds/SoundSource;FF)V"
            ),
            index = 1,
            require = 1,
            allow = 1
    )
    private BlockPos dimblend$projectFuelSound(BlockPos pos) {
        return dimblend$project(pos);
    }
}
