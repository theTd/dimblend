package dimblend.experience.compat.create.vault;

import com.simibubi.create.content.redstone.displayLink.DisplayLinkContext;
import com.simibubi.create.content.redstone.displayLink.source.PercentOrProgressBarDisplaySource;
import com.simibubi.create.foundation.gui.ModularGuiLineBuilder;
import dimblend.experience.DimBlend;
import java.util.List;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.jetbrains.annotations.Nullable;

/**
 * 显示连接器读取物品保险库（含 纵向物品保险库 / 集装箱）的公共显示层。
 *
 * <p>功能口径由子类决定（计位 / 计件，注册成两个 DisplaySource，在"信息类型"下拉里各占一项）：
 * {@link #currentOf} / {@link #totalOf} 把同一次 {@link VaultStorageMath.Tally} 折算成该口径的
 * 数量与总量。显示格式由配置行 2 的 {@code Mode} 决定：</p>
 * <ul>
 *   <li>{@code 0 数量/总量}：{@code 当前/总量}；</li>
 *   <li>{@code 1 进度条}：Create 原生 {@code █▓} 进度条（显示板/告示牌/翻牌板同款，翻牌板走
 *       {@code Progress} 布局）；</li>
 *   <li>{@code 2 百分比}：Create 原生 0–100 整数百分比（超出 100% 的加权件数在此压到 100%）。</li>
 * </ul>
 *
 * <p>进度/百分比都按 {@link VaultStorageMath#progress} 压到 [0,1]；"数量/总量"按原始数给出。
 * 读数失败（源方块没有物品 capability、或整台结构所在区块未加载）时不显示任何行。</p>
 */
public abstract class VaultStorageDisplaySource extends PercentOrProgressBarDisplaySource {

    private static final int MODE_PROGRESS_BAR = 1;
    private static final int MODE_PERCENT = 2;

    /** 该口径的"数量"。 */
    protected abstract long currentOf(VaultStorageMath.Tally tally);

    /** 该口径的"总量"。 */
    protected abstract long totalOf(VaultStorageMath.Tally tally);

    @Nullable
    private static VaultStorageMath.Tally tally(DisplayLinkContext context) {
        return VaultInventoryReader.tally(context.level(), context.getSourcePos());
    }

    private static int mode(DisplayLinkContext context) {
        return context.sourceConfig().getInt("Mode");
    }

    @Override
    @Nullable
    protected Float getProgress(DisplayLinkContext context) {
        VaultStorageMath.Tally tally = tally(context);
        if (tally == null) {
            return null;
        }
        long total = totalOf(tally);
        if (total <= 0) {
            return null;
        }
        return VaultStorageMath.progress(currentOf(tally), total);
    }

    @Override
    protected MutableComponent formatNumeric(DisplayLinkContext context, Float currentLevel) {
        if (mode(context) == MODE_PERCENT) {
            return super.formatNumeric(context, currentLevel);
        }
        VaultStorageMath.Tally tally = tally(context);
        if (tally == null) {
            return EMPTY_LINE;
        }
        return Component.literal(currentOf(tally) + "/" + totalOf(tally));
    }

    @Override
    protected boolean progressBarActive(DisplayLinkContext context) {
        return mode(context) == MODE_PROGRESS_BAR;
    }

    @Override
    protected boolean allowsLabeling(DisplayLinkContext context) {
        return true;
    }

    @Override
    @OnlyIn(Dist.CLIENT)
    public void initConfigurationWidgets(DisplayLinkContext context, ModularGuiLineBuilder builder, boolean isFirstLine) {
        super.initConfigurationWidgets(context, builder, isFirstLine);
        if (isFirstLine) {
            return;
        }
        builder.addSelectionScrollInput(0, 120, (input, label) -> input
                .forOptions(List.of(
                        Component.translatable(DimBlend.MODID + ".display_source.vault_storage.count"),
                        Component.translatable(DimBlend.MODID + ".display_source.vault_storage.progress_bar"),
                        Component.translatable(DimBlend.MODID + ".display_source.vault_storage.percent")))
                .titled(Component.translatable(DimBlend.MODID + ".display_source.vault_storage.display")),
                "Mode");
    }
}
