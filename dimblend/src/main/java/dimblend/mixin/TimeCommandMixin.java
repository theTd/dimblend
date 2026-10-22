package dimblend.mixin;

import dimblend.weather.RotatingLevelData;
import net.minecraft.server.commands.TimeCommand;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * /time add X writes every level relatively: setDayTime(getDayTime() + X) across getAllLevels().
 * rotating's setDayTime writes through to the overworld primary clock (RotatingLevelData), so the
 * loop would apply X twice to that one shared clock — once via the overworld, once via rotating.
 * Skip rotating's echo write. /time set is an absolute, idempotent write and needs no such guard.
 */
@Mixin(TimeCommand.class)
public abstract class TimeCommandMixin {
    @Redirect(method = "addTime", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerLevel;setDayTime(J)V"), require = 1, allow = 1)
    private static void dimblend$skipSharedClockEcho(ServerLevel level, long time) {
        if (!(level.getLevelData() instanceof RotatingLevelData)) {
            level.setDayTime(time);
        }
    }
}
