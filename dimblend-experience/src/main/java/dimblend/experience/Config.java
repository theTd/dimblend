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
            .comment("探索限制 A3/A4：|z| 每跨过 256 生命上限 ×0.75（下限 1 点），回到 |z|≤128 完全恢复")
            .define("depthCurse", true);

    public static final ModConfigSpec.BooleanValue CURSE_BOSSBAR = BUILDER
            .comment("探索限制 A5：旋转维度内在盔甲 HUD 位置常驻显示 z256 进度条（段内进度 + |z| 数字）")
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
            .comment("B 板块：CDG 柴油机转速行为（点火爬梯 16rpm→每4秒+2→额定；额定后 80%~100% 随机波动；运转中过载爆机掉落）")
            .define("dieselEngineBehavior", true);

    public static final ModConfigSpec.BooleanValue ELECTRIC_MOTOR_BEHAVIOR = BUILDER
            .comment("D 板块：CCA 电动马达（反转红石语义：信号在场=运转、无信号=停转；"
                    + "信号 1-15 映射 4rpm~面板额定；耗电改纯线性无待机下限）。"
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

    public static final ModConfigSpec.BooleanValue ISOLATED_WATER_DOWNGRADE = BUILDER
            .comment("G 板块 G3：旋转维度内新写入的纯水源（桶/管道/冰融化/流体成池等）若水平四邻源水<2 格则降级为流动 water7；≥2 格才保留源水")
            .define("isolatedWaterDowngrade", true);

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
            .comment("探索限制规则（A1-A6）生效的维度 id")
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
