package dimblend.experience.mixin.compat.starlight;

import cn.leolezury.eternalstarlight.common.block.entity.CrystalbornCatalystBlockEntity;
import net.minecraft.util.RandomSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Read/write access to the catalyst's private fuel counters for the redstone-block mode. */
@Mixin(value = CrystalbornCatalystBlockEntity.class, remap = false)
public interface CrystalbornCatalystBlockEntityAccessor {

    @Accessor("energyLeft")
    int dimblend$getEnergyLeft();

    @Accessor("energyLeft")
    void dimblend$setEnergyLeft(int energyLeft);

    @Accessor("charge")
    int dimblend$getCharge();

    @Accessor("charge")
    void dimblend$setCharge(int charge);

    @Accessor("random")
    RandomSource dimblend$getRandom();
}
