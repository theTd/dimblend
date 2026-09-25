package dimblend.experience.compat.cdg;

import dimblend.experience.DimBlend;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

/**
 * B 板块发动机运行态附件（带序列化）：过载闩锁与油量参照跨区块卸载/
 * 存档重启保持（维持"重新加油才重启"语义）；波动状态一并序列化（无代价）。
 */
public final class CdgAttachments {

    public static final DeferredRegister<AttachmentType<?>> ATTACHMENTS =
            DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, DimBlend.MODID);

    public static final DeferredHolder<AttachmentType<?>, AttachmentType<CdgEngineState>> ENGINE_STATE =
            ATTACHMENTS.register("cdg_engine_state", () -> AttachmentType
                    .<CdgEngineState>builder(CdgEngineState::new)
                    .serialize(CdgEngineState.CODEC)
                    .build());

    private CdgAttachments() {
    }
}