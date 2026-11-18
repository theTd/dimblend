package dimblend.experience.jump;

import dimblend.experience.DimBlend;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

/**
 * 服务端「本次滞空是否已放过二段跳」标记。不序列化：随玩家实体生命周期存在，
 * 退出重进/切维度自然清空，无需 UUID 表或离线清理。
 */
public final class MidAirJumpAttachments {

    public static final DeferredRegister<AttachmentType<?>> ATTACHMENTS =
            DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, DimBlend.MODID);

    public static final DeferredHolder<AttachmentType<?>, AttachmentType<Boolean>> USED_THIS_AIRBORNE =
            ATTACHMENTS.register("mid_air_jump_used", () -> AttachmentType
                    .builder(() -> Boolean.FALSE)
                    .build());

    private MidAirJumpAttachments() {
    }
}
