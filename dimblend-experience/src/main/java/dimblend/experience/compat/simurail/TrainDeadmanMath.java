package dimblend.experience.compat.simurail;

import java.util.ArrayList;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * E7' 无人值守刹车锁存的纯函数。
 * 速度单位与转向架 {@code visualSpeed} 一致（1 b/s = 1 m/s），坐标均为世界坐标。
 */
public final class TrainDeadmanMath {

    /** One bogey, in world coordinates; speed is the magnitude of visualSpeed in m/s. */
    public record Probe(double x, double y, double z, double speed) {
    }

    /** A loaded Sable carriage and its actual automatic-coupler connections. */
    public record Carriage(java.util.UUID id, List<java.util.UUID> partners, List<Probe> bogeys) {
    }

    /** A complete connected consist, retaining IDs for whole-consist brake/reset propagation. */
    public record Consist(List<UUID> carriageIds, List<Probe> bogeys) {
    }

    /** Connected components of the carriage graph, including carriages without bogeys as bridges. */
    public static List<Consist> cluster(List<Carriage> carriages) {
        int n = carriages.size();
        int[] parent = new int[n];
        java.util.Map<java.util.UUID, Integer> indices = new java.util.HashMap<>();
        for (int i = 0; i < n; i++) {
            parent[i] = i;
            indices.put(carriages.get(i).id(), i);
        }
        for (int i = 0; i < n; i++) {
            for (java.util.UUID partner : carriages.get(i).partners()) {
                Integer other = indices.get(partner);
                if (other != null) {
                    parent[find(parent, i)] = find(parent, other);
                }
            }
        }
        java.util.Map<Integer, List<Carriage>> groups = new java.util.LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            groups.computeIfAbsent(find(parent, i), key -> new ArrayList<>()).add(carriages.get(i));
        }
        List<Consist> consists = new ArrayList<>();
        for (List<Carriage> group : groups.values()) {
            List<UUID> ids = new ArrayList<>();
            List<Probe> bogeys = new ArrayList<>();
            for (Carriage carriage : group) {
                ids.add(carriage.id());
                bogeys.addAll(carriage.bogeys());
            }
            consists.add(new Consist(List.copyOf(ids), List.copyOf(bogeys)));
        }
        return consists;
    }

    /** 编组内是否存在超速车架（{@link TrainSpeedGateMath#isFast}）。 */
    public static boolean hasFast(List<Probe> group) {
        for (Probe probe : group) {
            if (TrainSpeedGateMath.isFast(probe.speed())) {
                return true;
            }
        }
        return false;
    }

    /** 编组上离给定点最近的车架的距离平方（距离判定不看该架速度——2026-10-15 用户口径）。 */
    public static double nearestDistanceSq(List<Probe> group,
                                           double px, double py, double pz) {
        double nearestSq = Double.MAX_VALUE;
        for (Probe probe : group) {
            double dx = px - probe.x();
            double dy = py - probe.y();
            double dz = pz - probe.z();
            double distSq = dx * dx + dy * dy + dz * dz;
            if (distSq < nearestSq) {
                nearestSq = distSq;
            }
        }
        return nearestSq;
    }

    /** Initial observation establishes a baseline; any subsequent raw signal-level change is an edge. */
    public static boolean signalChanged(Integer previous, int current) {
        return previous != null && previous != current;
    }

    /**
     * Propagate a latch across current coupler connections. Any signal edge resets its entire
     * consist and takes precedence over any new latching in this update. Unrelated consists
     * retain their own state. Absent carriage IDs retain the latch until they return or the world unloads.
     */
    public static Set<UUID> updateLatches(Set<UUID> previous, List<Consist> consists,
                                         Set<UUID> signalChanges, Predicate<Consist> unattended) {
        Set<UUID> next = new HashSet<>(previous);
        for (Consist consist : consists) {
            boolean latched = consist.carriageIds().stream().anyMatch(previous::contains);
            boolean reset = consist.carriageIds().stream().anyMatch(signalChanges::contains);
            if (reset) {
                next.removeAll(consist.carriageIds());
            } else if (latched || unattended.test(consist)) {
                next.addAll(consist.carriageIds());
            }
        }
        return Set.copyOf(next);
    }

    private static int find(int[] parent, int i) {
        while (parent[i] != i) {
            parent[i] = parent[parent[i]];
            i = parent[i];
        }
        return i;
    }

    private TrainDeadmanMath() {
    }
}
