package dimblend.dragonchase;

import dimblend.dragonchase.api.DragonChaseTuning.Option;
import java.util.EnumMap;
import java.util.Map;
import net.neoforged.neoforge.common.ModConfigSpec;

/** Server config: the eight tuning values (requirements.md §3.1). File: {@code dimblend_dragonchase-server.toml}. */
public final class Config {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();
    private static final Map<Option, ModConfigSpec.DoubleValue> VALUES = new EnumMap<>(Option.class);

    static {
        BUILDER.push("dragon_chase");
        for (Option option : Option.values()) {
            VALUES.put(option, BUILDER
                    .comment(option.checkbox()
                            ? option.id() + " (0 = off, 1 = on)"
                            : option.id() + " (" + option.format(option.minimum()) + "-" + option.format(option.maximum())
                                    + ", step " + option.step() + ")")
                    .defineInRange(option.id(), option.defaultValue(), option.minimum(), option.maximum()));
        }
        BUILDER.pop();
    }

    public static final ModConfigSpec SPEC = BUILDER.build();

    public static double value(Option option) {
        return VALUES.get(option).get();
    }

    public static void setValue(Option option, double value) {
        VALUES.get(option).set(value);
        SPEC.save();
    }

    private Config() {
    }
}
