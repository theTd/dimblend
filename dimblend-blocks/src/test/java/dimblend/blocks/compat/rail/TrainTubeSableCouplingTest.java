package dimblend.blocks.compat.rail;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * {@link TrainTubeSable} 跨车厢挂接判定的回归测试。
 *
 * <p>用户缺陷：加压管道用扳手跨车厢连接时，<b>正确</b>的端点没有任何反馈、也连不上，错误端点反而有提示。
 * 根因是 {@code BlockEntitySubLevelActor#sable$getConnectionDependencies()} 的默认实现返回 {@code null}
 * （车厢上大量方块实体——消防水枪/水带、simurail 的 {@code GangwayJoint}、各类 Create 兼容 actor——
 * 都没覆写它；Sable 自己的 {@code SubLevelHelper} 明确写了 {@code if (dependencies == null) continue;}），
 * 旧实现直接 for-each 这个 null 值抛 NPE，异常逃出 {@code onWrenched}，于是"建链成功"和红色拒绝提示
 * 两条分支都没跑到，玩家看到的就是毫无反馈。</p>
 *
 * <p>本测试不构造 {@code SubLevel} / {@code ServerLevel}：判定内核 {@code dependenciesContainTarget} 的
 * actor 类型与依赖元素类型都是类型参数，直接喂 stub actor + UUID 即可（{@code SubLevel} 是抽象类且构造要
 * 带 plot 容器的 {@code Level}，单测里造不出来；硬造还会触发 MC 静态初始化）。</p>
 */
class TrainTubeSableCouplingTest {

    /**
     * 测试用 actor：{@code ids} 可以为 {@code null}（= 接口默认实现"我没有依赖"），
     * 也可以让取依赖那一步直接抛 NPE（模拟上游 {@code AutomaticCouplerBlockEntity} 的 {@code List.of(null)}）。
     */
    private record Actor(List<UUID> ids, boolean throwsOnAccess) {

        static Actor none() {
            return new Actor(null, false);
        }

        static Actor empty() {
            return new Actor(List.of(), false);
        }

        static Actor broken() {
            return new Actor(null, true);
        }

        /** 允许混入 {@code null} 元素（有的上游用可变集合登记依赖）。 */
        static Actor dependsOn(UUID... targets) {
            return new Actor(Arrays.asList(targets), false);
        }

        List<UUID> deps() {
            if (this.throwsOnAccess) {
                throw new NullPointerException("List.of(null)");
            }
            return this.ids;
        }
    }

    private static boolean anyDependsOn(@Nullable Iterable<Actor> actors, @Nullable UUID targetId) {
        return TrainTubeSable.<Actor, UUID>dependenciesContainTarget(actors, targetId, Actor::deps,
                Function.identity());
    }

    private static UUID uuid() {
        return UUID.randomUUID();
    }

    // ------------------------------------------------------------------ 精确回归用例

    @Test
    void actorReturningNullDependenciesBeforeCoupledActorStillMatches() {
        // 缺陷现场：车厢上先枚举到一个没覆写 sable$getConnectionDependencies() 的方块实体（返回 null），
        // 后面才是登记了依赖的自动车钩。旧实现第一下就 NPE，判定永远走不到第二项。
        UUID target = uuid();
        assertTrue(anyDependsOn(List.of(Actor.none(), Actor.dependsOn(target)), target),
                "返回 null 的 actor 必须被跳过，且后面的直接挂接 actor 仍要判定为 true");
    }

    @Test
    void actorThrowingOnAccessBeforeCoupledActorIsSkipped() {
        // 上游 AutomaticCouplerBlockEntity 对"对端子层级已卸载/已删除"的伙伴写 List.of(subLevel==null)，
        // 取依赖这一步就抛 NPE（dimblend-dev §8.42）。
        UUID target = uuid();
        assertTrue(anyDependsOn(List.of(Actor.broken(), Actor.dependsOn(target)), target),
                "抛 NPE 的坏 actor 必须就地兜住，不能中断整个挂接判定");
        // 只有坏 actor 时也不能炸，只能是"没判定到"
        assertFalse(anyDependsOn(List.of(Actor.broken()), target));
    }

    // ------------------------------------------------------------------ 退化输入

    @Test
    void allActorsWithoutDependenciesReturnsFalse() {
        UUID target = uuid();
        assertFalse(anyDependsOn(List.of(Actor.none(), Actor.none(), Actor.empty()), target));
        assertFalse(anyDependsOn(List.of(Actor.empty()), target));
        assertFalse(anyDependsOn(List.of(), target));
    }

    @Test
    void nullElementsInsideDependencyIterableAreSkipped() {
        UUID target = uuid();
        assertTrue(anyDependsOn(List.of(Actor.dependsOn(null, uuid(), target)), target),
                "依赖集合里的 null 元素要跳过，后面的命中项仍要生效");
        assertFalse(anyDependsOn(List.of(Actor.dependsOn(null, null)), target));
    }

    @Test
    void nullActorsIterableReturnsFalse() {
        assertFalse(anyDependsOn(null, uuid()));
    }

    @Test
    void nullActorEntriesAreSkipped() {
        UUID target = uuid();
        assertTrue(anyDependsOn(Arrays.asList(null, Actor.none(), Actor.dependsOn(target)), target),
                "列表里的 null actor 要跳过，不能当致命错误");
        assertFalse(anyDependsOn(Arrays.asList(null, (Actor) null), target));
    }

    @Test
    void nullTargetIdReturnsFalse() {
        // 目标子层级还没 setUniqueId（SubLevel#uniqueId 字段初值就是 null）时按"没有目标"处理
        assertFalse(anyDependsOn(List.of(Actor.dependsOn(uuid())), null));
    }

    // ------------------------------------------------------------------ 判定语义

    @Test
    void matchIsByUuidValueNotByInstanceIdentity() {
        // 依赖登记的是同一个子层级 id 的另一个 UUID 实例：必须按 equals 命中
        UUID registered = UUID.fromString("6f1c8c1e-0d9a-4b4e-8f5e-2b0c9d7a1e33");
        UUID target = UUID.fromString("6f1c8c1e-0d9a-4b4e-8f5e-2b0c9d7a1e33");
        assertTrue(anyDependsOn(List.of(Actor.dependsOn(registered)), target));
    }

    @Test
    void unrelatedDependencyDoesNotMatch() {
        assertFalse(anyDependsOn(List.of(Actor.dependsOn(uuid(), uuid())), uuid()));
    }
}
