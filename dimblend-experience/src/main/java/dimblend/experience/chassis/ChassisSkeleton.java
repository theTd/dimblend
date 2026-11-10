package dimblend.experience.chassis;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 标准底盘必要骨架识别（纯规则，不读世界、不建约束）。
 *
 * <p>只认 H/D/B/P/W/I 这些结构符号，忽略洁净板 N、传动轴 S、车钩 C 以及任何上装/装饰
 * （观测里多出来的方块不参与匹配）。坐标以装配 anchor（plot center）为原点，与
 * {@link ChassisBlueprint.Cell} 同系；调用方负责把车体方块归一化到这个原点。</p>
 *
 * <p>四朝向：对每个蓝图尝试 0/90/180/270 四个绕 Y 的四分之一转。蓝图本身在 180° 下
 * 对称（两端车钩/转向架对称），所以同一辆车通常同时匹配 q 与 q+2；这里返回全部匹配，
 * 由调用方决定取哪个朝向，不把前端猜死。bogey 布局（W/I 的位置与类型）要求精确一致，
 * 多一个或少一个转向架都不算标准车架。</p>
 */
public final class ChassisSkeleton {
    /** 必要骨架符号；N/S/C 与其它上装方块不进匹配。 */
    public static final Set<Character> REQUIRED_SYMBOLS = Set.of('H', 'D', 'B', 'P', 'W', 'I');

    public record Observation(int x, int z, char symbol) {
    }

    public record Point(int x, int z) {
    }

    public record Match(ChassisBlueprint blueprint, int quarterTurns) {
    }

    public static List<ChassisBlueprint.Cell> requiredCells(ChassisBlueprint blueprint) {
        List<ChassisBlueprint.Cell> cells = new ArrayList<>();
        for (ChassisBlueprint.Cell cell : blueprint.cells()) {
            if (REQUIRED_SYMBOLS.contains(cell.symbol())) {
                cells.add(cell);
            }
        }
        return List.copyOf(cells);
    }

    /**
     * @return 所有匹配的蓝图+朝向；0 条表示不是标准必要骨架，多条表示朝向/端别对称。
     */
    public static List<Match> identify(Collection<Observation> observed) {
        if (observed == null || observed.isEmpty()) {
            return List.of();
        }
        Map<Point, Set<Character>> cells = new HashMap<>();
        for (Observation observation : observed) {
            if (observation == null || !REQUIRED_SYMBOLS.contains(observation.symbol())) {
                continue;
            }
            cells.computeIfAbsent(new Point(observation.x(), observation.z()), key -> new HashSet<>())
                    .add(observation.symbol());
        }
        if (cells.isEmpty()) {
            return List.of();
        }
        List<Match> matches = new ArrayList<>();
        for (ChassisBlueprint blueprint : ChassisBlueprint.values()) {
            List<ChassisBlueprint.Cell> required = requiredCells(blueprint);
            for (int quarterTurns = 0; quarterTurns < 4; quarterTurns++) {
                if (!bogeysMatch(required, cells, quarterTurns)) {
                    continue;
                }
                if (skeletonMatches(required, cells, quarterTurns)) {
                    matches.add(new Match(blueprint, quarterTurns));
                }
            }
        }
        return List.copyOf(matches);
    }

    /** 绕 Y 的四分之一转；q 允许任意整数，内部取模。 */
    public static Point rotate(int x, int z, int quarterTurns) {
        return switch (Math.floorMod(quarterTurns, 4)) {
            case 0 -> new Point(x, z);
            case 1 -> new Point(-z, x);
            case 2 -> new Point(-x, -z);
            case 3 -> new Point(z, -x);
            default -> throw new IllegalStateException("quarterTurns");
        };
    }

    private static boolean skeletonMatches(List<ChassisBlueprint.Cell> required,
                                           Map<Point, Set<Character>> cells, int quarterTurns) {
        for (ChassisBlueprint.Cell cell : required) {
            Point point = rotate(cell.x(), cell.z(), quarterTurns);
            Set<Character> symbols = cells.get(point);
            if (symbols == null || !symbols.contains(cell.symbol())) {
                return false;
            }
        }
        return true;
    }

    /** W/I 的位置与类型必须与蓝图完全一致；上装不能新增转向架。 */
    private static boolean bogeysMatch(List<ChassisBlueprint.Cell> required,
                                       Map<Point, Set<Character>> cells, int quarterTurns) {
        Set<Point> expected = new HashSet<>();
        for (ChassisBlueprint.Cell cell : required) {
            if (cell.symbol() == 'W' || cell.symbol() == 'I') {
                expected.add(rotate(cell.x(), cell.z(), quarterTurns));
            }
        }
        Set<Point> actual = new HashSet<>();
        for (Map.Entry<Point, Set<Character>> entry : cells.entrySet()) {
            if (entry.getValue().contains('W') || entry.getValue().contains('I')) {
                actual.add(entry.getKey());
            }
        }
        return expected.equals(actual);
    }

    private ChassisSkeleton() {
    }
}
