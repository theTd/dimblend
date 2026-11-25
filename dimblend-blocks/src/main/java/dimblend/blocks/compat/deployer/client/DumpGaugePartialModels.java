package dimblend.blocks.compat.deployer.client;

import dev.engine_room.flywheel.lib.model.baked.PartialModel;
import dimblend.blocks.DimBlendBlocks;
import net.minecraft.resources.ResourceLocation;

/**
 * 卸料仪表的面板部分模型（暗钢蓝重着色版）。
 *
 * <p>{@code FactoryPanelModel} 直接用 {@link PartialModel#get()} 的 baked quads 注入面板方块，
 * 所以贴图必须挂在「只有 elements、没有 parent」的几何模型上：
 * <ul>
 *   <li>{@code panel} / {@code panel_with_bulb}——network 型（有/无灯珠）</li>
 *   <li>{@code panel_restocker} / {@code panel_restocker_with_bulb}——packager 型（parent 到上面两个，
 *       只换贴图）</li>
 * </ul>
 *
 * <p>Flywheel 要求 PartialModel 在模型烘焙之前注册，Create 自己也是在客户端 mod 构造器里惰性初始化
 * {@code AllPartialModels}，本类照同一时序，由 {@link DumpGaugeClient#register} 调用。</p>
 */
public final class DumpGaugePartialModels {

    private static final String DIR = "block/dump_gauge/";

    public static PartialModel PANEL;
    public static PartialModel PANEL_WITH_BULB;
    public static PartialModel PANEL_RESTOCKER;
    public static PartialModel PANEL_RESTOCKER_WITH_BULB;

    private DumpGaugePartialModels() {}

    /** 幂等：重复调用不会重复注册。 */
    public static void init() {
        if (PANEL != null) {
            return;
        }
        PANEL = PartialModel.of(id("panel"));
        PANEL_WITH_BULB = PartialModel.of(id("panel_with_bulb"));
        PANEL_RESTOCKER = PartialModel.of(id("panel_restocker"));
        PANEL_RESTOCKER_WITH_BULB = PartialModel.of(id("panel_restocker_with_bulb"));
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(DimBlendBlocks.MODID, DIR + path);
    }
}
