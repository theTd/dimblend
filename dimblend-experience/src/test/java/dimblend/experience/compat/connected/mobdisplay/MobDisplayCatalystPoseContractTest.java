package dimblend.experience.compat.connected.mobdisplay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.WalkAnimationState;
import org.junit.jupiter.api.Test;

/**
 * 生物展示催化剂"放入其中的生物高速旋转 / 剧烈左右摆动"的<b>姿态归一化契约</b>回归测试
 * （2026-11-27 根因，实证自 {@code E:\misc\mcsrc-1.21.1\net\minecraft\world\entity\Entity.java:1749-1751}）。
 *
 * <p>根因链：{@code Entity#load} 只 {@code setOldPosAndRot()}（只同步 {@code yRotO}/{@code xRotO}），
 * 再把 {@code yHeadRot}/{@code yBodyRot} 设成抓捕朝向，<b>不会</b>同步
 * {@code LivingEntity.yBodyRotO}/{@code yHeadRotO}。展示实体从不跑 {@code Entity#tick()}，
 * 于是 {@code yBodyRotO == 0}、{@code yBodyRot == 175} 永远不变，而渲染器把逐 tick 锯齿的
 * {@code partialTick} 传给 {@code Mth.rotLerp(partialTick, yBodyRotO, yBodyRot)}，
 * 每 50ms 就在 {@code 0°} 与 {@code 175°} 之间扫一遍。</p>
 *
 * <p>分三层校验：</p>
 * <ol>
 * <li><b>源码不变量</b>：{@code lockDisplayPose} 必须归零清单里的每个字段，且
 *     {@code clientTick} 两条路径与渲染器都真的调它（注释先剥掉，避免"只在注释里写了"就算过）；</li>
 * <li><b>数学行为</b>：用真 {@code Mth.rotLerp} 复现锯齿，并证明 {@code 0/0} 之后恒为 {@code 0}；</li>
 * <li><b>动画配方</b>：{@code WalkAnimationState} 只 {@code setSpeed(0)} 不够，必须再
 *     {@code update(0, 1)} 把 {@code speedOld} 一起拉平——这条用真实例跑，不靠读源码。</li>
 * </ol>
 */
class MobDisplayCatalystPoseContractTest {

    private static final float EPS = 1.0e-6f;

    private static final String BLOCK_ENTITY = "MobDisplayCatalystBlockEntity.java";
    private static final String RENDERER = "MobDisplayCatalystRenderer.java";

    // ---- 一、源码不变量：姿态归一化清单 -----------------------------------------

    @Test
    void lockDisplayPoseZeroesEveryOrientationAndInterpolationField() throws IOException {
        String body = methodBody(stripComments(readSource(BLOCK_ENTITY)),
                "public static void lockDisplayPose(Entity entity)");

        List<String> required = List.of(
                "entity.setDeltaMovement(Vec3.ZERO)",
                "entity.setYRot(0.0F)",
                "entity.yRotO = 0.0F",
                "entity.setXRot(0.0F)",
                "entity.xRotO = 0.0F",
                "entity.setYHeadRot(0.0F)",
                "entity.setYBodyRot(0.0F)",
                "living.yBodyRot = 0.0F",
                "living.yBodyRotO = 0.0F",
                "living.yHeadRot = 0.0F",
                "living.yHeadRotO = 0.0F",
                "living.oAttackAnim = 0.0F",
                "living.attackAnim = 0.0F",
                "living.hurtTime = 0",
                "living.deathTime = 0",
                "living.walkAnimation.setSpeed(0.0F)",
                "living.walkAnimation.update(0.0F, 1.0F)",
                "entity.setOldPosAndRot()");
        for (String snippet : required) {
            assertTrue(body.contains(snippet),
                    "lockDisplayPose 必须包含 `" + snippet + "`，否则该字段会保留插值历史造成 20Hz 抖动");
        }

        // setOldPosAndRot 会把当前 yRot/xRot 抄进 yRotO/xRotO，必须放在所有归零之后。
        assertTrue(lastIndexOf(body, "entity.setOldPosAndRot()") > lastIndexOf(body, "entity.setYRot(0.0F)"),
                "setOldPosAndRot() 必须在朝向归零之后调用");
        assertTrue(lastIndexOf(body, "entity.setOldPosAndRot()") > lastIndexOf(body, "living.walkAnimation.update("),
                "setOldPosAndRot() 必须是 lockDisplayPose 的最后一步");
    }

    @Test
    void clientTickRelocksOnBothTheRebuildAndTheSteadyStatePath() throws IOException {
        String code = stripComments(readSource(BLOCK_ENTITY));
        String body = methodBody(code, "public void clientTick()");

        // 稳态路径（只自增 tickCount 的那条）与重建路径都必须锁姿态
        assertTrue(body.contains("displayEntity.tickCount++;"),
                "稳态路径必须只自增 tickCount（不跑 Entity#tick）");
        assertTrue(lastIndexOf(body, "lockDisplayPose(displayEntity)")
                        > lastIndexOf(body, "displayEntity.tickCount++"),
                "稳态路径必须每 tick 锁姿态");
        assertTrue(lastIndexOf(body, "lockDisplayPose(displayEntity)")
                        > lastIndexOf(body, "displayEntity.setCustomNameVisible(false)"),
                "重建路径（createDisplayEntity 之后）也必须锁姿态");
        assertEquals(2, countOccurrences(body, "lockDisplayPose("),
                "clientTick 里应恰好两处锁姿态：稳态每 tick 一次 + 重建后一次");
    }

    @Test
    void rendererRelocksRightBeforeDispatchAndKeepsOnlyTheSlowWallClockSpin() throws IOException {
        String code = stripComments(readSource(RENDERER));

        assertTrue(code.contains("MobDisplayCatalystBlockEntity.lockDisplayPose(display)"),
                "渲染前必须再锁一次姿态（renderer 侧兜底）");
        assertTrue(indexOf(code, "MobDisplayCatalystBlockEntity.lockDisplayPose(display)")
                        < indexOf(code, "dispatcher.render("),
                "lockDisplayPose 必须在 EntityRenderDispatcher#render 之前");

        // 唯一的旋转必须是墙钟慢速自转（0.04°/ms，9 秒一圈）；partialTick 只用来推进 getBob 等平滑动画。
        assertTrue(code.contains("MobDisplayCatalystRules.rotationDegrees(System.currentTimeMillis())"),
                "方块自转必须仍走 MobDisplayCatalystRules.rotationDegrees(墙钟)");
        assertTrue(code.contains("0.0F, partialTick, pose, buffer, packedLight"),
                "dispatcher.render 必须把 partialTick 照常传下去（生物朝向已归零，不会产生锯齿）");
        assertFalse(code.contains("rotationDegrees(display.getYRot()"),
                "自转不得取自实体 yRot（那会把刚被归零的朝向又带回来）");
    }

    // ---- 二、行为：真实 Mth.rotLerp 的锯齿与修复后恒 0 --------------------------

    @Test
    void staleInterpolationHistoryReproducesTheTwentyHertzWhip() {
        // 抓捕朝向 175°、插值历史 0°（Entity#load 不会同步 yBodyRotO）：partialTick 每 tick 从 0 扫到 1
        float capturedYaw = 175.0F;
        float min = Float.MAX_VALUE;
        float max = -Float.MAX_VALUE;
        for (int frame = 0; frame <= 20; frame++) {
            float partialTick = Math.min(frame / 20.0F, 1.0F);
            float renderedYaw = Mth.rotLerp(partialTick, 0.0F, capturedYaw);
            min = Math.min(min, renderedYaw);
            max = Math.max(max, renderedYaw);
        }
        assertEquals(0.0F, min, EPS);
        assertEquals(capturedYaw, max, EPS);
        assertTrue(max - min > 90.0F,
                "0 -> 175 的 rotLerp 摆幅必须 > 90°，这正是用户看到的剧烈左右摆动");
    }

    @Test
    void zeroedHistoryMakesYawCompletelyStillForAnyPartialTick() {
        for (int frame = 0; frame <= 40; frame++) {
            float partialTick = frame / 40.0F;
            assertEquals(0.0F, Mth.rotLerp(partialTick, 0.0F, 0.0F), EPS,
                    "yRotO == yRot == 0 时 rotLerp 必须恒为 0（partialTick=" + partialTick + "）");
        }
    }

    @Test
    void walkAnimationZeroingNeedsTheUpdateCallNotJustSetSpeed() {
        // 模拟"被抓捕时正在跑"的 WalkAnimationState：speed/speedOld 都不为 0、position 已有累积
        WalkAnimationState walk = new WalkAnimationState();
        walk.setSpeed(1.5F);
        walk.update(1.5F, 0.4F);
        assertTrue(walk.isMoving());
        assertTrue(walk.speed() > 0.0F);

        // 只 setSpeed(0)：speedOld 仍是旧值，speed(partialTick) 依然插值 -> 腿仍在抖
        walk.setSpeed(0.0F);
        assertTrue(walk.speed(0.0F) > 0.0F,
                "只 setSpeed(0) 不够：WalkAnimationState#speed(float) 会在旧 speedOld 与新 speed 之间插值");

        // 再 update(0, 1)：speedOld 也被拉成 0，任何 partialTick 都拿到 0
        float positionWhenFrozen = walk.position();
        walk.update(0.0F, 1.0F);
        assertFalse(walk.isMoving());
        assertEquals(0.0F, walk.speed(), EPS);
        for (float partialTick : new float[] {0.0F, 0.35F, 1.0F}) {
            assertEquals(0.0F, walk.speed(partialTick), EPS,
                    "update(0,1) 之后 speed(partialTick) 必须恒为 0（partialTick=" + partialTick + "）");
        }
        // position 没有公开 setter、无法归零，但 speed == 0 之后它不再推进（腿姿冻结而非抖动）；
        // 且 Entity#load 不持久化 walkAnimation.position，新建的展示实体本来就是 0。
        assertEquals(positionWhenFrozen, walk.position(), EPS);
    }

    // ---- 源码读取 / 注释剥离 / 方法体提取 -------------------------------------

    private static String readSource(String fileName) throws IOException {
        Path moduleRoot = Path.of("").toAbsolutePath();
        Path direct = moduleRoot.resolve(
                "src/main/java/dimblend/experience/compat/connected/mobdisplay/" + fileName);
        if (Files.exists(direct)) {
            return Files.readString(direct, StandardCharsets.UTF_8);
        }
        Path nested = moduleRoot.resolve(
                "dimblend-experience/src/main/java/dimblend/experience/compat/connected/mobdisplay/" + fileName);
        assertTrue(Files.exists(nested), "Could not locate " + fileName + " from " + moduleRoot);
        return Files.readString(nested, StandardCharsets.UTF_8);
    }

    /** 只剥行注释与块注释（本文件无需处理字符串字面量里的注释符号）。 */
    private static String stripComments(String source) {
        StringBuilder out = new StringBuilder(source.length());
        int i = 0;
        while (i < source.length()) {
            char c = source.charAt(i);
            char next = i + 1 < source.length() ? source.charAt(i + 1) : '\0';
            if (c == '/' && next == '/') {
                i += 2;
                while (i < source.length() && source.charAt(i) != '\n') {
                    i++;
                }
            } else if (c == '/' && next == '*') {
                i += 2;
                while (i < source.length()) {
                    if (source.charAt(i) == '*' && i + 1 < source.length() && source.charAt(i + 1) == '/') {
                        i += 2;
                        break;
                    }
                    i++;
                }
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    /** 取某个方法签名的整个方法体（含签名行，按花括号配平截取）；找不到签名时直接让测试红。 */
    private static String methodBody(String code, String signature) {
        int start = code.indexOf(signature);
        assertTrue(start >= 0, "找不到方法签名 `" + signature + "`（签名可能被改写，契约失效）");
        int open = code.indexOf('{', start);
        assertTrue(open > start, "方法 `" + signature + "` 没有方法体");
        int depth = 0;
        for (int i = open; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return code.substring(start, i + 1);
                }
            }
        }
        throw new AssertionError("方法 `" + signature + "` 的花括号没有配平");
    }

    private static int indexOf(String haystack, String needle) {
        int index = haystack.indexOf(needle);
        assertTrue(index >= 0, "源码里找不到 `" + needle + "`");
        return index;
    }

    private static int lastIndexOf(String haystack, String needle) {
        int index = haystack.lastIndexOf(needle);
        assertTrue(index >= 0, "源码里找不到 `" + needle + "`");
        return index;
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int from = 0;
        while (true) {
            int index = haystack.indexOf(needle, from);
            if (index < 0) {
                return count;
            }
            count++;
            from = index + needle.length();
        }
    }
}
