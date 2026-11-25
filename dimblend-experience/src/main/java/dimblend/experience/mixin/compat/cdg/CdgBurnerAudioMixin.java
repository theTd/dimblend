package dimblend.experience.mixin.compat.cdg;

import dimblend.experience.compat.sable.SableWorldPosition;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 柴油发电机组的燃烧器环境音跟车走（2026-11-25 用户口径「sable 载具上的声音类方块」）。
 *
 * <p>{@code BurnerBlockEntity#tickAudio} 用 {@code level.playLocalSound(worldPosition...)}
 * 播火焰环境音，坐标是 <b>plot 局部坐标</b>：装在 Sable 载具上时声音会留在原地不动，
 * 载具开走后还能听见。这里把三个坐标参数先投影成真实世界位置再播。
 * 只改声源位置，不改音量/节拍/粒子（粒子本身由上游 plot 渲染器处理）。</p>
 */
@Mixin(targets = "com.jesz.createdieselgenerators.content.burner.BurnerBlockEntity", remap = false)
public abstract class CdgBurnerAudioMixin {

    @Redirect(
            method = "tickAudio",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/Level;playLocalSound(DDDLnet/minecraft/sounds/SoundEvent;Lnet/minecraft/sounds/SoundSource;FFZ)V"
            ),
            require = 1,
            allow = 1
    )
    private void dimblend$sableBurnerAmbience(Level level, double x, double y, double z,
            SoundEvent sound, SoundSource source, float volume, float pitch, boolean distanceDelay) {
        Vec3 world = SableWorldPosition.project(level, new Vec3(x, y, z));
        level.playLocalSound(world.x, world.y, world.z, sound, source, volume, pitch, distanceDelay);
    }
}
