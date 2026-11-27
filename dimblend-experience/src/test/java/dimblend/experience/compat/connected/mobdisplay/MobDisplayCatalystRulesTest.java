package dimblend.experience.compat.connected.mobdisplay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

/**
 * 生物展示催化剂纯规则层（{@link MobDisplayCatalystRules}）单元测试：
 * 抓捕盒三键解析 + 展示缩放 + 自转角度。
 */
class MobDisplayCatalystRulesTest {

    private static final float EPS = 1.0e-6f;

    private static CompoundTag capturedCowBox() {
        CompoundTag entityNbt = new CompoundTag();
        entityNbt.putString("id", "minecraft:cow");
        entityNbt.putFloat("Health", 10.0f);

        CompoundTag box = new CompoundTag();
        box.putBoolean(MobDisplayCatalystRules.TAG_HAS_CAPTURED, true);
        box.putString(MobDisplayCatalystRules.TAG_CAPTURED_TYPE, "minecraft:cow");
        box.put(MobDisplayCatalystRules.TAG_CAPTURED_NBT, entityNbt);
        return box;
    }

    // ---- 抓捕盒三键 ---------------------------------------------------------

    @Test
    void readsCapturedFlagTypeAndNbt() {
        CompoundTag box = capturedCowBox();

        assertTrue(MobDisplayCatalystRules.hasCapturedEntity(box));
        assertEquals("minecraft:cow", MobDisplayCatalystRules.capturedEntityTypeId(box));

        CompoundTag entity = MobDisplayCatalystRules.capturedEntityTag(box);
        assertNotNull(entity);
        assertEquals("minecraft:cow", entity.getString("id"));
        assertEquals(10.0f, entity.getFloat("Health"), EPS);
    }

    @Test
    void missingOrEmptyBoxDataYieldsNothingToRender() {
        // 整只盒子没有任何自定义数据（拿在手里的空盒子）
        assertFalse(MobDisplayCatalystRules.hasCapturedEntity(null));
        assertEquals("", MobDisplayCatalystRules.capturedEntityTypeId(null));
        assertNull(MobDisplayCatalystRules.capturedEntityTag(null));

        // 有自定义数据但没抓到东西：不该建实体
        CompoundTag empty = new CompoundTag();
        empty.putBoolean(MobDisplayCatalystRules.TAG_HAS_CAPTURED, false);
        assertFalse(MobDisplayCatalystRules.hasCapturedEntity(empty));
        assertNull(MobDisplayCatalystRules.capturedEntityTag(empty));

        // 只有类型没有 nbt（老盒子/被裁剪过）：类型照给，nbt 为 null（走 create + 不 load）
        CompoundTag typeOnly = new CompoundTag();
        typeOnly.putBoolean(MobDisplayCatalystRules.TAG_HAS_CAPTURED, true);
        typeOnly.putString(MobDisplayCatalystRules.TAG_CAPTURED_TYPE, "minecraft:pig");
        assertTrue(MobDisplayCatalystRules.hasCapturedEntity(typeOnly));
        assertEquals("minecraft:pig", MobDisplayCatalystRules.capturedEntityTypeId(typeOnly));
        assertNull(MobDisplayCatalystRules.capturedEntityTag(typeOnly));
    }

    @Test
    void capturedNbtIsACopySoTheBoxIsNeverMutatedByTheDisplayEntity() {
        CompoundTag box = capturedCowBox();
        CompoundTag entity = MobDisplayCatalystRules.capturedEntityTag(box);
        assertNotNull(entity);

        entity.putFloat("Health", 1.0f);
        entity.putBoolean("InLove", true);

        assertEquals(10.0f, box.getCompound(MobDisplayCatalystRules.TAG_CAPTURED_NBT).getFloat("Health"), EPS);
        assertFalse(box.getCompound(MobDisplayCatalystRules.TAG_CAPTURED_NBT).getBoolean("InLove"));
    }

    @Test
    void nonCompoundCapturedNbtIsIgnored() {
        CompoundTag box = new CompoundTag();
        box.putBoolean(MobDisplayCatalystRules.TAG_HAS_CAPTURED, true);
        box.putString(MobDisplayCatalystRules.TAG_CAPTURED_TYPE, "minecraft:cow");
        box.putString(MobDisplayCatalystRules.TAG_CAPTURED_NBT, "not a compound");
        assertNull(MobDisplayCatalystRules.capturedEntityTag(box));
    }

    // ---- 展示缩放 -----------------------------------------------------------

    @Test
    void oversizedMobIsDownscaledToFitTheBlock() {
        // 牛 1.4 高 -> 0.4 / 1.4
        assertEquals(0.4f / 1.4f, MobDisplayCatalystRules.displayScale(0.9f, 1.4f), EPS);
        // 长边取 max(宽, 高)：宽 3 的巨型生物按宽度算
        assertEquals(0.4f / 3.0f, MobDisplayCatalystRules.displayScale(3.0f, 1.0f), EPS);
        // 正好 0.4 格宽高：缩到 0.4（0.4 / 0.4 = 1.0，再被外层的 0.4 上限压住）
        assertEquals(MobDisplayCatalystRules.MAX_DIMENSION, MobDisplayCatalystRules.displayScale(0.4f, 0.4f), EPS);
    }

    @Test
    void smallMobIsNeverUpscaled() {
        // 小鸡 0.4 高：绝不放大成巨型，封顶 0.4
        assertEquals(MobDisplayCatalystRules.MAX_DIMENSION, MobDisplayCatalystRules.displayScale(0.4f, 0.4f), EPS);
        // 比上限还小的生物同理
        assertEquals(MobDisplayCatalystRules.MAX_DIMENSION, MobDisplayCatalystRules.displayScale(0.2f, 0.3f), EPS);
    }

    @Test
    void zeroSizedMobDoesNotExplodeTheScale() {
        // 0 宽 0 高（被清空包围盒的实体）：不能除零、不能 NaN/Infinity
        float scale = MobDisplayCatalystRules.displayScale(0.0f, 0.0f);
        assertFalse(Float.isNaN(scale));
        assertFalse(Float.isInfinite(scale));
        assertEquals(MobDisplayCatalystRules.MAX_DIMENSION, scale, EPS);
    }

    // ---- 自转 ---------------------------------------------------------------

    @Test
    void rotationWrapsEveryNineSeconds() {
        // 0.04°/ms = 40°/s -> 9 秒一圈（旧注释误写成"≈ 2.5 秒一圈"，这里把真实数值钉死）
        assertEquals(0.04D, MobDisplayCatalystRules.DEGREES_PER_MILLISECOND, 1.0e-12D);
        assertEquals(9000.0D, 360.0D / MobDisplayCatalystRules.DEGREES_PER_MILLISECOND, 1.0e-6D);

        assertEquals(0.0f, MobDisplayCatalystRules.rotationDegrees(0L), EPS);
        assertEquals(1.0f, MobDisplayCatalystRules.rotationDegrees(25L), EPS);
        assertEquals(90.0f, MobDisplayCatalystRules.rotationDegrees(2250L), EPS);
        // 一圈（360 度 = 9000 ms）后回绕到 0，永不越界
        assertEquals(0.0f, MobDisplayCatalystRules.rotationDegrees(9000L), EPS);
        assertEquals(1.0f, MobDisplayCatalystRules.rotationDegrees(9000L + 25L), EPS);
        for (long millis : new long[] {1L, 123L, 4_567_890L, Long.MAX_VALUE / 64L}) {
            float degrees = MobDisplayCatalystRules.rotationDegrees(millis);
            assertTrue(degrees >= 0.0f && degrees < 360.0f,
                    "rotation must stay in [0,360) for " + millis + " but was " + degrees);
        }
    }
}
