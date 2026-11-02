package dimblend.experience.mixin.compat.simurail;

import com.crystaelix.simurail.api.coupler.CouplerType;
import com.crystaelix.simurail.content.automatic_coupler.AutomaticCouplerBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 写入车钩类型字段，供「类型强制为詹式」在 {@code read} 末尾落刀。
 *
 * <p>目标字段 {@code protected CouplerType type} 不是 final，普通 setter 访问器即可，
 * 不需要 {@code @Mutable}。</p>
 */
@Mixin(AutomaticCouplerBlockEntity.class)
public interface AutomaticCouplerTypeAccessor {

    @Accessor(value = "type", remap = false)
    void dimblend$setType(CouplerType type);
}