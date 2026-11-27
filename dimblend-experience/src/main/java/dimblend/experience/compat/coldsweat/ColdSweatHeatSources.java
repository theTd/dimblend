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
import net.neoforged.bus.api.EventPriority;
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
 * <p><b>fade 与 logarithmic 是两件事（已对 2.4.2 字节码核实）</b>：{@code fade=true} 时
 * {@code BlockTempModifier} 在拿到 {@code getTemperature} 的非零返回值后自己做
 * {@code CSMath.blend(temp, 0, distance, 0.5, range)} —— 距离 ≤ 0.5 取满、≥ range 归零，
 * 中间线性衰减；{@code logarithmic=true} 则是<b>同一 BlockTemp 多格叠加</b>的曲线
 * {@code newTotal = (oldTotal^(1/0.52) + tempToAdd)^0.52}，与距离无关。所以这里
 * {@code getTemperature} 必须返回<b>平坦值</b>，按 distance 再衰减一次会让实际强度变成平方衰减。</p>
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
     * 岩浆等价热源（{@code world.toml:242} 的 {@code minecraft:lava} 同一组数）：
     * 温度 30/45、range 7.0、minEffect −200/45、maxEffect 200/45、
     * minTemp −∞、maxTemp (1000 − 32)/45、fade true、<b>logarithmic true</b>。
     *
     * <p>这是 1× 岩浆基准（2026-11-27 起只作为 {@link #STEAM_ENGINE_EXHAUST} 的底稿）。
     * {@code fade} 是线性距离衰减，{@code logarithmic} 是 {@code ^0.52} 的多格叠加曲线
     * （见类注释），两者都照 {@code world.toml} 的岩浆条目。</p>
     */
    public static final HeatProfile LAVA_EQUIVALENT = new HeatProfile(
            fahrenheitToMc(30.0, false),
            7.0,
            -fahrenheitToMc(200.0, false),
            fahrenheitToMc(200.0, false),
            Double.NEGATIVE_INFINITY,
            fahrenheitToMc(1000.0, true),
            true,
            true);

    /** Cold Sweat 2.4.2 {@code BlockTempModifier#LOG_FACTOR}：logarithmic 叠加指数。 */
    public static final double COLD_SWEAT_LOGARITHMIC_EXPONENT = 0.52;

    /** 蒸汽引擎过载排气的等效岩浆倍数（2026-11-27 用户口径：岩浆温度 × 8）。 */
    public static final double STEAM_ENGINE_EXHAUST_LAVA_MULTIPLIER = 8.0;

    /**
     * 把一条 logarithmic 热源的<b>最终效应</b>按 {@code effectMultiplier} 等比放大。
     *
     * <p>Cold Sweat 的 logarithmic 分支累加的是
     * {@code newTotal = (oldTotal^(1/e) + tempToAdd)^e}（{@code e = }{@link #COLD_SWEAT_LOGARITHMIC_EXPONENT}），
     * 所以单看某一格 {@code tempToAdd} 乘 {@code m}，实际温升只有 {@code m^e} 倍（8× 会变成
     * {@code 8^0.52 ≈ 2.95×}）。先把温度乘 {@code m^(1/e)}，则任一步
     * {@code (m^(1/e) · t)^e = m · t^e}——整条叠加序列都是基准的 {@code m} 倍；再把
     * minEffect / maxEffect 同步乘 {@code m}，clamp 边界也保持同一比例，于是任意距离、
     * 任意引擎数、任意遮挡格数下最终效应都精确是基准的 {@code m} 倍。</p>
     *
     * <p>range、温度上下限、{@code fade}/{@code logarithmic} 原样继承。</p>
     */
    public static HeatProfile scaleLogarithmicEffect(HeatProfile base, double effectMultiplier) {
        return new HeatProfile(
                base.temperatureMc() * Math.pow(effectMultiplier, 1.0 / COLD_SWEAT_LOGARITHMIC_EXPONENT),
                base.range(),
                base.minEffectMc() * effectMultiplier,
                base.maxEffectMc() * effectMultiplier,
                base.minTemperatureMc(),
                base.maxTemperatureMc(),
                base.fade(),
                base.logarithmic());
    }

    /**
     * 蒸汽引擎过载排气：岩浆等效热量的 8 倍（用户口径「岩浆温度 × 8」）。
     *
     * <p>温度与 effect 上下限已按 logarithmic 曲线补偿（见
     * {@link #scaleLogarithmicEffect}），实际 Cold Sweat 温升是 {@link #LAVA_EQUIVALENT} 的
     * 精确 8 倍，而不是只把输入温度乘 8 得到的 {@code 8^0.52} 倍。</p>
     */
    public static final HeatProfile STEAM_ENGINE_EXHAUST = scaleLogarithmicEffect(
            LAVA_EQUIVALENT, STEAM_ENGINE_EXHAUST_LAVA_MULTIPLIER);

    /**
     * 一份 {@code BlockTemp} 的完整参数（顺序与 {@code BlockTemp} 的 8 参构造一致：
     * minEffect, maxEffect, minTemperature, maxTemperature, range, fade, logarithmic）。
     *
     * <p>7 参便捷构造默认 {@code logarithmic = false}（烈焰人炉/炉灶那条线沿用线性衰减）。</p>
     */
    public record HeatProfile(double temperatureMc,
                              double range,
                              double minEffectMc,
                              double maxEffectMc,
                              double minTemperatureMc,
                              double maxTemperatureMc,
                              boolean fade,
                              boolean logarithmic) {

        public HeatProfile(double temperatureMc,
                           double range,
                           double minEffectMc,
                           double maxEffectMc,
                           double minTemperatureMc,
                           double maxTemperatureMc,
                           boolean fade) {
            this(temperatureMc, range, minEffectMc, maxEffectMc, minTemperatureMc, maxTemperatureMc,
                    fade, false);
        }
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

    /**
     * 由 {@code DimBlend} 在构造期调用一次：订阅 Cold Sweat 的热源注册事件。
     *
     * <p><b>为什么是 {@link EventPriority#LOWEST}</b>：同一优先级内监听器按模组加载顺序派发，
     * {@code create_cold_sweat} 的 modId 字母序排在 {@code dimblend_experience} 之前，用默认
     * {@code NORMAL} 注册时它排在前面、先占住 {@code create:steam_engine}。本模组必须晚于所有
     * {@code NORMAL} 监听执行，才能保证 {@link Create} 的 {@code registerFirst} 真的把条目插到
     * 下标 0——根因（{@code BlockTempModifier} 命中首个非零 {@code BlockTemp} 即 {@code break}）
     * 见 {@link Create} 的类注释。</p>
     */
    public static void register() {
        if (!ModList.get().isLoaded("cold_sweat")) {
            return;
        }
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST,
                ColdSweatHeatSources::onRegisterBlockTemps);
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

        // 同理：Create 类型只在 Create 内部类里出现。
        if (ModList.get().isLoaded("create")) {
            Create.register(event);
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
     * Create 蒸汽引擎热源。整类只在 {@code create} 在场时被加载。
     *
     * <p>只有<b>排气窗口</b>（过热 → 断轴之后那 32 秒）的引擎算热源，而且是
     * <b>8× 岩浆</b>（{@link ColdSweatHeatSources#STEAM_ENGINE_EXHAUST}，由 {@link ColdSweatHeatSources#LAVA_EQUIVALENT} 经
     * {@link ColdSweatHeatSources#scaleLogarithmicEffect} 补偿 {@code ^0.52} 后得到）：断轴的引擎在喷高温蒸汽，
     * 玩家贴上去要付出中暑代价。正常运转/停转的引擎<b>不</b>发热（蒸汽机不等于锅炉炉膛）。</p>
     *
     * <p><b>判据（2026-11-26 修）</b>：主判据是 {@code SteamEngineExhaustHeatZones.isExhausting}
     * ——一张由引擎自己的服务端 tick 每 tick 续期的 {@code (维度, BlockPos)} 纯内存表，
     * 只做 O(1) 查表，不碰区块、不碰 BE 实例。旧的
     * {@code SteamEngineExhaustAccess.isExhaustingAt}（{@code getChunkNow} + 服务端
     * {@code WeakHashMap<BlockEntity, Integer>}）降为兜底：它要求"当场解析出那个 BE"，
     * 而 Cold Sweat 2.4.2 在 Sable 子层级里是用 {@code DummyEntity} 在 plot 坐标上跑
     * {@code isValid}（{@code SublevelBlockTempModifier}），解析上下文与引擎 tick 不保证一致，
     * {@code ServerChunkCache#getChunkNow} 还有"非主线程→null"的硬守卫——这正是
     * "喷汽了但高温没出现"的来路。</p>
     *
     * <p><b>为什么必须 {@code registerFirst} + {@link EventPriority#LOWEST}（2026-11-27 字节码实证）</b>：
     * {@code ColdSweat-2.4.2.jar} 的 {@code BlockTempModifier#calculate}（字节码偏移 540–875）
     * 遍历 {@code BlockTempRegistry#getBlockTempsFor(state)} 的整列 {@code BlockTemp} 时，
     * <b>只要遇到第一个 {@code isValid() == true && getTemperature() != 0.0} 的条目</b>，
     * 累加完温度就 {@code goto 875}——即跳出 {@code for (BlockTemp)} 循环、直接进下一格坐标，
     * 同格列表里排在它后面的 {@code BlockTemp} 一个都不再执行。而整合包的
     * {@code create_cold_sweat-1.1.2.jar}（modId 字母序在 {@code dimblend_experience} 之前，
     * 用默认 {@code NORMAL}）已通过 {@code event.register(...)} 给 {@code create:steam_engine}
     * 注册了 {@code net.orion.create_cold_sweat.blockeffects.SteamEngine}：它不覆写
     * {@code isValid}（恒 {@code true}），且引擎贴着加热锅炉储罐时 {@code getTemperature}
     * 返回锅炉余温的微弱非零正值。于是本条的岩浆热源排在它后面，永远轮不到——表现就是
     * "过载断轴了、蒸汽在喷，但 32 秒内的高温没出现"。</p>
     *
     * <p><b>双保险</b>：</p>
     * <ol>
     *   <li>{@code EventPriority.LOWEST} 保证本监听晚于 {@code create_cold_sweat} 等所有
     *       {@code NORMAL} 监听执行；</li>
     *   <li>{@code registerFirst} 走 {@code BlockTempRegistry#register(BlockTemp, true)}
     *       （{@code ColdSweat-2.4.2.jar} 字节码实证）：它既把条目插到 {@code BLOCK_TEMPS} 的
     *       下标 0，也把 {@code MAPPED_BLOCKS.get(block)}（底层保序 {@code LinkedHashSet} 的
     *       {@code RegistryMultiMap}）清空重排后插到下标 0。两步合起来，本条才稳定压过
     *       {@code create_cold_sweat.SteamEngine}。</li>
     * </ol>
     *
     * <p><b>不破坏原有行为</b>：引擎正常运转/停转时 {@code exhaustingEngine(...)} 返回
     * {@code false}，{@code BlockTempModifier} 对下标 0 只是 {@code isValid} 失败后
     * {@code goto 540}（继续遍历），下标 1 的 {@code create_cold_sweat.SteamEngine} 照旧生效；
     * 只有过载断轴后的 32 秒排气窗口里，下标 0 抢先命中 {@link ColdSweatHeatSources#STEAM_ENGINE_EXHAUST}。</p>
     */
    private static final class Create {

        private static void register(BlockTempRegisterEvent event) {
            Block engine = block("create", "steam_engine");
            if (engine != null) {
                // registerFirst（不是 register）：插到列表下标 0，否则 create_cold_sweat 的
                // SteamEngine 会在 BlockTempModifier 里抢先命中并 break，8× 岩浆热源永远不生效。
                event.registerFirst(
                        ColdSweatHeatBlockTemps.steamEngineExhaust(Create::exhaustingEngine, engine));
            }
        }

        private static boolean exhaustingEngine(Level level, BlockPos pos, BlockState state) {
            return dimblend.experience.compat.create.SteamEngineExhaustHeatZones.isExhausting(level, pos)
                    || dimblend.experience.compat.create.SteamEngineExhaustAccess.isExhaustingAt(level, pos);
        }

        private Create() {
        }
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

    /** 蒸汽引擎过载排气（{@link ColdSweatHeatSources#STEAM_ENGINE_EXHAUST}，logarithmic，8× 岩浆）。 */
    static BlockTemp steamEngineExhaust(ColdSweatHeatSources.StateGate gate, Block... blocks) {
        return new Gated(ColdSweatHeatSources.STEAM_ENGINE_EXHAUST, gate, blocks);
    }

    private static final class Gated extends BlockTemp {

        private final double temperatureMc;
        private final ColdSweatHeatSources.StateGate gate;

        private Gated(ColdSweatHeatSources.HeatProfile profile, ColdSweatHeatSources.StateGate gate,
                Block... blocks) {
            // BlockTemp(minEffect, maxEffect, minTemperature, maxTemperature, range, fade, logarithmic, blocks...)
            super(profile.minEffectMc(), profile.maxEffectMc(),
                    profile.minTemperatureMc(), profile.maxTemperatureMc(),
                    profile.range(), profile.fade(), profile.logarithmic(), blocks);
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
