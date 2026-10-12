package dimblend.experience.tuning.perf;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.FriendlyByteBuf;

/**
 * One evaluated checklist step. {@code args} fill both the {@code .current} and the
 * {@code .advice.<advice>} translations; {@code fixable} means the one-click fix applies now.
 */
public record PerfCheckResult(PerfCheck check, PerfScope scope, PerfStatus status, String advice,
                              List<String> args, boolean fixable) {
    private static final int MAX_ARGS = 8;
    /** Network limit per argument; values are cut to this when built, so encoding can never fail. */
    public static final int MAX_ARG_LENGTH = 256;

    public PerfCheckResult {
        if (!check.advices().contains(advice)) {
            throw new IllegalArgumentException("Unknown advice " + advice + " for " + check.id());
        }
        if (fixable && !check.fixable()) {
            throw new IllegalArgumentException(check.id() + " has no fix");
        }
        if (args.size() > MAX_ARGS) {
            throw new IllegalArgumentException(check.id() + " has more than " + MAX_ARGS + " arguments");
        }
        // e.g. generational ZGC reports four MXBean names (70 characters); never let a value break the payload.
        args = args.stream().map(arg -> arg.length() <= MAX_ARG_LENGTH
                ? arg : arg.substring(0, MAX_ARG_LENGTH - 1) + "…").toList();
    }

    public static PerfCheckResult of(PerfCheck check, PerfScope scope, PerfStatus status, String advice,
                                     boolean fixable, String... args) {
        return new PerfCheckResult(check, scope, status, advice, List.of(args), fixable);
    }

    public String adviceKey() { return check.key() + ".advice." + advice; }

    public void write(FriendlyByteBuf buffer) {
        buffer.writeUtf(check.id(), 64);
        buffer.writeEnum(scope);
        buffer.writeEnum(status);
        buffer.writeUtf(advice, 32);
        buffer.writeVarInt(args.size());
        args.forEach(arg -> buffer.writeUtf(arg, MAX_ARG_LENGTH));
        buffer.writeBoolean(fixable);
    }

    /** Returns null for a step this client does not know (newer server), so it can be skipped. */
    public static PerfCheckResult read(FriendlyByteBuf buffer) {
        PerfCheck check = PerfCheck.byId(buffer.readUtf(64));
        PerfScope scope = buffer.readEnum(PerfScope.class);
        PerfStatus status = buffer.readEnum(PerfStatus.class);
        String advice = buffer.readUtf(32);
        int count = Math.min(buffer.readVarInt(), MAX_ARGS);
        List<String> args = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            args.add(buffer.readUtf(MAX_ARG_LENGTH));
        }
        boolean fixable = buffer.readBoolean();
        if (check == null || !check.advices().contains(advice) || (fixable && !check.fixable())) {
            return null;
        }
        return new PerfCheckResult(check, scope, status, advice, args, fixable);
    }
}
