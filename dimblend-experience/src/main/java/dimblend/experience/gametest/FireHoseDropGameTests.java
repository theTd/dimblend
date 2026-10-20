package dimblend.experience.gametest;

import java.lang.reflect.Method;
import java.util.List;

import com.mikoalopex.createfirefightingadd.CreateFireFightingAdd;
import com.mikoalopex.createfirefightingadd.content.blocks.fire_hose.PlaceFireHosePacket;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * 消防水带「放置/拆除数量守恒」运行时验证（2026-10-20 用户报告 4 倍掉落）：
 * 每个水带端点方块恒定「放 1 个 = 吃 1 个、拆 1 个 = 还 1 个」。需要 Fire Fighting Add
 * 进 dev 运行：{@code ./gradlew :dimblend-experience:runGameTestServer -PwithFireHose}；
 * 仅该 addon 在场时由 {@code DimBlend#onRegisterGameTests} 显式注册。模板复用
 * {@code item_drain_refill}（3×3×3 全空平台，地面 y=0），端点放工作层 y=1。
 *
 * <p>两条用例分别锁定修复的两半：{@code singleEndpointBreakDropsOne} 打「每个方块
 * 掉两份」（战利品表 1 + 方块实体 {@code remove()} 自弹 1，见
 * {@code FireHoseExtraDropMixin}）；{@code pairRoundTrip...} 打「第二个端点免单」
 * （{@code consumeItem=false}，见 {@code FireHosePlacementCostMixin}）——修复前该用例
 * 实测「2 个方块只吃 1 个、拆出 4 个」，即用户看到的 4 倍。</p>
 *
 * <p>不加 {@code @GameTestHolder}：NeoForge 会按扫描数据自动注册所有带该注解的类，
 * addon 缺席时加载本类即 {@code NoClassDefFoundError}。</p>
 */
@PrefixGameTestTemplate(false)
public final class FireHoseDropGameTests {

    private static final BlockPos CENTER = new BlockPos(1, 1, 1);
    private static final BlockPos ENDPOINT_A = new BlockPos(0, 1, 0);
    private static final BlockPos ENDPOINT_B = new BlockPos(2, 1, 2);

    private FireHoseDropGameTests() {
    }

    @GameTest(template = "item_drain_refill", templateNamespace = "dimblend_experience", timeoutTicks = 100)
    public static void singleEndpointBreakDropsOne(GameTestHelper helper) {
        helper.setBlock(CENTER, CreateFireFightingAdd.FIRE_HOSE.get().defaultBlockState());
        BlockPos abs = helper.absolutePos(CENTER);
        helper.getLevel().destroyBlock(abs, true);
        helper.runAfterDelay(2, () -> {
            int dropped = countHoseDrops(helper, abs);
            helper.assertTrue(dropped == 1,
                    "one hose endpoint must drop exactly one hose item (was 2 before the fix), got "
                            + dropped);
            helper.succeed();
        });
    }

    @GameTest(template = "item_drain_refill", templateNamespace = "dimblend_experience", timeoutTicks = 100)
    public static void pairRoundTripKeepsOneToOne(GameTestHelper helper) {
        ServerPlayer player = GameTestPlayers.create(helper, GameType.SURVIVAL,
                Vec3.atBottomCenterOf(helper.absolutePos(CENTER.above(2))));
        ItemStack held = new ItemStack(CreateFireFightingAdd.FIRE_HOSE_ITEM.get(), 2);
        // 与真实链路同参：第一头 PLACE_ENDPOINT（consumeItem=true），第二头由客户端按
        // PLACE_AND_CONNECT_FREE 发 consumeItem=false；两条都进同一私有 placeEndpoint
        helper.assertTrue(placeEndpoint(helper, player, held, ENDPOINT_A, true) != null,
                "first endpoint must be placed");
        helper.assertTrue(placeEndpoint(helper, player, held, ENDPOINT_B, false) != null,
                "second (free-flag) endpoint must be placed");
        helper.assertTrue(held.getCount() == 0,
                "two placed endpoints must consume two hose items, remaining " + held.getCount());
        helper.getLevel().destroyBlock(helper.absolutePos(ENDPOINT_A), true);
        helper.getLevel().destroyBlock(helper.absolutePos(ENDPOINT_B), true);
        helper.runAfterDelay(2, () -> {
            int returned = countHoseDrops(helper, helper.absolutePos(CENTER));
            helper.assertTrue(returned == 2,
                    "removal must return exactly what placement consumed (2 items), got " + returned);
            helper.succeed();
        });
    }

    /**
     * 直调 addon 私有静态 {@code PlaceFireHosePacket#placeEndpoint}：真实放置链路里客户端
     * 只发 action 号，服务端就是这条方法落方块+扣费，故用例无需伪造 {@code IPayloadContext}。
     * 方法签名变化时抛错而非静默跳过（addon 升级必须重新核实）。
     */
    private static Object placeEndpoint(GameTestHelper helper, ServerPlayer player, ItemStack hose,
            BlockPos relTarget, boolean consumeItem) {
        BlockPos clicked = helper.absolutePos(relTarget.below());
        try {
            return PLACE_ENDPOINT.invoke(null, helper.getLevel(), player, hose, clicked, Direction.UP,
                    consumeItem);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("createfirefightingadd placeEndpoint no longer matches", e);
        }
    }

    private static final Method PLACE_ENDPOINT = resolvePlaceEndpoint();

    private static Method resolvePlaceEndpoint() {
        try {
            Method method = PlaceFireHosePacket.class.getDeclaredMethod("placeEndpoint", Level.class,
                    ServerPlayer.class, ItemStack.class, BlockPos.class, Direction.class, boolean.class);
            method.setAccessible(true);
            return method;
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("createfirefightingadd placeEndpoint signature changed", e);
        }
    }

    private static int countHoseDrops(GameTestHelper helper, BlockPos absCenter) {
        ServerLevel level = helper.getLevel();
        List<ItemEntity> drops = level.getEntitiesOfClass(ItemEntity.class,
                AABB.ofSize(Vec3.atCenterOf(absCenter), 8.0, 8.0, 8.0),
                entity -> entity.getItem().is(CreateFireFightingAdd.FIRE_HOSE_ITEM.get()));
        int count = 0;
        for (ItemEntity drop : drops) {
            count += drop.getItem().getCount();
        }
        return count;
    }
}
