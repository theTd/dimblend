package dimblend.experience.gametest;

import java.util.List;

import com.simibubi.create.content.kinetics.deployer.DeployerFakePlayer;

import dimblend.experience.Config;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * G3 配套冰放置禁令的机械手半边：{@link DeployerFakePlayer} 经 {@code ItemStack#useOn}
 * 放冰/浮冰/蓝冰被拦，放普通方块不受影响。引用 Create 类型，仅 Create 在场时注册。
 * 用例体同步执行，finally 还原配置（同 {@link LimitedWaterGameTests}）。
 */
@GameTestHolder("dimblend_experience")
@PrefixGameTestTemplate(false)
public final class DeployerIcePlacementGameTests {

    @GameTest(template = "item_drain_refill", templateNamespace = "dimblend_experience",
            timeoutTicks = 100, batch = "limited_water")
    public static void deployerCannotPlaceIce(GameTestHelper helper) {
        String rotatingId = Config.ROTATING_DIMENSION_ID.get();
        boolean iceBan = Config.ICE_PLACEMENT_BAN.get();
        Config.ROTATING_DIMENSION_ID.set("minecraft:overworld");
        Config.ICE_PLACEMENT_BAN.set(true);
        try {
            DeployerFakePlayer deployer = new DeployerFakePlayer(helper.getLevel(), null);
            // 对照组先行：机械手在本环境能放普通方块，才说明下面放冰失败来自禁令
            LimitedWaterGameTests.Placement stone = LimitedWaterGameTests.tryPlaceIntoAir(helper, deployer, Items.STONE);
            helper.assertTrue(stone.placed(), "the Deployer still places other blocks (" + stone + ")");
            for (Item ice : List.of(Items.ICE, Items.PACKED_ICE, Items.BLUE_ICE)) {
                LimitedWaterGameTests.Placement placement = LimitedWaterGameTests.tryPlaceIntoAir(helper, deployer, ice);
                helper.assertFalse(placement.placed(), "the Deployer must not place " + ice + " (" + placement + ")");
            }
            helper.succeed();
        } finally {
            Config.ROTATING_DIMENSION_ID.set(rotatingId);
            Config.ICE_PLACEMENT_BAN.set(iceBan);
        }
    }

    private DeployerIcePlacementGameTests() {
    }
}
