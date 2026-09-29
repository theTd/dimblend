package dimblend.experience.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dimblend.experience.Config;
import dimblend.experience.exploration.RotatingDimension;
import dimblend.experience.exploration.WaterWriteContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.IceBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;

/**
 * 冰破坏产水绕过 G3（普通冰半边）：原版 {@code IceBlock.playerDestroy} 在无精准
 * 采集挖掘且脚下固体/液体时原位写入水源（{@code meltsInto()}），该写入经
 * {@code Level#setBlock} 会被 G3 拦成 water7。本 mixin 在方法期间置位
 * {@link WaterWriteContext} bypass，让这次写入保留源水。
 *
 * <p>身份判据只认 {@code Blocks.ICE}：{@code FrostedIceBlock} 继承
 * {@code IceBlock} 且未覆写 {@code playerDestroy}，霜冰是冰霜行者可再生资源，
 * 放行进 bypass 等于开出无限水口子——拍板口径明确"三种冰不含 frosted_ice"。</p>
 *
 * <p>{@code playerDestroy} 仅服务端调用（{@code ServerPlayerGameMode#destroyBlock}，
 * 源码核实唯一入口），Config 读取安全。只包 {@code playerDestroy}：光照融化的
 * {@code melt()} 不置位——非玩家写入按拍板口径仍降级。ultraWarm 分支无写入，
 * 置位无害。浮冰/蓝冰原版不产水，由 {@code PackedIceBreakWaterMixin} 补齐。</p>
 *
 * <p>{@code @WrapMethod} + try/finally：目标中途抛异常时 bypass 也能复位，
 * 不会把置位泄漏给主线程后续无关写入。</p>
 */
@Mixin(IceBlock.class)
public abstract class IceBlockWaterBypassMixin {

    @WrapMethod(method = "playerDestroy")
    private void dimblend$playerDestroyWithBypass(Level level, Player player, BlockPos pos, BlockState state,
            BlockEntity blockEntity, ItemStack tool, Operation<Void> original) {
        boolean bypass = (Object) this == Blocks.ICE
                && Config.ICE_BREAK_WATER_SOURCE.get() && RotatingDimension.is(level);
        if (bypass) {
            WaterWriteContext.enterBypass();
        }
        try {
            original.call(level, player, pos, state, blockEntity, tool);
        } finally {
            if (bypass) {
                WaterWriteContext.exitBypass();
            }
        }
    }
}
