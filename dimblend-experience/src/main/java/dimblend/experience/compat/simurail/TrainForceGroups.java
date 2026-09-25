package dimblend.experience.compat.simurail;

import dev.ryanhcode.sable.api.physics.force.ForceGroup;
import dev.ryanhcode.sable.api.physics.force.ForceGroups;
import dimblend.experience.DimBlend;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 本模组登记到 sable 力分组注册表的分组（simurail {@code SimurailForceGroups} 同型）。
 * 施力走 {@code QueuedForceGroup#applyAndRecordPointForce} 才会被 Simulated 力示意图
 * （{@code DiagramEntity} 读 {@code getRecordedPointForces}）记录并按本分组的名称/颜色绘制。
 *
 * <p>仅在 simurail 在场时注册（simurail 硬性依赖 sable；与 E8 mixin 的加载条件一致，
 * 保证 mixin 生效时分组必已注册）。</p>
 */
public final class TrainForceGroups {

    public static final DeferredRegister<ForceGroup> FORCE_GROUPS =
            DeferredRegister.create(ForceGroups.REGISTRY_KEY, DimBlend.MODID);

    /** E8 车架随机横向力。 */
    public static final DeferredHolder<ForceGroup, ForceGroup> LATERAL_FORCE =
            FORCE_GROUPS.register("lateral_force", () -> new ForceGroup(
                    Component.translatable("force_group." + DimBlend.MODID + ".lateral_force"),
                    null, 0xD94F9A, true));

    private TrainForceGroups() {
    }
}
