package dimblend.experience.datagen;

import java.util.List;

/**
 * factory_blocks 装饰方块 ID 表（纯 Java，无游戏依赖，单测可直接引用）。
 *
 * <p>与 factory_blocks 1.4.0（origin/1.21.1 分支）{@code RegisterBlocks} 的 42 项 {@code Type.base}
 * 加 6 种风扇一一对应，共 48 项。上游增删方块时同步此表，并重跑 {@code runData}。
 *
 * <p>7 种风扇里只有 {@code medium_fan} 因碰撞箱问题排除，保持不可制作（用户 2026-10-31 口径）；
 * 其余 6 种（fan / fan_on / fan_four / fan_four_on / fan_malfunction / fan_malfunction_on）与
 * 其它装饰方块一样进切石机配方。
 */
public final class FactoryStonecuttingBlocks {
    private FactoryStonecuttingBlocks() {}

    public static final List<String> DECORATIVE_BLOCKS = List.of(
            "factory", "rust", "vrust", "srust",
            "wireframe", "pwireframe", "bwireframe",
            "hazard", "hazardo", "caution",
            "circuit", "gcircuit", "pgcircuit", "bcircuit", "mosaic",
            "metalbox", "megacell", "ice",
            "grinder", "old_vents", "grate", "rgrate",
            "rust_plates", "rust_bplates",
            "large_pipes", "small_pipes", "piping", "engineer",
            "vent", "gvent",
            "insulation", "gears", "cables",
            "hex", "wgpanel", "wopanel",
            "sturdy", "exhaust",
            "rusty_scaffold", "scaffold",
            "large_plating", "fan_side",
            "fan", "fan_on", "fan_four", "fan_four_on",
            "fan_malfunction", "fan_malfunction_on");
}
