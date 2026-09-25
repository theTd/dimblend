package dimblend.experience.mixin;

import java.util.ArrayList;
import java.util.List;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import dimblend.experience.Config;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BeaconBlockEntity;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * A7 信标效果全图广播（用户口径：广播至信标所在维度的全体玩家，全部维度一致）：
 * 把 applyEffects 的“信标范围玩家”查询替换为所在维度的全体非旁观者玩家（死亡玩家
 * 与原版一致仍会收到广播）。效果种类/等级/时长等其余语义全部沿用原版。
 */
@Mixin(BeaconBlockEntity.class)
public abstract class BeaconBlockEntityMixin {

    @WrapOperation(
            method = "applyEffects",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/Level;getEntitiesOfClass(Ljava/lang/Class;Lnet/minecraft/world/phys/AABB;)Ljava/util/List;"))
    private static List<Player> dimblend$broadcastToDimension(Level level, Class<Player> type, AABB aabb,
            Operation<List<Player>> original) {
        if (Config.GLOBAL_BEACON.get() && level instanceof ServerLevel serverLevel) {
            List<Player> players = new ArrayList<>();
            for (Player player : serverLevel.players()) {
                if (!player.isSpectator()) {
                    players.add(player);
                }
            }
            return players;
        }
        return original.call(level, type, aabb);
    }
}
