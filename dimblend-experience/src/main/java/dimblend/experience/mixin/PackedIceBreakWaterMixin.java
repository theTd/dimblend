package dimblend.experience.mixin;

import dimblend.experience.Config;
import dimblend.experience.exploration.RotatingDimension;
import dimblend.experience.exploration.WaterWriteContext;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.EnchantmentTags;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 冰破坏产水（浮冰/蓝冰半边）：原版只有 {@code IceBlock} 在 {@code playerDestroy}
 * 产水，{@code Blocks.PACKED_ICE}/{@code Blocks.BLUE_ICE} 是普通 Block 无此逻辑。
 * 本 mixin 挂在 {@code Block.playerDestroy} 尾部，按实例身份判据只处理这两种冰，
 * 完整复制原版冰语义：工具无 {@code PREVENTS_ICE_MELTING}（精准采集）tag、
 * 维度非 ultraWarm、下方 {@code blocksMotion() || liquid()} 才原位写入水源；
 * 写入期间置位 {@link WaterWriteContext} bypass 绕过 G3 降级。
 *
 * <p>{@code playerDestroy} 仅服务端非创造路径调用（{@code ServerPlayerGameMode
 * #destroyBlock}：创造提前 return，源码核实），创造破坏不产水由调用门天然保证。
 * 身份判据在首行，其余方块的 playerDestroy 零成本通过。普通冰不在此处理
 * （其 {@code playerDestroy} 是 {@code IceBlock} 覆写，产水由原版完成、
 * bypass 由 {@code IceBlockWaterBypassMixin} 置位）。</p>
 */
@Mixin(Block.class)
public abstract class PackedIceBreakWaterMixin {

    @Inject(method = "playerDestroy", at = @At("TAIL"))
    private void dimblend$packedIceMeltsIntoSource(Level level, Player player, BlockPos pos, BlockState state,
            BlockEntity blockEntity, ItemStack tool, CallbackInfo ci) {
        if ((Object) this != Blocks.PACKED_ICE && (Object) this != Blocks.BLUE_ICE) {
            return;
        }
        if (!Config.ICE_BREAK_WATER_SOURCE.get() || !RotatingDimension.is(level)) {
            return;
        }
        if (EnchantmentHelper.hasTag(tool, EnchantmentTags.PREVENTS_ICE_MELTING)
                || level.dimensionType().ultraWarm()) {
            return;
        }
        BlockState below = level.getBlockState(pos.below());
        if (!below.blocksMotion() && !below.liquid()) {
            return;
        }
        WaterWriteContext.enterBypass();
        try {
            level.setBlockAndUpdate(pos, Blocks.WATER.defaultBlockState());
        } finally {
            WaterWriteContext.exitBypass();
        }
    }
}
