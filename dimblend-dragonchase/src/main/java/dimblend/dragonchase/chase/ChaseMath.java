package dimblend.dragonchase.chase;

import java.util.Locale;

/**
 * Every number the chase director decides with, as pure functions.
 *
 * <p>No Minecraft types and no mutation: the director reads the world, calls these, and applies
 * the result. Requirement references are to {@code docs/requirements.md}; tuning values come from
 * {@code api.DragonChaseTuning} and are passed in.
 *
 * <p>Units follow the physics engine: speeds are metres per second, positions are world blocks,
 * ticks are server ticks (20 per second).
 */
public final class ChaseMath {
    /** Rails sit at y = 64 (docs/structures.md), so this is the track height every rule reasons about. */
    public static final double TRACK_Y = 64.0;

    /** §3.2: the prologue belongs to the scripted End region. */
    public static final int PROLOGUE_REGION = 32;
    /** §3.2: the prologue spawns when a corridor car's centre passes this local X. */
    public static final double PROLOGUE_LOCAL_START = 256.0;
    /** §3.2: the prologue leaves when the orbited car passes this local X. */
    public static final double PROLOGUE_LOCAL_END = 1536.0;
    /** §3.2: the orbited car must stay slower than this for {@link #PROLOGUE_SLOW_TICKS}. */
    public static final double PROLOGUE_SLOW_SPEED = 1.0;

    /** §2.1: only these corridor cars count as "the train". Half-width of the corridor band. */
    public static final double CORRIDOR_HALF_WIDTH = 16.0;

    /** §3.3: the nest is placed at this local X of the eligible End region. */
    public static final double NEST_LOCAL_X = 512.0;
    /** §3.4: passing this local X of the nest's region forces the next region to barren End. */
    public static final double FORCED_END_LOCAL_X = 1024.0;
    /** §3.3: the first End region that can carry a nest. */
    public static final int FIRST_NEST_REGION = 34;

    /** §3.3: a corridor car within this distance of the nest starts the chase. */
    public static final double CHASE_START_DISTANCE = 128.0;
    /** §3.3/§3.4: the nest tries to keep at least this gap ahead of the chasing train. */
    public static final double KEEP_AWAY_DISTANCE = 128.0;
    /** How much slack above {@link #KEEP_AWAY_DISTANCE} the nest aims for before it slows down. */
    public static final double KEEP_AWAY_MARGIN = 32.0;
    /** Gain of the gap-closing term, so the nest reacts without overshooting. */
    public static final double KEEP_AWAY_GAIN = 0.5;
    /** §3.3: the 2 m/s floor every force-loaded sub-level gets while a nest exists. */
    public static final double SPEED_FLOOR = 2.0;
    /** §3.4: minimum nest speed starts here. */
    public static final double MIN_SPEED_BASE = 10.0;
    /** §3.4: every full growth interval adds this much minimum speed. */
    public static final double MIN_SPEED_GROWTH_STEP = 0.5;

    /** §3.4: damage accumulated while the fireball cooldown runs is discarded. */
    public static final long FIREBALL_COOLDOWN_TICKS = 160L;
    /** §3.5: the car dropped by a fireball starts about this much slower than the chase train. */
    public static final double DROPPED_CAR_SPEED_LOSS = 5.0;
    /** §3.4: the dropped car waits at most this long for its footprint to load (brief: retry up to 5 s). */
    public static final long DROPPED_CAR_LOAD_TIMEOUT_TICKS = 100L;

    /** §3.4: health fractions at which the dragon breathes on the track, once each. */
    public static final double[] HEALTH_THRESHOLDS = {0.60D, 0.40D, 0.25D, 0.10D};

    /** §3.2: the orbited car must be this slow for 2 s. */
    public static final int PROLOGUE_SLOW_TICKS = 40;
    /** §3.2: the dragon needs 3 s of orbit before engine idling can send it away. */
    public static final int PROLOGUE_SPAWN_GRACE_TICKS = 60;
    /** §2.7: after a load, lose checks and the prologue slow rule wait this long. */
    public static final int LOAD_GRACE_TICKS = 200;

    /** §3.6: rewards and advancements reach players this far from the nest (or prologue orbit). */
    public static final double ADVANCEMENT_RADIUS = 512.0;
    /** §4: natural and chunk-generation spawns are denied this close to the nest while ACTIVE. */
    public static final double SPAWN_PAUSE_RADIUS = 256.0;

    /** §3.2: the prologue circles this far above the track (the locked 48-64 band's midpoint is close; the brief fixes 56). */
    public static final double PROLOGUE_HEIGHT = 56.0;
    /** §3.4: the chase dragon spawns this far behind and above the nest. */
    public static final double CHASE_SPAWN_BACK = 64.0;
    public static final double CHASE_SPAWN_UP = 32.0;

    /** §3.4: the fireball lands this far behind the nest. */
    public static final double FIREBALL_BACK = 12.0;
    public static final double FIREBALL_Y = 65.0;

    /** §3.4: breath zone geometry on the track (about 40 blocks long, |z| <= 3, y 64-68). */
    public static final double BREATH_BACK_FROM = 64.0;
    public static final double BREATH_BACK_TO = 24.0;
    public static final double BREATH_HALF_WIDTH = 3.0;
    public static final double BREATH_MIN_Y = 64.0;
    public static final double BREATH_MAX_Y = 68.0;

    /**
     * §3.4 minimum nest speed: {@code min(cap, 10 + (generation - 1) * step + 0.5 * floor(distance / growth))}.
     *
     * @param distanceTravelled blocks the nest has covered since the chase started
     * @param growthDistance    blocks per extra 0.5 m/s (tuning)
     */
    public static double minSpeed(double cap, int generation, double generationStep, double distanceTravelled,
            double growthDistance) {
        if (growthDistance <= 0.0D) {
            return cap;
        }
        double base = MIN_SPEED_BASE + Math.max(0, generation - 1) * generationStep;
        double growth = MIN_SPEED_GROWTH_STEP * Math.floor(Math.max(0.0D, distanceTravelled) / growthDistance);
        return Math.min(cap, base + growth);
    }

    /**
     * §2.8 / §3.4 max health: {@code (400 + 200 * (online - 1)) * multiplier}, fixed at spawn.
     *
     * <p>A server with no players still asks for 400: the director never spawns with zero online,
     * but a negative or zero count must not produce a negative health pool.
     */
    public static float maxHealth(int onlinePlayers, double healthMultiplier) {
        int players = Math.max(1, onlinePlayers);
        return (float) ((400.0D + 200.0D * (players - 1)) * healthMultiplier);
    }

    /**
     * §3.4 nest target speed: never below {@code minSpeed}, and when the gap to the leading car
     * shrinks below {@link #KEEP_AWAY_DISTANCE} + {@link #KEEP_AWAY_MARGIN} the nest speeds up by
     * {@link #KEEP_AWAY_GAIN} times the missing gap on top of the leader's own speed.
     */
    public static double nestTargetSpeed(double minSpeed, double nestX, double leaderX, double leaderSpeed) {
        double gap = nestX - leaderX;
        double desired = KEEP_AWAY_DISTANCE + KEEP_AWAY_MARGIN;
        if (gap >= desired) {
            return minSpeed;
        }
        return Math.max(minSpeed, leaderSpeed + KEEP_AWAY_GAIN * (desired - gap));
    }

    /** Velocity delta that reaches {@code targetVx} without ever decelerating. */
    public static double topUpDelta(double currentVx, double targetVx) {
        return Math.max(0.0D, targetVx - currentVx);
    }

    /**
     * §3.4 damage accumulation: hits during the 8 s cooldown are discarded, not banked.
     */
    public static double accumulateDamage(double accumulated, float applied, long gameTime, long cooldownUntil) {
        return gameTime < cooldownUntil ? accumulated : accumulated + applied;
    }

    /** True when the accumulated damage has reached the fireball threshold. */
    public static boolean fireballReady(double accumulated, double threshold) {
        return accumulated >= threshold;
    }

    /**
     * Index of the first threshold not yet fired that the health fraction is now below, or -1.
     * One threshold per call, so a single large hit does not stack four breaths in one tick.
     */
    public static int firstUnfiredThreshold(double healthFraction, boolean[] fired) {
        for (int i = 0; i < HEALTH_THRESHOLDS.length; i++) {
            if (!fired[i] && healthFraction < HEALTH_THRESHOLDS[i]) {
                return i;
            }
        }
        return -1;
    }

    /** Region index of a world block X (same floor rule as {@code BandLayout.regionOfBlockX}). */
    public static int regionOf(double worldX, int bandSize) {
        return Math.floorDiv((int) Math.floor(worldX), bandSize);
    }

    /** Local X of a world block X inside its region. */
    public static double localX(double worldX, int bandSize) {
        return worldX - (double) regionOf(worldX, bandSize) * bandSize;
    }

    /** §3.2: a car at this region/local X has reached the prologue spawn point (or already passed it). */
    public static boolean prologueThresholdReached(int region, double localX) {
        return region > PROLOGUE_REGION || (region == PROLOGUE_REGION && localX >= PROLOGUE_LOCAL_START);
    }

    /**
     * §3.3 nest eligibility (decision nest-trigger): an End region at or past
     * {@link #FIRST_NEST_REGION} with both neighbours not End, no chase running, never used before.
     */
    public static boolean nestRegionEligible(int region, String laneHere, String laneBefore, String laneAfter,
            boolean chaseRunning, boolean regionUsed) {
        return !chaseRunning
                && !regionUsed
                && region >= FIRST_NEST_REGION
                && "end".equals(laneHere)
                && !"end".equals(laneBefore)
                && !"end".equals(laneAfter);
    }

    /** §2.1: corridor membership used for every "the train" question. Positive side only. */
    public static boolean onCorridor(double centerX, double centerZ) {
        return centerX > 0.0D && Math.abs(centerZ) <= CORRIDOR_HALF_WIDTH;
    }

    /** True when a horizontal point is within {@code radius} of a centre. */
    public static boolean withinRadius(double x, double z, double centerX, double centerZ, double radius) {
        double dx = x - centerX;
        double dz = z - centerZ;
        return dx * dx + dz * dz <= radius * radius;
    }

    /**
     * §3.4 boss bar generation: Roman numerals for 1-63, plain Arabic from 64 (and for anything
     * outside that range, which only a misconfigured generation value can produce).
     */
    public static String romanOrArabic(int generation) {
        if (generation < 1 || generation > 63) {
            return Integer.toString(generation);
        }
        return roman(generation);
    }

    private static final int[] ROMAN_VALUES = {1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1};
    private static final String[] ROMAN_SYMBOLS = {"M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I"};

    private static String roman(int value) {
        StringBuilder out = new StringBuilder();
        int rest = value;
        for (int i = 0; i < ROMAN_VALUES.length; i++) {
            while (rest >= ROMAN_VALUES[i]) {
                out.append(ROMAN_SYMBOLS[i]);
                rest -= ROMAN_VALUES[i];
            }
        }
        return out.toString();
    }

    /** One decimal, independent of locale (the boss bar and command feedback share it). */
    public static String tenths(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    private ChaseMath() {
    }
}
