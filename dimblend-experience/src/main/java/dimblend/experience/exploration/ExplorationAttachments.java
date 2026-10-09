package dimblend.experience.exploration;

import dimblend.experience.DimBlend;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

/**
 * 探索限制板块的实体数据附件。附件刻意**不**使用 copyOnDeath：需要跨死亡持有的
 * 状态（如 A3/A4 僵持区层级）由各自规则在重生后按新位置重算。
 */
public final class ExplorationAttachments {

    public static final DeferredRegister<AttachmentType<?>> ATTACHMENTS =
            DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, DimBlend.MODID);

    /** A3/A4 远行诅咒当前层级。持久化以便死亡中登出重登仍持有僵持区层级。 */
    public static final DeferredHolder<AttachmentType<?>, AttachmentType<Integer>> FAR_CURSE_TIER =
            ATTACHMENTS.register("far_curse_tier", () -> AttachmentType
                    .builder(() -> 0)
                    .serialize(com.mojang.serialization.Codec.INT)
                    .build());

    /**
     * G1 村民大师标记：已收编村民的职业 id；空串表示未收编（自然村民/傻子）。
     * 持久化，用于掉职业后恢复与补货拦截判据。
     */
    public static final DeferredHolder<AttachmentType<?>, AttachmentType<String>> VILLAGER_PROFESSION =
            ATTACHMENTS.register("villager_profession", () -> AttachmentType
                    .builder(() -> "")
                    .serialize(com.mojang.serialization.Codec.STRING)
                    .build());

    /**
     * G1 新鲜戳：转职瞬间由 {@code VillagerMasterMixin} 打上，自带职业的新生由
     * {@code VillagerMasterRules#onFinalizeSpawn} 打上，无业/傻子/婴儿新生不打戳，
     * 扫描收编时消费。只认戳不认交易/经验——`getOffers()` 自带物化副作用，不能拿它判新鲜。
     * 持久化，重启不丢。
     */
    public static final DeferredHolder<AttachmentType<?>, AttachmentType<Boolean>> VILLAGER_FRESH =
            ATTACHMENTS.register("villager_fresh", () -> AttachmentType
                    .builder(() -> false)
                    .serialize(com.mojang.serialization.Codec.BOOL)
                    .build());

    private ExplorationAttachments() {
    }
}
