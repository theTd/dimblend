package dimblend.experience;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Server-side feature switches. All exploration/compat rules are enforced on the
 * logical server, so a SERVER config keeps client installs from desyncing.
 */
public final class Config {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.BooleanValue DEATH_RULES = BUILDER
            .comment("探索限制 A1/A2：旋转维度内死亡保留物品、清空经验；床遗失时改在走廊旁重生")
            .define("deathRules", true);

    public static final ModConfigSpec.BooleanValue DEPTH_CURSE = BUILDER
            .comment("探索限制 A3/A4：|z| 每跨过 256 生命上限 ×0.75（下限 1 点），回到 |z|≤128 完全恢复；关闭只停扣上限，层级计数照常（进度条用）")
            .define("depthCurse", true);

    public static final ModConfigSpec.BooleanValue CURSE_BOSSBAR = BUILDER
            .comment("探索限制 A5：旋转维度内在盔甲 HUD 位置常驻显示 z256 进度条（(|z| − 256×诅咒层级)/256，负数 0% + |z| 数字）")
            .define("curseBossbar", true);

    public static final ModConfigSpec.BooleanValue SAFE_ZONE = BUILDER
            .comment("探索限制 A6：|z|≤64 安全区内拦截自然类生成（放行刷怪笼/刷怪蛋/繁殖等玩家侧与机器间接生成）")
            .define("safeZone", true);

    public static final ModConfigSpec.BooleanValue GLOBAL_BEACON = BUILDER
            .comment("信标全图广播（A7，全局功能不受维度约束）：激活的信标效果广播至其所在维度的全体玩家")
            .define("globalBeacon", true);

    public static final ModConfigSpec.BooleanValue NICKNAME = BUILDER
            .comment("物品昵称（A8，全局功能）：按键打开对话框（默认不绑定），也可 /dbx nickname。"
                    + "id 绑定、纯显示层、全服生效；"
                    + "运行时关闭后已下发到客户端的昵称表保留至重连（接受瞬态）")
            .define("nickname", true);

    public static final ModConfigSpec.IntValue NICKNAME_PERMISSION = BUILDER
            .comment("使用物品昵称（按键对话框与 /dbx nickname）所需的权限等级（0=所有人，2=管理员）")
            .defineInRange("nicknamePermission", 0, 0, 4);

    public static final ModConfigSpec.BooleanValue DIESEL_ENGINE_BEHAVIOR = BUILDER
            .comment("B 板块：CDG 柴油机转速行为（点火爬梯 16rpm→每4秒+2→额定；额定后 80%~100% 随机波动，爬梯/波动期间应力容量恒按额定；运转中过载持续2秒确认后爆机掉落）")
            .define("dieselEngineBehavior", true);

    public static final ModConfigSpec.BooleanValue DIESEL_OVERLOAD_PROBE = BUILDER
            .comment("B 板块诊断探针：柴油机疑似过载（缓存 overStressed 为真）时在日志输出 [CDG-PROBE] 行——"
                    + "疑似开始/结束、网络账本快照、成员逐项明细、网络最近账本事件回放；点引信时 WARN 完整转储。"
                    + "只读不改行为，定位完毕后可关")
            .define("dieselOverloadProbe", true);

    public static final ModConfigSpec.BooleanValue STEAM_ENGINE_OVERLOAD = BUILDER
            .comment("H 板块：Create 蒸汽引擎过载两阶段（过载持续 16 秒内每秒警告音 + 云粒子，解除即停；"
                    + "满 16 秒断开传动杆掉落 + 排气音 1 次 + 8 秒云粒子，过载解除不中断）")
            .define("steamEngineOverload", true);

    public static final ModConfigSpec.BooleanValue ELECTRIC_MOTOR_BEHAVIOR = BUILDER
            .comment("D 板块：CCA 电动马达（反转红石语义：信号在场=运转、无信号=停转；"
                    + "信号 1-15 映射 4rpm~面板额定；耗电改纯线性无待机下限；"
                    + "D7 过载锁存：kinetic 过载且|面板|>64且运转中锁信号冻输出、耗电×2、播过载音/粒子，过载恢复或FE耗尽重置）。"
                    + "开启将改变 CCA 原版'红石=停转'语义，既有红石装置请注意")
            .define("electricMotorBehavior", true);

    public static final ModConfigSpec.BooleanValue ALTERNATOR_IDLE_DRAIN = BUILDER
            .comment("D 板块 D5：CCA 交流发电机无有效转速输入（本 tick 不产电：停转 0rpm /"
                    + " 过载·冻结网络读数归零 / 最低转速门未满足）时内部储存 FE 自行流失"
                    + "（每 tick 250、约每秒 5000，扣到 0 为止；有输入产电时不流失）")
            .define("alternatorIdleDrain", true);

    public static final ModConfigSpec.BooleanValue SIMURAIL_PROTECT = BUILDER
            .comment("E 板块：Simurail 自带方块防爆防拆，仅创造模式可编辑。"
                    + "例外：create:non_breakable tag 补强为静态数据包资源，不受本开关控制（Create 机械侧防护始终生效）")
            .define("simurailProtect", true);

    public static final ModConfigSpec.BooleanValue COUPLER_REDSTONE = BUILDER
            .comment("E 板块 E5：取消自动车钩的红石信号解锁（true=红石信号不再断开车钩；贯通框不受影响）")
            .define("couplerRedstone", true);

    public static final ModConfigSpec.BooleanValue ASSEMBLER_GUARD = BUILDER
            .comment("F 板块 F1：simulated 物理组装器禁止摆放（生存模式禁、创造模式放行；"
                    + "放行放置后本 tick 末破坏自身返还：真人回背包、机器放置掉落；需要 simulated 在场）")
            .define("assemblerGuard", true);

    public static final ModConfigSpec.BooleanValue PORTABLE_ENGINE_EXCLUSIVITY = BUILDER
            .comment("F 板块 F2：simulated 便携引擎同 Create 动力网络互斥（16 色全算同类；按连通判、与转速无关，"
                    + "零转速静置网络同样生效，通电离合器隔开的两侧不算同网）；"
                    + "实体放置的新引擎若已连着其他引擎则本 tick 末自毁；其余任何接通（传动杆/齿轮/皮带接起来、离合器切换、"
                    + "蓝图炮/装置解体、区块加载、起转）本 tick 末随机留一台、其余全毁；"
                    + "一律按 loot 掉落、无豁免；需要 simulated+create 在场）")
            .define("portableEngineExclusivity", true);

    public static final ModConfigSpec.BooleanValue TRAIN_SOUNDS = BUILDER
            .comment("E 板块 E2-E4：转向架行驶轨道节奏循环（音量/音调随速度）+ 刹车/松闸气阀触发音。"
                    + "需要 Simurail 在场；客户端经 ConfigSync 同步后生效")
            .define("trainSounds", true);

    public static final ModConfigSpec.BooleanValue WIDE_GAUGE_PARTICLES = BUILDER
            .comment("E 板块 E6：幻纱宽轨上的车架在其 y-1 3x3 范围生成下落的末地烛粒子（纯客户端视觉）。需要 Simurail 在场")
            .define("wideGaugeParticles", true);

    public static final ModConfigSpec.BooleanValue OFF_STRUCTURE_TELEPORT = BUILDER
            .comment("E 板块 E7：旋转维度内任意车架速度大于 4 m/s 时，每 10 秒检测玩家半径 32 格内是否有 sable 结构；"
                    + "都无则传送回重生位置（有效床，否则走廊旁）。创造/旁观豁免；需要 Simurail+Sable 在场")
            .define("offStructureTeleport", true);

    public static final ModConfigSpec.BooleanValue TRAIN_LATERAL_FORCE = BUILDER
            .comment("E 板块 E8：车架速度大于 4 m/s 时每固定 2 秒以 速度/20 的概率对该车架施加 1200 pN 横向力"
                    + "（垂直车架朝向、左右随机、持续 10 tick，作用于车架位置）。全维度；需要 Simurail 在场")
            .define("trainLateralForce", true);

    public static final ModConfigSpec.BooleanValue VILLAGER_MASTER = BUILDER
            .comment("G 板块 G1：旋转维度内村民生成/转职瞬间定大师并补全全部交易，每条只能成交一次、不补货，掉工作站点不掉职业；"
                    + "新生无业者随机指派职业（不含无业/傻子），存量不追溯，傻子/婴儿/流浪商人跳过")
            .define("villagerMaster", true);

    public static final ModConfigSpec.BooleanValue STRUCTURE_BED = BUILDER
            .comment("G 板块 G2：旋转维度内只有 sable 结构上的床可交互（睡/设重生点）；破坏不管，创造模式豁免")
            .define("structureBed", true);

    public static final ModConfigSpec.BooleanValue PORTAL_BAN = BUILDER
            .comment("G 板块 G4：旋转维度内禁一切传送门生成与跨维度旅行；折跃门方块全局禁传送（留块）。回家通道不管")
            .define("portalBan", true);

    public static final ModConfigSpec.BooleanValue LIMITED_WATER = BUILDER
            .comment("G 板块 G3 有限水：旋转维度内每次写入源水（water8），先看以该格为球心 8 格内最近的非旁观玩家——"
                    + "该玩家为创造模式则不检测、原样写入；其余情况（生存/冒险，或 8 格内无人）检测水平四邻："
                    + "源水或冰/浮冰/蓝冰 ≥2 格保留源水，0-1 格改写为流动 water7。流动水、含水方块、霜冰不计；"
                    + "只拦新写入，已存在的水与世界生成不动；Sable 载具上的玩家与方块按真实世界坐标计距")
            .define("limitedWater", true);

    public static final ModConfigSpec.BooleanValue ICE_PLACEMENT_BAN = BUILDER
            .comment("G 板块 G3 配套：旋转维度内生存模式玩家与 Create 机械手不能放置冰/浮冰/蓝冰；"
                    + "其它模式玩家、其它机器/假玩家不拦")
            .define("icePlacementBan", true);

    public static final ModConfigSpec.BooleanValue ENDER_STORAGE_STRUCTURE_ONLY = BUILDER
            .comment("G 板块 G5：旋转维度内末影箱/末影罐（enderstorage:ender_chest / ender_tank）只能放在 sable 结构上；"
                    + "放在结构外则放行放置后本 tick 末破坏返还（真人回背包、机器放置掉落，频率保留）；"
                    + "创造模式豁免；需要 enderstorage 在场，sable 缺席时放行")
            .define("enderStorageStructureOnly", true);

    public static final ModConfigSpec.BooleanValue COPYCAT_OBSIDIAN_HARDNESS = BUILDER
            .comment("伪装硬度（全局功能，不限维度）：Create / Copycats+ / Create Connected"
                    + " 的全体伪装方块统一黑曜石硬度（挖掘/爆抗/末影龙凋灵免疫，含钻石镐采集校验）。"
                    + "与 dimblend-blocks 的 C0 共存（两者都装时以本 BlockState 拦截为准，行为一致）")
            .define("copycatObsidianHardness", true);

    public static final ModConfigSpec.BooleanValue ITEM_DRAIN_IRRIGATION = BUILDER
            .comment("分液池保湿（全局功能，不限维度）：水箱只要有水，就给周围同层土壤保湿；"
                    + "经 NeoForge FarmlandWaterManager 票据维持 MOISTURE（无催熟，催熟见 itemDrainGrowth*）；"
                    + "保湿不消耗水量、无最低水量门槛；建票时把范围内耕地主动拉满一次，"
                    + "其余时间走原版耕地 randomTick 节奏；空水箱/非水时摘票，回水后自动重建")
            .define("itemDrainIrrigation", true);

    // 键名由 itemDrainIrrigationRadius（旧默认 5=11x11）改为 itemDrainCoverageRadius：
    // NeoForge 对已存在的键保留旧值，不改名则老配置永远停在 11x11；改名后旧键被自动清除、新键取默认 3
    public static final ModConfigSpec.IntValue ITEM_DRAIN_COVERAGE_RADIUS = BUILDER
            .comment("分液池保湿/催熟半径（格）：默认 3 即 7x7；改动后下次重建票据时生效")
            .defineInRange("itemDrainCoverageRadius", 3, 1, 8);

    public static final ModConfigSpec.BooleanValue ITEM_DRAIN_GROWTH = BUILDER
            .comment("分液池催熟（全局功能，不限维度）：存水不低于阈值时，每周期随机挑范围内一株植物"
                    + "走原版骨粉逻辑催熟（一切骨粉目标都算，含草方块/苔藓蔓延型）；"
                    + "骨粉生效才扣水，范围内无目标或骨粉判定未生效时不扣")
            .define("itemDrainGrowth", true);

    // 键名由 itemDrainGrowthIntervalTicks（旧默认 1200=60 秒）改为按秒的 itemDrainGrowthIntervalSeconds，
    // 理由同 itemDrainCoverageRadius：老配置的旧值不会被新默认覆盖
    public static final ModConfigSpec.IntValue ITEM_DRAIN_GROWTH_INTERVAL_SECONDS = BUILDER
            .comment("分液池催熟周期（秒）；默认 30")
            .defineInRange("itemDrainGrowthIntervalSeconds", 30, 1, 600);

    public static final ModConfigSpec.IntValue ITEM_DRAIN_GROWTH_COST_MB = BUILDER
            .comment("分液池每次催熟生效扣除的水量（mb，1 桶=1000）；0=免费催熟")
            .defineInRange("itemDrainGrowthCostMb", 10, 0, 1500);

    public static final ModConfigSpec.IntValue ITEM_DRAIN_GROWTH_MIN_MB = BUILDER
            .comment("分液池存水低于此水量（mb）时停止催熟（保湿不受影响）；回水后自动恢复")
            .defineInRange("itemDrainGrowthMinMb", 200, 0, 1500);

    public static final ModConfigSpec.BooleanValue ITEM_DRAIN_PIPE_REFILL = BUILDER
            .comment("分液池管道补水（全局功能，不限维度）：开放分液池注入口，允许流体管道/泵向池内注水"
                    + "（原版机械动力禁止管道灌入分液池、且正上方无流体接口，本开关两者都放开；侧面/底面/顶面均可接管道）；"
                    + "关闭后恢复原版行为")
            .define("itemDrainPipeRefill", true);

    public static final ModConfigSpec.ConfigValue<String> ROTATING_DIMENSION_ID = BUILDER
            .comment("探索限制规则（A1-A6）生效的维度 id，必须带命名空间（缺命名空间将回退默认 dimblend:rotating 并记错误日志）")
            .define("rotatingDimensionId", "dimblend:rotating");

    static final ModConfigSpec SPEC = BUILDER.build();

    /**
     * 配置是否已加载。SERVER 配置在同步/加载完成前 {@code Value#get()} 会抛
     * {@link IllegalStateException}（"Cannot get config value before config is loaded"），
     * capability provider 等任意端任意时机都可能被查询的路径必须先判本方法。
     */
    public static boolean isLoaded() {
        return SPEC.isLoaded();
    }

    private Config() {
    }
}
