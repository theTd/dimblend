package dimblend.experience.compat.create;

import java.util.ArrayList;
import java.util.List;

/**
 * 单个 Create 应力网络的账本事件环形缓冲（诊断探针，纯 Java，零 Minecraft 依赖）。
 * 只保留最近 {@link #CAPACITY} 条，记录 add/remove/addSilently/initFromTE/sync/updateXxxFor
 * 调用瞬间的账本读数，供柴油机过载探针在误报发生时回放"网络刚刚经历了什么"。
 * 仅服务端线程写读，不加锁。
 */
public final class KineticLedgerTrace {

    public static final int CAPACITY = 64;

    /**
     * @param serverTick  记录时服务端 tick（无服务端时为 -1）
     * @param op          操作名：INIT/ADD_SILENTLY/ADD/REMOVE/SYNC/CAP_FOR/STRESS_FOR
     * @param pos         相关成员位置（无则 "-"）
     * @param flag        操作相关布尔：ADD_SILENTLY/ADD=调用前已在 members（重复入网）；REMOVE=调用前是成员
     * @param argA        操作入参 A（INIT=maxStress，ADD_SILENTLY=lastCapacity，CAP_FOR/STRESS_FOR=新值）
     * @param argB        操作入参 B（INIT=currentStress，ADD_SILENTLY=lastStress）
     * @param members     调用瞬间 members 数
     * @param sources     调用瞬间 sources 数
     * @param unloadedCapacity 调用瞬间未加载容量份额
     * @param unloadedStress   调用瞬间未加载应力份额
     * @param unloadedMembers  调用瞬间未加载成员数
     * @param initialized 调用瞬间网络 initialized
     */
    public record Event(long serverTick, String op, String pos, boolean flag, float argA, float argB,
            int members, int sources, float unloadedCapacity, float unloadedStress, int unloadedMembers,
            boolean initialized) {

        public String format() {
            return "t=" + serverTick + " " + op + " pos=" + pos + " flag=" + flag
                    + " a=" + argA + " b=" + argB
                    + " members=" + members + " sources=" + sources
                    + " unloaded{cap=" + unloadedCapacity + ",stress=" + unloadedStress
                    + ",members=" + unloadedMembers + "} init=" + initialized;
        }
    }

    private final Event[] ring = new Event[CAPACITY];
    private int next;
    private int count;
    private long total;

    public void record(Event event) {
        this.ring[this.next] = event;
        this.next = (this.next + 1) % CAPACITY;
        if (this.count < CAPACITY) {
            this.count++;
        }
        this.total++;
    }

    /** 自网络创建起记录过的事件总数（含已被挤出环的）。 */
    public long total() {
        return this.total;
    }

    /** 最近至多 {@code limit} 条，旧→新。 */
    public List<Event> recent(int limit) {
        int n = Math.min(Math.min(limit, this.count), CAPACITY);
        List<Event> out = new ArrayList<>(n);
        int start = (this.next - n + CAPACITY) % CAPACITY;
        for (int i = 0; i < n; i++) {
            out.add(this.ring[(start + i) % CAPACITY]);
        }
        return out;
    }
}
