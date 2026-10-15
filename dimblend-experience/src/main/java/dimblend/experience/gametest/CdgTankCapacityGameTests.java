package dimblend.experience.gametest;

import com.jesz.createdieselgenerators.CDGBlocks;
import com.jesz.createdieselgenerators.content.diesel_engine.modular.ModularDieselEngineBlockEntity;
import com.jesz.createdieselgenerators.content.diesel_engine.normal.DieselEngineBlockEntity;

import dimblend.experience.Config;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * B10 柴油机小号/中号燃料容量（{@code CdgEngineTankCapacityMixin}）运行时验证：
 * 普通/组合式引擎 BE 初始化后油箱容量 == 配置值；配置 1000 恢复 CDG 原生。
 * 需要 CDG 进 dev 运行：{@code ./gradlew :dimblend-experience:runGameTestServer -PwithCdg}；
 * 仅 CDG 在场时注册。模板复用 {@code item_drain_refill}（3×3×3 全空平台），工作层 y=1。
 *
 * <p>容量在 BE 初始化（普通 addBehaviours / 组合式构造器）即定型，setBlock 放置
 * 即可观测——本用例锁 BE 初始化路径，无需 EntityPlaceEvent（组合式 read 追认路径
 * 与构造器同值，不另测）。</p>
 *
 * <p>不加 {@code @GameTestHolder}：NeoForge 会按扫描数据自动注册所有带该注解的类，
 * 无 CDG 时加载本类即 NoClassDefFoundError；只由 DimBlend 在 CDG 在场时显式注册。</p>
 */
@PrefixGameTestTemplate(false)
public final class CdgTankCapacityGameTests {

    private static final BlockPos MID = new BlockPos(1, 1, 1);

    private CdgTankCapacityGameTests() {
    }

    @GameTest(template = "item_drain_refill", templateNamespace = "dimblend_experience", timeoutTicks = 40)
    public static void normalEngineTankUsesConfiguredCapacity(GameTestHelper helper) {
        int previous = Config.DIESEL_ENGINE_FUEL_TANK_MB.get();
        Config.DIESEL_ENGINE_FUEL_TANK_MB.set(100);
        // 普通引擎油箱在 BE 初始化（addBehaviours）创建：setBlock 即定型
        helper.setBlock(MID, CDGBlocks.DIESEL_ENGINE.get().defaultBlockState());
        helper.runAfterDelay(2, () -> {
            try {
                if (helper.getBlockEntity(MID) instanceof DieselEngineBlockEntity engine) {
                    helper.assertTrue(engine.getTank().getCapacity() == 100,
                            "normal engine tank capacity must follow dieselEngineFuelTankMb, got "
                                    + engine.getTank().getCapacity());
                } else {
                    helper.fail("precondition: normal engine BE must exist");
                }
            } finally {
                Config.DIESEL_ENGINE_FUEL_TANK_MB.set(previous);
            }
            helper.succeed();
        });
    }

    @GameTest(template = "item_drain_refill", templateNamespace = "dimblend_experience", timeoutTicks = 40)
    public static void modularEngineTankUsesConfiguredCapacity(GameTestHelper helper) {
        int previous = Config.DIESEL_ENGINE_FUEL_TANK_MB.get();
        Config.DIESEL_ENGINE_FUEL_TANK_MB.set(100);
        // 组合式油箱容量在构造器定型（控制器 read 追认路径同值，不另测）
        helper.setBlock(MID, CDGBlocks.MODULAR_DIESEL_ENGINE.get().defaultBlockState());
        helper.runAfterDelay(2, () -> {
            try {
                if (helper.getBlockEntity(MID) instanceof ModularDieselEngineBlockEntity engine) {
                    helper.assertTrue(engine.getTank().getCapacity() == 100,
                            "modular engine tank capacity must follow dieselEngineFuelTankMb, got "
                                    + engine.getTank().getCapacity());
                } else {
                    helper.fail("precondition: modular engine BE must exist");
                }
            } finally {
                Config.DIESEL_ENGINE_FUEL_TANK_MB.set(previous);
            }
            helper.succeed();
        });
    }

    @GameTest(template = "item_drain_refill", templateNamespace = "dimblend_experience", timeoutTicks = 40)
    public static void configured1000RestoresCdgNativeCapacity(GameTestHelper helper) {
        int previous = Config.DIESEL_ENGINE_FUEL_TANK_MB.get();
        Config.DIESEL_ENGINE_FUEL_TANK_MB.set(1000);
        helper.setBlock(MID, CDGBlocks.DIESEL_ENGINE.get().defaultBlockState());
        helper.runAfterDelay(2, () -> {
            try {
                if (helper.getBlockEntity(MID) instanceof DieselEngineBlockEntity engine) {
                    helper.assertTrue(engine.getTank().getCapacity() == 1000,
                            "dieselEngineFuelTankMb=1000 must restore CDG native capacity, got "
                                    + engine.getTank().getCapacity());
                } else {
                    helper.fail("precondition: normal engine BE must exist");
                }
            } finally {
                Config.DIESEL_ENGINE_FUEL_TANK_MB.set(previous);
            }
            helper.succeed();
        });
    }
}
