package dimblend.experience.compat.create;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.logistics.funnel.AbstractFunnelBlock;

import net.minecraft.world.level.block.state.BlockState;

/**
 * 安山漏斗红石免疫（2026-11-25）：<b>只有安山漏斗</b>（含安山传送带漏斗）不吃红石信号。
 *
 * <p>为什么只认安山：黄铜漏斗的红石行为是"过滤器暂停/放行"的核心玩法，绝对不能牵连；
 * 安山漏斗没有过滤器，"被打红石就停"只是早期设计的噪音。判定必须严格按方块身份，
 * 不能用 {@code instanceof AbstractFunnelBlock}（那样黄铜/所有别的漏斗都会中招）。</p>
 *
 * <p>"免疫"就要让 {@code POWERED} 永不为真：放置强制 false、{@code neighborChanged} 不再排
 * 1 tick 的红石同步、{@code tick} 只做"把旧存档里的 legacy true 复位"。
 * 这样所有下游（插入/抽取模式、皮带漏斗形态、模型 powered 变体）自然都看到 unpowered。</p>
 */
public final class AndesiteFunnelRedstoneRules {

    /** 是否安山漏斗（严格身份判定，绝不含黄铜/漏斗 dump 等其它形态）。 */
    public static boolean isAndesiteFunnel(BlockState state) {
        if (state == null) {
            return false;
        }
        return state.is(AllBlocks.ANDESITE_FUNNEL.get()) || state.is(AllBlocks.ANDESITE_BELT_FUNNEL.get());
    }

    /** 安山漏斗当前是否残留 legacy {@code POWERED == true}（旧存档/旧版本留下的）。 */
    public static boolean hasLegacyPowered(BlockState state) {
        if (!isAndesiteFunnel(state) || !state.hasProperty(AbstractFunnelBlock.POWERED)) {
            return false;
        }
        return state.getValue(AbstractFunnelBlock.POWERED);
    }

    /** 把 {@code POWERED} 压成 false（非安山漏斗原样返回）。 */
    public static BlockState withUnpowered(BlockState state) {
        if (!isAndesiteFunnel(state) || !state.hasProperty(AbstractFunnelBlock.POWERED)) {
            return state;
        }
        return state.getValue(AbstractFunnelBlock.POWERED) ? state.setValue(AbstractFunnelBlock.POWERED, false) : state;
    }

    private AndesiteFunnelRedstoneRules() {
    }
}
