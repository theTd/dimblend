package dimblend.experience.chassis;

import dimblend.experience.reward.RewardWagonContent;
import dimblend.experience.reward.RewardWagonLayout;
import java.util.Locale;
import java.util.Optional;
import net.neoforged.fml.ModList;

/**
 * 驾驶台右键可选的全部预生成列车/车厢（2026-11-15 创造调试口径）。
 *
 * <p><b>龙巢除外。</b>龙巢是末影龙追逐的关卡结构（强制加载、奖励分区），不是玩家能直接放出来的
 * 通用车厢，因此这里只有 {@link Family#DRAGON} 的刹车与水晶两种；「龙巢」永远不能进这个表。
 *
 * <p><b>id 就是网络包里的预设名。</b>两端都用 {@link #id()}（枚举名的小写）而不是序号，新增一种
 * 预设不会移动别人的编号；{@link #byId} 是服务端唯一的解析入口，未知 id 一律拒绝。
 *
 * <p><b>可选 mod 的预设按存在性隐藏。</b>末影龙追逐不在时，{@code BRAKE}/{@code CRYSTAL} 不可用，
 * 客户端不显示、服务端即使收到也拒绝；判定放在 {@link #available(boolean)} 这个纯函数里，单测不用
 * 启动游戏即可覆盖。
 */
public enum ChassisPreset {
    // 标准底盘（dimblend 自己的 45 格图纸）。
    POWER(Family.STANDARD, ChassisBlueprint.POWER),
    CARRIAGE(Family.STANDARD, ChassisBlueprint.CARRIAGE),

    // 奖励车厢：RewardWagonContent 的全部 11 种内容，NONE 不是车厢，不在这里。
    SHALLOW_COPPER(Family.REWARD, RewardWagonContent.SHALLOW_COPPER),
    SHALLOW_ZINC(Family.REWARD, RewardWagonContent.SHALLOW_ZINC),
    SHALLOW_IRON(Family.REWARD, RewardWagonContent.SHALLOW_IRON),
    DEEP_IRON(Family.REWARD, RewardWagonContent.DEEP_IRON),
    DEEP_ZINC(Family.REWARD, RewardWagonContent.DEEP_ZINC),
    DEEP_COPPER(Family.REWARD, RewardWagonContent.DEEP_COPPER),
    DEEPSILVER(Family.REWARD, RewardWagonContent.DEEPSILVER),
    SALTPETER(Family.REWARD, RewardWagonContent.SALTPETER),
    TWILIGHT_CHEST(Family.REWARD, RewardWagonContent.TWILIGHT_CHEST),
    SOUND_TRAP(Family.REWARD, RewardWagonContent.SOUND_TRAP),
    MOTOR_WAGON(Family.REWARD, RewardWagonContent.MOTOR_WAGON),

    // 末影龙追逐的非巢车厢。NEST 故意缺席。
    BRAKE(Family.DRAGON),
    CRYSTAL(Family.DRAGON);

    /** 预设来源：决定谁来生成、以及生存模式是否可见。 */
    public enum Family {
        /** 45 格标准底盘，逐排施工。 */
        STANDARD,
        /** 奖励车厢，由 {@code RewardWagonBuilder} 一次装配。 */
        REWARD,
        /** 末影龙追逐的刹车/水晶车厢，由该 mod 自己的装配器生成。 */
        DRAGON
    }

    /** 末影龙追逐的 mod id，与 {@code TuningOption} 的可用位同源。 */
    public static final String DRAGON_CHASE_MOD_ID = "dimblend_dragonchase";

    private final Family family;
    private final ChassisBlueprint blueprint;
    private final RewardWagonContent content;

    ChassisPreset(Family family, ChassisBlueprint blueprint) {
        this.family = family;
        this.blueprint = blueprint;
        this.content = null;
    }

    ChassisPreset(Family family, RewardWagonContent content) {
        this.family = family;
        this.blueprint = null;
        this.content = content;
    }

    ChassisPreset(Family family) {
        this.family = family;
        this.blueprint = null;
        this.content = null;
    }

    public Family family() {
        return family;
    }

    /** 标准底盘的图纸；其它来源为 {@code null}。 */
    public ChassisBlueprint blueprint() {
        return blueprint;
    }

    /** 奖励车厢的内容；其它来源为 {@code null}。 */
    public RewardWagonContent content() {
        return content;
    }

    /** 网络包里的稳定 id，也是语言键的后缀。 */
    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public String translationKey() {
        return "chassis.dimblend_experience." + id();
    }

    public String descriptionKey() {
        return translationKey() + ".description";
    }

    /**
     * 该预设占地的半长（X）、半宽（Z）与中心以上的高度，用于生成前的场地检查。
     *
     * <p>与生成器使用的真实尺寸一致：标准底盘 45×5、奖励车厢 9×3 且顶面在中心 +2、末影龙追逐
     * 车厢 13×7（§3.5），灯与水晶在中心 +2。检查范围只包住车厢本身，而不是整条 49 格底盘检查带，
     * 这样调试时挨着别的车厢也能放。
     */
    public int halfLength() {
        return switch (family) {
            case STANDARD -> ChassisBlueprint.HALF_LENGTH;
            case REWARD -> RewardWagonLayout.HALF_LENGTH;
            case DRAGON -> 6;
        };
    }

    public int halfWidth() {
        return switch (family) {
            case STANDARD -> 2;
            case REWARD -> RewardWagonLayout.HALF_WIDTH;
            case DRAGON -> 3;
        };
    }

    public int height() {
        return switch (family) {
            case STANDARD -> 1;
            case REWARD -> RewardWagonLayout.ORE_TOP_DY - RewardWagonLayout.STRUCTURE_DY;
            case DRAGON -> 3;
        };
    }

    /** 该来源的 mod 是否在场；末影龙追逐缺席时只隐藏它自己的两种车厢。 */
    public boolean available(boolean dragonChaseLoaded) {
        return family != Family.DRAGON || dragonChaseLoaded;
    }

    public boolean available() {
        return available(ModList.get().isLoaded(DRAGON_CHASE_MOD_ID));
    }

    /**
     * 该预设对这名玩家是否可用。
     *
     * <p>创造模式永远全开；生存模式只有在
     * {@link ChassisInteraction#SURVIVAL_STANDARD_CHASSIS_ENABLED} 打开时才看得到两种标准底盘，
     * 其余（奖励、末影龙追逐）一律只供创造调试。
     */
    public boolean permitted(boolean creative) {
        return creative || (family == Family.STANDARD && ChassisInteraction.SURVIVAL_STANDARD_CHASSIS_ENABLED);
    }

    /** 客户端列表与客户端点击都用它：在场且这名玩家被允许。 */
    public boolean visibleTo(boolean creative) {
        return available() && permitted(creative);
    }

    public static Optional<ChassisPreset> byId(String id) {
        for (ChassisPreset preset : values()) {
            if (preset.id().equals(id)) {
                return Optional.of(preset);
            }
        }
        return Optional.empty();
    }
}
