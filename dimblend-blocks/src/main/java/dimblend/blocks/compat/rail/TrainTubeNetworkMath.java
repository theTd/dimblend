package dimblend.blocks.compat.rail;

/**
 * 列车加压管道网络的纯数值规则（锁定判定 #2 / DEFAULT #12）。
 *
 * <p>刻意<b>不</b>在入参里出现 {@code FluidStack}：本类只吃"量"与"是否同种流体（含
 * DataComponents）"两个事实，真正的 {@code FluidStack.isSameFluidSameComponents}
 * 判断留在方块实体层。这样规则可以在单测里被钉死，而不用在无注册表的环境里造 FluidStack。</p>
 *
 * <p>核心是<b>换液规则</b>：池里是 A、往里灌 B 时，{@code SIMULATE} 必须返回
 * {@code min(100, B.amount)}（&gt; 0，否则泵会认为灌不进去而卡死），{@code EXECUTE}
 * 则先清空再灌入。</p>
 */
public final class TrainTubeNetworkMath {

    /** 整条网络（含跨车厢端点）共用的容量，单位 mB。 */
    public static final int CAPACITY_MB = 100;

    /** 跨车厢锚点的最大世界距离（方块）。 */
    public static final int MAX_LINK_DISTANCE = 16;

    /** 端点的本地连接数必须恰好为 1 才能当锚点。 */
    public static final int ENDPOINT_LOCAL_CONNECTIONS = 1;

    /** 是否同种流体（含全部 DataComponents）：由调用方算好传进来。 */
    public static int simulateFill(int poolAmount, boolean sameFluid, int incomingAmount) {
        if (incomingAmount <= 0) {
            return 0;
        }
        if (poolAmount <= 0) {
            return Math.min(CAPACITY_MB, incomingAmount);
        }
        if (!sameFluid) {
            // 换液：模拟阶段必须给出正数，否则泵会判定"灌不进去"
            return Math.min(CAPACITY_MB, incomingAmount);
        }
        return Math.max(0, Math.min(CAPACITY_MB - poolAmount, incomingAmount));
    }

    /**
     * 实际灌入的接受量（{@code simulateFill} 的结果）以及灌完之后池里应该是多少。
     * 换液时 {@code poolAmount} 先归零，所以接受量就是灌入量。
     */
    public static int acceptedAmount(int poolAmount, boolean sameFluid, int incomingAmount, int simulated) {
        if (simulated <= 0) {
            return 0;
        }
        int room = sameFluid && poolAmount > 0 ? CAPACITY_MB - poolAmount : CAPACITY_MB;
        return Math.min(simulated, Math.max(0, room));
    }

    /** {@code EXECUTE} 之后池里的量：换液则只剩新液，同种则累加。 */
    public static int poolAmountAfterFill(int poolAmount, boolean sameFluid, int accepted) {
        if (accepted <= 0) {
            return poolAmount;
        }
        int base = sameFluid ? Math.max(0, poolAmount) : 0;
        return Math.min(CAPACITY_MB, base + accepted);
    }

    /** 抽取：最多抽走池里的全部。 */
    public static int drainAmount(int poolAmount, int wanted) {
        if (poolAmount <= 0 || wanted <= 0) {
            return 0;
        }
        return Math.min(poolAmount, wanted);
    }

    /** 端点资格：本地连接数恰好 1 才可当跨车厢锚点。 */
    public static boolean isValidEndpoint(int localConnectionCount) {
        return localConnectionCount == ENDPOINT_LOCAL_CONNECTIONS;
    }

    /** 两个锚点是否在允许的跨车厢距离内（按方块中心计算，平方比较避免开方）。 */
    public static boolean withinLinkDistance(double dx, double dy, double dz) {
        double limit = MAX_LINK_DISTANCE;
        return dx * dx + dy * dy + dz * dz <= limit * limit;
    }

    /** 拓扑变更（加/拆方块、建/断跨车连接）一律清空，两个方向的池都归零。 */
    public static int clearOnTopologyChange() {
        return 0;
    }

    private TrainTubeNetworkMath() {
    }
}
