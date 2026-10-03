package dimblend.radio.client;

import dimblend.radio.acoustics.PathingField;
import dimblend.radio.client.AcousticBakeScheduler.BakeState;
import dimblend.radio.client.AcousticBakeScheduler.Inspection;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/**
 * The bake view's text: what the idle gate decided and why, then each radio's bake, what it waits
 * for, and the diffracted path its session renders. Plain strings with section-sign colour codes,
 * for the debug screen and the bake view's own panel.
 */
final class AcousticBakeReadout {
    private static final String[] COMPASS = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};

    /** RGB of a bake state in the world and in text. */
    static int colour(BakeState state) {
        return switch (state) {
            case VALID -> 0x55FF55;
            case STALE -> 0xFFFF55;
            case BAKING -> 0x55FFFF;
            case WAITING -> 0xFFAA00;
            case LOADING, CHECKING -> 0xAAAAAA;
            case NO_AIR -> 0x777777;
            case FAILED -> 0xFF55FF;
        };
    }

    /** Probes in or next to a section that changed since the bake, and the section outlines. */
    static final int CHANGED = 0xFF5555;

    private static String code(BakeState state) {
        return switch (state) {
            case VALID -> "§a";
            case STALE -> "§e";
            case BAKING -> "§b";
            case WAITING -> "§6";
            case LOADING, CHECKING -> "§7";
            case NO_AIR -> "§8";
            case FAILED -> "§d";
        };
    }

    static List<String> lines(AcousticIdleGate.Status gate, boolean baking, List<Inspection> radios, Vec3 listener,
            Function<BlockPos, PathingField> paths) {
        List<String> lines = new ArrayList<>();
        lines.add("§nRadio acoustic bakes§r  (/radioacoustics bakeview off)");
        lines.add(gate(gate, baking));
        if (radios.isEmpty()) lines.add("§7No playing radio with a bake region here (radios on structures are not baked)");
        for (Inspection radio : radios) {
            BlockPos pos = radio.radio();
            lines.add(String.format(Locale.ROOT, "%s %d %d %d, %.0f m: %s§r, %s", code(radio.state()) + "■§r", pos.getX(),
                    pos.getY(), pos.getZ(), Vec3.atCenterOf(pos).distanceTo(listener), headline(radio), detail(radio, gate, baking)));
            if (radio.bake() != null) lines.add("    " + path(paths.apply(pos)));
        }
        lines.add("§7Probes: §agreen§7 valid, §eyellow§7 stale, §cred§7 near a changed section (outlined), §bblue§7 baking");
        return lines;
    }

    /** "VALID · 812 probes · 4-block cells". */
    static String headline(Inspection radio) {
        String state = code(radio.state()) + radio.state().name().replace('_', ' ');
        if (radio.bake() == null) return state;
        return String.format(Locale.ROOT, "%s · %d probes · %d-block cells · %.1f MiB", state, radio.bake().probeCount(),
                radio.bake().cellSize(), radio.bake().batch().length / (1024.0 * 1024.0));
    }

    /** What the radio's bake is doing or waiting for. */
    static String detail(Inspection radio, AcousticIdleGate.Status gate, boolean baking) {
        String changes = radio.changedSections().length == 1 ? "1 section changed"
                : radio.changedSections().length + " sections changed";
        return switch (radio.state()) {
            case LOADING -> "reading its file";
            case CHECKING -> radio.regionLoaded() ? "comparing with its file" : "waiting for chunks to load";
            case BAKING -> String.format(Locale.ROOT, "baking for %.1f s on %d %s", radio.bakingFor(), radio.bakeThreads(),
                    radio.bakeThreads() == 1 ? "thread" : "threads");
            case VALID -> radio.lastBakeSeconds() > 0
                    ? String.format(Locale.ROOT, "baked in %.1f s on %d %s", radio.lastBakeSeconds(), radio.bakeThreads(),
                            radio.bakeThreads() == 1 ? "thread" : "threads")
                    : "from disk, region unchanged";
            case STALE -> !radio.verified() && radio.changedSections().length == 0
                    ? "routes validated; compared with the region once it is unchanged for a second"
                    : changes + "; routes validated" + (radio.needsBake() ? "; rebake " + waiting(radio, gate, baking) : "");
            case WAITING -> "bake " + waiting(radio, gate, baking);
            case NO_AIR -> "no walkable air around it: nothing to bake";
            case FAILED -> "bake failed " + radio.failures() + " times; see the log";
        };
    }

    /** Why a due bake has not started yet. */
    static String waiting(Inspection radio, AcousticIdleGate.Status gate, boolean baking) {
        if (!radio.regionLoaded()) return "waits for chunks to load";
        if (radio.retryIn() > 0) {
            return String.format(Locale.ROOT, "retries in %.0f s (%d failed)", Math.ceil(radio.retryIn()), radio.failures());
        }
        if (radio.stableIn() > 0) {
            return String.format(Locale.ROOT, "once unchanged for %.0f more s", Math.ceil(radio.stableIn()));
        }
        if (baking) return "after the running bake";
        if (!gate.allowance().open()) return "waits for an idle client";
        if (radio.deferredProbes() > 0) {
            return radio.deferredProbes() + " probes: waits until you are away";
        }
        return "starting";
    }

    static String gate(AcousticIdleGate.Status gate, boolean baking) {
        if (baking) return "Idle gate: §bbaking§r (CPU load is measured again afterwards)";
        if (gate.loading()) return "Idle gate: §cclosed§r, loading";
        var allowance = gate.allowance();
        String threads = allowance.threads() == 1 ? "1 thread" : allowance.threads() + " threads";
        if (allowance.open()) {
            String why = switch (gate.activity()) {
                case PAUSED -> "away (paused)";
                case UNFOCUSED -> "away (window in the background)";
                case STILL -> "away (no movement for 30 s)";
                case PLAYING -> "CPU idle (load " + loads(gate.loads()) + ")";
            };
            return String.format(Locale.ROOT, "Idle gate: §aopen§r, %s: %s, bakes up to %.0f s", why, threads, allowance.seconds());
        }
        if (gate.loads().length < AcousticIdleGate.LOAD_SAMPLES) {
            return String.format(Locale.ROOT, "Idle gate: §cclosed§r, measuring CPU load (%d of %d s)", gate.loads().length,
                    AcousticIdleGate.LOAD_SAMPLES);
        }
        for (double load : gate.loads()) {
            if (Double.isNaN(load)) return "Idle gate: §cclosed§r, CPU load unknown: bakes only while you are away";
        }
        if (!gate.smoothFrames()) {
            return String.format(Locale.ROOT, "Idle gate: §cclosed§r, frames behind (%d of %d fps)", gate.fps(), gate.targetFps());
        }
        return "Idle gate: §cclosed§r, CPU busy (load " + loads(gate.loads()) + ")";
    }

    private static String loads(double[] loads) {
        StringBuilder text = new StringBuilder();
        for (double load : loads) {
            if (!text.isEmpty()) text.append(' ');
            text.append(Double.isNaN(load) ? "?" : String.format(Locale.ROOT, "%.0f%%", load * 100));
        }
        return text.toString();
    }

    /** "path: 42 m, from SE, 5° up; low/mid/high -3/-7/-12 dB". */
    static String path(PathingField field) {
        if (field == null) return "§7path: none (in view, out of reach, or no route)";
        Vec3 arrival = field.arrival();
        String from = arrival == null ? "no direction" : "from " + compass(arrival);
        return String.format(Locale.ROOT, "path: %.0f m, %s; low/mid/high %s/%s/%s dB", field.length(), from,
                decibels(field.eq()[0]), decibels(field.eq()[1]), decibels(field.eq()[2]));
    }

    /** "SE, 5° up" for a unit vector in world axes (north is -z, east +x). */
    static String compass(Vec3 arrival) {
        double bearing = Math.toDegrees(Math.atan2(arrival.x, -arrival.z));
        String point = COMPASS[Math.floorMod((int) Math.round(bearing / 45), 8)];
        long elevation = Math.round(Math.toDegrees(Math.asin(Math.max(-1, Math.min(1, arrival.y)))));
        if (elevation == 0) return point;
        return point + ", " + Math.abs(elevation) + "° " + (elevation > 0 ? "up" : "down");
    }

    private static String decibels(float gain) {
        return gain > 1e-6 ? String.format(Locale.ROOT, "%.0f", 20 * Math.log10(gain)) : "-inf";
    }

    private AcousticBakeReadout() { }
}
