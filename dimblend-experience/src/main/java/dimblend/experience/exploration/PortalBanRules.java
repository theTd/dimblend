package dimblend.experience.exploration;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * G4 传送门禁令的纯判定（供单测钉死）。
 *
 * <p>旋转维度内的「离境禁」拦的是<b>跨维度</b>旅行。末影珍珠落地、紫颂果等原版手段会把
 * 同维度的位置跳转也包装成一次 {@code EntityTravelToDimensionEvent}，若按维度无关地一律取消，
 * 珍珠在旋转维度内会失效且没有任何提示。因此目的地与出发维度相同即放行。</p>
 */
public final class PortalBanRules {

    /** @return true = 同维度旅行，不属于离境禁的管辖范围 */
    public static boolean isSameDimension(ResourceKey<Level> from, ResourceKey<Level> to) {
        return from != null && from.equals(to);
    }

    private PortalBanRules() {
    }
}
