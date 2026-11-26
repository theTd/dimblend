package dimblend.blocks.compat.deployer;

import java.util.List;
import java.util.UUID;

import net.minecraft.nbt.CompoundTag;

/**
 * 卸料仪表面板存档里必须存在的 Create 字段名，以及「把标量写回去」的纯函数。
 *
 * <p><b>为什么需要这一层：</b>Deployer 的 {@code AbstractPanelBehaviour#write} 是这么写的——</p>
 * <ol>
 *   <li>先 {@code invokespecial FactoryPanelBehaviour.write}，把过滤物品、目标值、地址、
 *       网络频率、连线表整份写进 {@code nbt[slotName]}；</li>
 *   <li>只要 {@code active}，就<b>新建</b>一个 {@code CompoundTag}，只塞
 *       {@code Satisfied/PromisedSatisfied/RedstonePowered/Targeting/TargetedBy/TargetedByLinks/TargetedByExtra}
 *       与 {@code easyWrite} 写进去的东西；</li>
 *   <li>最后 {@code nbt.put(slotName, 新tag)}——<b>把第 1 步那份整份替换掉</b>。</li>
 * </ol>
 *
 * <p>结果：{@code Filter} / {@code FilterAmount} / {@code UpTo} / {@code RecipeAddress} 这些
 * Create 字段在存档和客户端同步包里全部丢失。表现出来就是「设了过滤物品，退出重进/重连后过滤槽
 * 空、目标值回 0、地址栏空、仪表不再下料」。{@code easyWrite} 是 Deployer 唯一留给我们的回填钩子
 * （它拿到的正是第 2 步那个新 tag，且在 {@code put} 之前），所以只能在这里补。</p>
 *
 * <p>{@code writeSafe}（蓝图）路径没有这个问题：那条路不会替换 {@code slotName}，
 * 只是额外挂一个顶层 {@code CustomPanels}，所以不动它。</p>
 */
public final class DumpPanelState {

    /** 过滤物品（{@code ItemStack#saveOptional} 的 Tag）。 */
    public static final String FILTER = "Filter";
    /** 目标原始值（0 未启用，1 目标 0，2..100 → units 1..99）。与 {@code FilteringBehaviour#count} 同字段。 */
    public static final String FILTER_AMOUNT = "FilterAmount";
    /** 目标单位是不是「组」。 */
    public static final String UP_TO = "UpTo";
    /** 卸料地址（面板自填）。 */
    public static final String RECIPE_ADDRESS = "RecipeAddress";
    /** Create 的「产出数量」。本仪表不用它，但读写要能原样往返。 */
    public static final String RECIPE_OUTPUT = "RecipeOutput";
    /** 承诺清空间隔。本仪表没有承诺语义，同样只做往返。 */
    public static final String PROMISE_CLEARING_INTERVAL = "PromiseClearingInterval";
    /** 物流网络频率。 */
    public static final String FREQ = "Freq";

    /**
     * {@code easyWrite} 必须补回 {@code slotName} 里的全部键（{@link #FILTER} 由调用方单独写入，
     * 因为它需要一个 {@code ItemStack} 才能编码）。
     */
    public static final List<String> REQUIRED_KEYS = List.of(FILTER, FILTER_AMOUNT, UP_TO, RECIPE_ADDRESS,
            RECIPE_OUTPUT, PROMISE_CLEARING_INTERVAL, FREQ);

    private DumpPanelState() {
    }

    /**
     * 把除了过滤物品以外的标量写进面板 tag。
     *
     * <p>{@code network == null} 时不写 {@link #FREQ}：Create 的 {@code FactoryPanelBehaviour#read}
     * 只在 {@code hasUUID(Freq)} 为真时才覆盖网络字段，而 {@code CompoundTag#putUUID} 传 null 会抛 NPE。</p>
     */
    public static void putScalars(CompoundTag tag, int count, boolean upTo, String address, int recipeOutput,
            int promiseClearingInterval, UUID network) {
        tag.putInt(FILTER_AMOUNT, count);
        tag.putBoolean(UP_TO, upTo);
        tag.putString(RECIPE_ADDRESS, address == null ? "" : address);
        tag.putInt(RECIPE_OUTPUT, recipeOutput);
        tag.putInt(PROMISE_CLEARING_INTERVAL, promiseClearingInterval);
        if (network != null) {
            tag.putUUID(FREQ, network);
        }
    }
}
