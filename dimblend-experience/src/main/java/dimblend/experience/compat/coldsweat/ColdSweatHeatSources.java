package dimblend.experience.compat.coldsweat;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.jesz.createdieselgenerators.content.diesel_engine.huge.HugeDieselEngineBlock;
import com.jesz.createdieselgenerators.content.diesel_engine.huge.HugeDieselEngineBlockEntity;
import com.jesz.createdieselgenerators.content.diesel_engine.huge.PoweredEngineShaftBlockEntity;
import com.jesz.createdieselgenerators.content.diesel_engine.modular.ModularDieselEngineBlockEntity;
import com.jesz.createdieselgenerators.content.diesel_engine.normal.DieselEngineBlockEntity;
import com.momosoftworks.coldsweat.api.event.core.registry.BlockTempRegisterEvent;
import com.momosoftworks.coldsweat.api.registry.BlockTempRegistry;
import com.momosoftworks.coldsweat.api.temperature.block_temp.BlockTemp;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.block.state.properties.Property;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.common.NeoForge;

/**
 * 给 Cold Sweat 补"热源"：烈焰人炉标准的两档 BlockTemp，挂到原整合包里会发热但 Cold Sweat
 * 不认识的那批方块上。
 *
 * <p>数值基准取自 {@code TrainTripWorld/config/coldsweat/world.toml} 的烈焰人炉条目，并按
 * Cold Sweat 2.4.2 的 {@code Temperature.convert} 复算（F→MC = (F − 32[绝对]) / 45）：</p>
 * <ul>
 *   <li>普通加热（kindled）：temp 20 °F = {@code 20/45} MC，range 5，maxEffect 45 °F = 1 MC，
 *       minEffect −1 MC，maxTemp 400 °F = {@code (400−32)/45} ≈ 8.1778 MC，minTemp −∞</li>
 *   <li>超级加热（seething）：temp 30 °F = {@code 30/45} MC，range 6，maxEffect 60 °F = 4/3 MC，
 *       minEffect −4/3 MC，其余同上</li>
 * </ul>
 *
 * <p><b>fade=true 的语义（已对 2.4.2 字节码核实）</b>：{@code BlockTempModifier} 在拿到
 * {@code getTemperature} 的非零返回值后自己做
 * {@code CSMath.blend(temp, 0, distance, 0.5, range)} —— 距离 ≤ 0.5 取满，≥ range 归零，
 * 中间线性。所以这里 {@code getTemperature} 必须返回<b>平坦值</b>，按 distance 再衰减一次
 * 会让实际强度变成平方衰减。</p>
 *
 * <p><b>同一方块的多个 BlockTemp</b>：{@code BlockTempRegistry.MAPPED_BLOCKS} 是
 * {@code Multimap<Block, BlockTemp>}，{@code getBlockTempsFor} 返回整列，
 * {@code BlockTempModifier} 对每一项单独调 {@code isValid}。可控燃烧室的 kindled/seething
 * 因此可以拆成两条 range 不同的条目，不需要在一个 {@code getTemperature} 里按 distance 手写
 * 两档区间（那样会绕开 Cold Sweat 的 maxEffect 累计口径）。</p>
 *
 * <p><b>只读不加载区块</b>：温度查询每 40 tick 会对玩家周围每个方块跑一次，任何
 * {@code level.getBlockEntity(pos)} 前的 {@link Level#hasChunk} 守卫都是必需的——漏一个就会
 * 在玩家贴边时同步加载/生成区块。</p>
 */
public final class ColdSweatHeatSources {

    /** 普通加热烈焰人炉标准（kindled blaze burner）。 */
    public static final HeatProfile KINDLED = new HeatProfile(
            fahrenheitToMc(20.0, false),
            5.0,
            -fahrenheitToMc(45.0, false),
            fahrenheitToMc(45.0, false),
            Double.NEGATIVE_INFINITY,
            fahrenheitToMc(400.0, true),
            true);

    /** 超级加热烈焰人炉标准（seething blaze burner）。 */
    public static final HeatProfile SEETHING = new HeatProfile(
            fahrenheitToMc(30.0, false),
            6.0,
            -fahrenheitToMc(60.0, false),
            fahrenheitToMc(60.0, false),
            Double.NEGATIVE_INFINITY,
            fahrenheitToMc(400.0, true),
            true);

    /**
     * 炉灶显式点名（其余同后缀方块在注册表里扫描补全）。
     *
     * <p>存字符串而不是 {@code ResourceLocation}：静态初始化必须零 MC 依赖——{@code ResourceLocation}
     * 自己的 {@code <clinit>} 拉 netty，冷加载路径越短越好（本类在 Cold Sweat 缺席时也要能初始化）。</p>
     */
    private static final List<String> EXPLICIT_STOVES = List.of(
            "kaleidoscope_cookery:stove",
            "kaleidoscope_nether:nether_stove",
            "kaleidoscope_sculk:deepslate_stove",
            "kaleidoscope_twilight:twilight_stove",
            "kaleidoscope_end:end_stove");

    /**
     * 一份 {@code BlockTemp} 的完整参数（顺序与 {@code BlockTemp} 的 7 参构造一致：
     * minEffect, maxEffect, minTemperature, maxTemperature, range, fade）。
     */
    public record HeatProfile(double temperatureMc,
                              double range,
                              double minEffectMc,
                              double maxEffectMc,
                              double minTemperatureMc,
                              double maxTemperatureMc,
                              boolean fade) {
    }

    /** 某方块此刻是否算"点着/在转"（{@code BlockTemp#isValid} 的口径）。 */
    @FunctionalInterface
    public interface StateGate {
        boolean test(Level level, BlockPos pos, BlockState state);
    }

    /** Fahrenheit → Cold Sweat MC 温度单位（F→MC = (F − 32[绝对]) / 45）。 */
    public static double fahrenheitToMc(double fahrenheit, boolean absolute) {
        return (fahrenheit - (absolute ? 32.0 : 0.0)) / 45.0;
    }

    /** 由 {@code DimBlend} 在构造期调用一次：订阅 Cold Sweat 的热源注册事件。 */
    public static void register() {
        if (!ModList.get().isLoaded("cold_sweat")) {
            return;
        }
        NeoForge.EVENT_BUS.addListener(ColdSweatHeatSources::onRegisterBlockTemps);
    }

    private static void onRegisterBlockTemps(BlockTempRegisterEvent event) {
        Block basicBurner = block("createlowheated", "basic_burner");
        if (basicBurner != null) {
            event.register(ColdSweatHeatBlockTemps.kindled(ColdSweatHeatSources::isLit, basicBurner));
        }

        registerStoves(event);

        Block shawarmaSpit = block("kaleidoscope_cookery", "shawarma_spit");
        if (shawarmaSpit != null) {
            event.register(ColdSweatHeatBlockTemps.kindled(ColdSweatHeatSources::isPowered, shawarmaSpit));
        }

        Block grill = block("kaleidoscope_grilling", "grill");
        if (grill != null) {
            event.register(ColdSweatHeatBlockTemps.kindled(ColdSweatHeatSources::isLit, grill));
        }

        // CDG 类型只在这个内部类的方法体里出现：模组缺席时 Cdg 类不会被加载。
        if (ModList.get().isLoaded("createdieselgenerators")) {
            Cdg.register(event);
        }
    }

    private static void registerStoves(BlockTempRegisterEvent event) {
        Set<Block> stoves = new LinkedHashSet<>();
        for (String id : EXPLICIT_STOVES) {
            Block stove = block(ResourceLocation.parse(id));
            if (stove != null) {
                stoves.add(stove);
            }
        }

        for (var entry : BuiltInRegistries.BLOCK.entrySet()) {
            ResourceLocation id = entry.getKey().location();
            String path = id.getPath();
            if (!path.equals("stove") && !path.endsWith("_stove")) {
                continue;
            }
            Block candidate = entry.getValue();
            // 已经被 Cold Sweat 自己覆盖的方块（原版熔炉/烟熏炉等）不再叠加一份热源。
            if (BlockTempRegistry.MAPPED_BLOCKS.containsKey(candidate)) {
                continue;
            }
            if (booleanProperty(candidate.defaultBlockState(), "lit") == null) {
                continue;
            }
            stoves.add(candidate);
        }

        if (!stoves.isEmpty()) {
            event.register(ColdSweatHeatBlockTemps.kindled(ColdSweatHeatSources::isLit, stoves.toArray(new Block[0])));
        }
    }

    /** {@code lit=true}；属性按<b>名字</b>查，兼容 {@code BlockStateProperties.LIT} 与自定义同名属性。 */
    public static boolean isLit(Level level, BlockPos pos, BlockState state) {
        return isTrue(state, "lit");
    }

    /** {@code powered=true}；仅读自身方块状态属性，绝不跨格访问邻区块。 */
    public static boolean isPowered(Level level, BlockPos pos, BlockState state) {
        return isTrue(state, "powered");
    }

    static boolean isTrue(BlockState state, String propertyName) {
        BooleanProperty property = booleanProperty(state, propertyName);
        return property != null && state.getValue(property);
    }

    static BooleanProperty booleanProperty(BlockState state, String propertyName) {
        Property<?> property = state.getBlock().getStateDefinition().getProperty(propertyName);
        return property instanceof BooleanProperty booleanProperty ? booleanProperty : null;
    }

    /** 枚举属性的序列化名（Create 的 {@code blaze} 等），非枚举属性返回 {@code null}。 */
    static String enumValueName(BlockState state, String propertyName) {
        Property<?> property = state.getBlock().getStateDefinition().getProperty(propertyName);
        if (!(property instanceof EnumProperty<?>)) {
            return null;
        }
        Comparable<?> value = getUnchecked(state, property);
        if (value instanceof StringRepresentable serializable) {
            return serializable.getSerializedName();
        }
        return value == null ? null : String.valueOf(value);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Comparable<?> getUnchecked(BlockState state, Property<?> property) {
        return state.getValue((Property) property);
    }

    /**
     * 非阻塞取已完全晋升 {@code ChunkStatus.FULL} 的 {@link BlockEntity}。
     * 注意服务端 {@code Level#hasChunk} 仅检查 {@code ticketLevel <= 33}，
     * 后台仍在生成的区块随后调 {@code level.getBlockEntity} 会进 {@code managedBlock} 阻塞主线程。
     */
    static BlockEntity loadedBlockEntity(Level level, BlockPos pos) {
        LevelChunk chunk = level.getChunkSource().getChunkNow(
                SectionPos.blockToSectionCoord(pos.getX()),
                SectionPos.blockToSectionCoord(pos.getZ()));
        return chunk == null ? null : chunk.getBlockEntity(pos);
    }

    private static Block block(String namespace, String path) {
        return block(ResourceLocation.fromNamespaceAndPath(namespace, path));
    }

    private static Block block(ResourceLocation id) {
        return BuiltInRegistries.BLOCK.containsKey(id) ? BuiltInRegistries.BLOCK.get(id) : null;
    }

    /**
     * CDG 相关热源。整类只在 {@code createdieselgenerators} 在场时被加载，
     * 所以下面每个方法体里对 CDG 类型的引用在缺席环境里永远不会解析。
     *
     * <p>三种柴油机都发普通加热标准（kindled）。运转判据：已供油 + 油门 &gt; 0 + 未过载；
     * 巨型机还要确认动力轴真的在转（{@code HugeDieselEngineBlockEntity} 本身不产出应力，
     * 转速在 {@code pos.relative(FACING, 2)} 的轴上）。</p>
     */
    private static final class Cdg {

        private static void register(BlockTempRegisterEvent event) {
            Block diesel = block("createdieselgenerators", "diesel_engine");
            if (diesel != null) {
                event.register(ColdSweatHeatBlockTemps.kindled(Cdg::runningDieselEngine, diesel));
            }

            Block modular = block("createdieselgenerators", "modular_diesel_engine");
            if (modular != null) {
                event.register(ColdSweatHeatBlockTemps.kindled(Cdg::runningModularEngine, modular));
            }

            Block huge = block("createdieselgenerators", "huge_diesel_engine");
            if (huge != null) {
                event.register(ColdSweatHeatBlockTemps.kindled(Cdg::runningHugeEngine, huge));
            }

            Block burner = block("createdieselgenerators", "burner");
            if (burner != null) {
                // seething 先注册、range 更大；两条按 isValid 互斥，互不叠加。
                event.register(ColdSweatHeatBlockTemps.seething(Cdg::seethingBurner, burner));
                event.register(ColdSweatHeatBlockTemps.kindled(Cdg::kindledBurner, burner));
            }
        }

        private static boolean runningDieselEngine(Level level, BlockPos pos, BlockState state) {
            if (!(loadedBlockEntity(level, pos) instanceof DieselEngineBlockEntity engine)) {
                return false;
            }
            return engine.enabled() && engine.getThrottle() > 0.0F && !engine.isOverStressed();
        }

        private static boolean runningModularEngine(Level level, BlockPos pos, BlockState state) {
            if (!(loadedBlockEntity(level, pos) instanceof ModularDieselEngineBlockEntity engine)) {
                return false;
            }
            var controller = engine;
            if (!engine.isController()) {
                BlockPos controllerPos = engine.getController();
                if (controllerPos == null) {
                    return false;
                }
                if (!(loadedBlockEntity(level, controllerPos)
                        instanceof ModularDieselEngineBlockEntity resolved)) {
                    return false;
                }
                controller = resolved;
            }
            return controller.enabled() && controller.getThrottle() > 0.0F && !controller.isOverStressed();
        }

        private static boolean runningHugeEngine(Level level, BlockPos pos, BlockState state) {
            if (!(loadedBlockEntity(level, pos) instanceof HugeDieselEngineBlockEntity engine)) {
                return false;
            }
            if (!engine.enabled() || engine.getThrottle() <= 0.0F) {
                return false;
            }
            BlockPos shaftPos = pos.relative(state.getValue(
                    HugeDieselEngineBlock.FACING), 2);
            return loadedBlockEntity(level, shaftPos)
                    instanceof PoweredEngineShaftBlockEntity shaft
                    && shaft.getSpeed() != 0.0F
                    && !shaft.isOverStressed();
        }

        private static boolean seethingBurner(Level level, BlockPos pos, BlockState state) {
            return isTrue(state, "lit") && "seething".equals(enumValueName(state, "blaze"));
        }

        private static boolean kindledBurner(Level level, BlockPos pos, BlockState state) {
            return isTrue(state, "lit") && !"seething".equals(enumValueName(state, "blaze"));
        }

        private Cdg() {
        }
    }

    private ColdSweatHeatSources() {
    }
}

/**
 * {@link BlockTemp} 的实体（平坦温度 + 逐状态判定；距离衰减交给 Cold Sweat 的
 * {@code fade} 机制）。
 *
 * <p><b>为什么单独一个类文件（同源文件里的第二个顶层类）而不是 {@code ColdSweatHeatSources}
 * 的内部类</b>：Cold Sweat 是本模块的 {@code compileOnly} 依赖，缺席时
 * {@code ColdSweatHeatSources} 仍必须能被初始化（{@code register()} 的
 * {@code ModList.isLoaded} 门控在方法体里，初始化先于方法体执行）。JVM 校验
 * {@code ColdSweatHeatSources} 时，方法体里出现 {@code new <BlockTemp 子类>} 会强制加载
 * 该子类，进而加载缺席的 {@code BlockTemp} 超类——实测启动即
 * {@code NoClassDefFoundError}。把实例化整体挪进这个类文件后，只有 Cold Sweat 真的在场、
 * 事件真正派发时才会走到这里。</p>
 */
final class ColdSweatHeatBlockTemps {

    static BlockTemp kindled(ColdSweatHeatSources.StateGate gate, Block... blocks) {
        return new Gated(ColdSweatHeatSources.KINDLED, gate, blocks);
    }

    static BlockTemp seething(ColdSweatHeatSources.StateGate gate, Block... blocks) {
        return new Gated(ColdSweatHeatSources.SEETHING, gate, blocks);
    }

    private static final class Gated extends BlockTemp {

        private final double temperatureMc;
        private final ColdSweatHeatSources.StateGate gate;

        private Gated(ColdSweatHeatSources.HeatProfile profile, ColdSweatHeatSources.StateGate gate,
                Block... blocks) {
            // BlockTemp(minEffect, maxEffect, minTemperature, maxTemperature, range, fade, blocks...)
            super(profile.minEffectMc(), profile.maxEffectMc(),
                    profile.minTemperatureMc(), profile.maxTemperatureMc(),
                    profile.range(), profile.fade(), blocks);
            this.temperatureMc = profile.temperatureMc();
            this.gate = gate;
        }

        @Override
        public boolean isValid(Level level, BlockPos pos, BlockState state) {
            return this.gate.test(level, pos, state);
        }

        @Override
        public double getTemperature(Level level, LivingEntity entity, BlockState state, BlockPos pos,
                double distance) {
            return this.temperatureMc;
        }
    }

    private ColdSweatHeatBlockTemps() {
    }
}
