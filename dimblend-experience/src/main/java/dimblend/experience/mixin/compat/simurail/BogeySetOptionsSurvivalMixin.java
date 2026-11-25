package dimblend.experience.mixin.compat.simurail;

import com.crystaelix.simurail.content.bogey.PhysicsBogeyBlockEntity;
import com.crystaelix.simurail.content.bogey.PhysicsBogeyOptions;
import com.crystaelix.simurail.content.bogey.PhysicsBogeySetOptionsPacket;
import dimblend.experience.compat.simurail.SimurailSurvivalBogeyGlue;
import foundry.veil.api.network.handler.ServerPacketContext;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 服务端闸门：生存模式只接受应力与白名单样式（2026-11-25 LOCKED #3）。
 *
 * <p>客户端界面隐藏只是体验层；真正的约束在这里——把
 * {@code PhysicsBogeySetOptionsPacket#handle} 的 {@code setOptions} 换成合并后的值：
 * 应力取玩家发来的（夹在 0..32），样式只有落到四种白名单样式才接受，
 * 其余开关（物理/角度/位移/垂直/控制/连接件）一律沿用服务端现值。
 * 创造模式整包放行。</p>
 */
@Mixin(value = PhysicsBogeySetOptionsPacket.class, remap = false)
public abstract class BogeySetOptionsSurvivalMixin {

    @Redirect(
            method = "handle",
            at = @At(value = "INVOKE",
                    target = "Lcom/crystaelix/simurail/content/bogey/PhysicsBogeyBlockEntity;setOptions(Lcom/crystaelix/simurail/content/bogey/PhysicsBogeyOptions;)V"),
            require = 1,
            allow = 1
    )
    private void dimblend$enforceSurvivalMerge(PhysicsBogeyBlockEntity bogey, PhysicsBogeyOptions incoming,
            ServerPacketContext context) {
        ServerPlayer player = context.player();
        if (player == null || player.isCreative()) {
            bogey.setOptions(incoming);
            return;
        }
        bogey.setOptions(SimurailSurvivalBogeyGlue.mergeForSurvival(bogey.getOptions(), incoming));
    }
}
