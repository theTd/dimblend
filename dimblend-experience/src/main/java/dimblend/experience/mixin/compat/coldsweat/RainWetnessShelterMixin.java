package dimblend.experience.mixin.compat.coldsweat;

import com.momosoftworks.coldsweat.util.world.WorldHelper;
import dev.ryanhcode.sable.util.SubLevelRainColumn;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 子层级车顶挡住雨时，Cold Sweat 不再把玩家算作淋雨（车厢内不湿身）。
 *
 * <p>Cold Sweat 2.4.2 的判定链（源码 1.21-FG 分支核实）：
 * <pre>
 * WaterTempModifier.calculate / EntityTempManager（每 40 tick）
 *   → WorldHelper.isRainingAt(level, entity.blockPosition())
 *       pos = sublevelToWorld(level, pos);          // plot 坐标 → 世界坐标
 *       return ... &amp;&amp; canSeeSky(level, pos.above(), maxBuildHeight) &amp;&amp; ...
 * </pre>
 * {@code canSeeSky} 只看**世界**方块柱。Sable 子层级的车顶在 plot 区块里，世界那一列永远
 * 是空的，于是判定恒为"淋雨"，湿身值一路涨到上限。</p>
 *
 * <p>湿身值同时驱动两处可见效果，所以这一个源头把两个现象一起解决了：</p>
 * <ul>
 *   <li>{@code WetnessRenderer.onRenderOverlay} 按 {@code WaterTempModifier} 的温度取
 *       wetness，> 0.01 才生成屏幕水珠；归零后水珠只淡出、不再新增。</li>
 *   <li>{@code WaterTempModifier.tick} 以 {@code |temperature| * 2} 为概率在玩家自身
 *       坐标撒 {@code ParticleTypes.FALLING_WATER}，即脚边不断溅的水花。</li>
 * </ul>
 *
 * <p>判据刻意与挡雨渲染共用 {@link SubLevelRainColumn}：渲染那边用同一函数决定
 * "这一列的雨从哪里开始"，所以"看得见雨"和"会湿身"不会互相矛盾。</p>
 *
 * <ul>
 *   <li>相机/玩家在车顶之上（站在车顶）时，返回值等于脚下地板顶面，不大于脚部 Y，
 *       不算遮挡——站在车顶照样淋湿。</li>
 *   <li>敞篷、天窗、舱门这类头顶是空的列，扫描得到的是地板，同样不遮挡，与挡雨渲染
 *       的语义一致（敞口就该看得见雨、也会湿）。</li>
 * </ul>
 *
 * <p>只在降雨时做这次扫描：不下雨时 Cold Sweat 原本也返回 false（本包未装 Weather2），
 * 提前返回省掉每个实体每 40 tick 的一次列扫描。</p>
 */
@Mixin(WorldHelper.class)
public abstract class RainWetnessShelterMixin {

    /**
     * 在 Cold Sweat 自己判定之前短路：子层级顶面高于脚下 Y 时，此处不算降雨。
     */
    @Inject(method = "isRainingAt(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;)Z",
            at = @At("HEAD"), cancellable = true, require = 1)
    private static void dimblend$sublevelRoofKeepsPlayerDry(Level level, BlockPos pos,
            CallbackInfoReturnable<Boolean> cir) {
        if (!level.isRaining()) {
            return;
        }

        // Cold Sweat 自己的 plot→world 映射：不在 plot 区时恒等，两种坐标空间都安全。
        final BlockPos world = WorldHelper.sublevelToWorld(level, pos);
        final int shelter = SubLevelRainColumn.height(level, world.getX(), world.getZ(), 1);

        // 严格大于：站在车顶时返回值正好等于脚部 Y，必须继续算淋雨。
        if (shelter != SubLevelRainColumn.NONE && shelter > world.getY()) {
            cir.setReturnValue(false);
        }
    }
}