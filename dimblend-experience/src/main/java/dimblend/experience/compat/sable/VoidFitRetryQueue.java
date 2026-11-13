package dimblend.experience.compat.sable;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * {@link SableVoidFit} 重试队列与慢速自愈倒计时的纯调度逻辑（不引用任何 Sable 类，便于单测直验）。
 */
final class VoidFitRetryQueue {

    record RetryCandidate(UUID id, boolean verifyDue) {
    }

    /**
     * 按 FIFO 顺序从重试集合取出本轮扫描预算内的重试候选（先出队，若仍未写完再由
     * {@link #finishCandidate} 排回队尾实现轮转），并按正常节拍推进其自愈倒计时——
     * 常规预算重试不再强制开启逐格世界核验，只有自愈倒计时归零时才置 {@code verifyDue=true}。
     */
    static List<RetryCandidate> pollCandidates(VoidFitTracker tracker, Set<UUID> retry, int budget) {
        if (budget <= 0 || retry.isEmpty()) {
            return List.of();
        }
        List<RetryCandidate> picked = new ArrayList<>(Math.min(budget, retry.size()));
        Iterator<UUID> it = retry.iterator();
        while (it.hasNext() && picked.size() < budget) {
            UUID id = it.next();
            it.remove();
            VoidFitTracker.VehicleFit fit = tracker.vehicles().get(id);
            boolean verifyDue = fit != null && --fit.verifyCountdown <= 0;
            picked.add(new RetryCandidate(id, verifyDue));
        }
        return picked;
    }

    /**
     * 重试执行后的队列维护：未写完则排回队尾（保证多车厢超出扫描预算时轮询公平），
     * 已写完但目标为空（plot 懒加载未就位）则按上限续试。
     */
    static void finishCandidate(VoidFitTracker tracker, Set<UUID> retry, UUID id,
            boolean complete, int emptyTargetRetryLimit) {
        if (!complete) {
            retry.add(id);
            return;
        }
        requeueIfTargetMissing(tracker, retry, id, emptyTargetRetryLimit);
    }

    static void requeueIfTargetMissing(VoidFitTracker tracker, Set<UUID> retry, UUID id,
            int emptyTargetRetryLimit) {
        VoidFitTracker.VehicleFit fit = tracker.vehicles().get(id);
        if (fit == null) {
            retry.remove(id);
            return;
        }
        if (!fit.targetCells.isEmpty()) {
            fit.emptyRetries = 0;
            retry.remove(id);
            return;
        }
        if (++fit.emptyRetries < emptyTargetRetryLimit) {
            retry.add(id);
        } else {
            fit.emptyRetries = 0;
            retry.remove(id);
        }
    }

    private VoidFitRetryQueue() {
    }
}
