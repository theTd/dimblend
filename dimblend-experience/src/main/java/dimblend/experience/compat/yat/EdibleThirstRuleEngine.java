package dimblend.experience.compat.yat;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Pure-Java half of the YAT edible overrides: the rule table ({@code thirst_edible_overrides.json})
 * and its loader. Deliberately free of Minecraft types so the bundled table can be parsed and
 * validated by a plain JUnit test without bootstrapping a game or registry.
 *
 * <p>Only items matching a rule appear in the table; every other edible is left to whatever
 * {@code config/yetanotherthirst/items.toml} already configures. Values are fixed per rule:</p>
 *
 * <ul>
 *   <li>A - a producing recipe directly uses water: drink {@code 6/8}, food {@code 4/5}.</li>
 *   <li>B - no water, but the drink is a fruit or has a fruit ingredient: {@code 3/4}.</li>
 *   <li>C - no water, but the food is a fruit or has a fruit ingredient: {@code 1/1}.</li>
 * </ul>
 */
public final class EdibleThirstRuleEngine {

    /** Classpath location of the bundled table. */
    public static final String RESOURCE = "/dimblend_experience/thirst_edible_overrides.json";

    private static final int FORMAT = 1;
    /** Namespace and path of a resource location, without the leading slash of tags. */
    private static final Pattern ITEM_ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");

    public enum Kind {
        DRINK,
        FOOD
    }

    public enum Rule {
        A,
        B,
        C
    }

    /** One override: the item, which YAT map it belongs to, and the rule that produced it. */
    public record Entry(String item, Kind kind, Rule rule, int thirst, int quenched) {
    }

    /** Immutable table in file order, with an item-id index. */
    public static final class Table {
        private final List<Entry> entries;
        private final Map<String, Entry> byItem;

        private Table(List<Entry> entries) {
            this.entries = List.copyOf(entries);
            Map<String, Entry> index = new LinkedHashMap<>();
            for (Entry entry : entries) {
                if (index.put(entry.item(), entry) != null) {
                    throw new IllegalArgumentException("Duplicate override entry for " + entry.item());
                }
            }
            this.byItem = Collections.unmodifiableMap(index);
        }

        public List<Entry> entries() {
            return entries;
        }

        public int size() {
            return entries.size();
        }

        public Entry get(String item) {
            return byItem.get(item);
        }

        public boolean contains(String item) {
            return byItem.containsKey(item);
        }
    }

    /** Reads the table shipped inside this mod jar. */
    public static Table loadBundled() {
        InputStream in = EdibleThirstRuleEngine.class.getResourceAsStream(RESOURCE);
        if (in == null) {
            throw new IllegalStateException("Missing classpath resource " + RESOURCE);
        }
        try (InputStream stream = in) {
            return load(new InputStreamReader(stream, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read " + RESOURCE, e);
        }
    }

    /**
     * Parses and fully validates a table. Every failure is an {@link IllegalArgumentException}
     * that names the offending entry, so a broken resource fails at startup instead of silently
     * leaving items unconfigured.
     */
    public static Table load(Reader reader) {
        JsonObject root;
        try {
            JsonElement parsed = JsonParser.parseReader(reader);
            if (!parsed.isJsonObject()) {
                throw new IllegalArgumentException("Override table root must be a JSON object");
            }
            root = parsed.getAsJsonObject();
        } catch (JsonParseException e) {
            throw new IllegalArgumentException("Override table is not valid JSON", e);
        }
        int format = requireInt(root, "format", "override table root");
        if (format != FORMAT) {
            throw new IllegalArgumentException(
                    "Unsupported override table format " + format + " (expected " + FORMAT + ")");
        }
        JsonElement entriesElement = root.get("entries");
        if (entriesElement == null || !entriesElement.isJsonArray()) {
            throw new IllegalArgumentException("Override table is missing the entries array");
        }
        JsonArray array = entriesElement.getAsJsonArray();
        if (array.isEmpty()) {
            throw new IllegalArgumentException("Override table entries array is empty");
        }
        List<Entry> entries = new ArrayList<>(array.size());
        for (int i = 0; i < array.size(); i++) {
            entries.add(parseEntry(array.get(i), i));
        }
        return new Table(entries);
    }

    /** Thirst granted by a rule; Rule A differs for drinks and foods. */
    public static int thirstFor(Kind kind, Rule rule) {
        return switch (rule) {
            case A -> kind == Kind.DRINK ? 6 : 4;
            case B -> 3;
            case C -> 1;
        };
    }

    /** Quenched granted by a rule; Rule A differs for drinks and foods. */
    public static int quenchedFor(Kind kind, Rule rule) {
        return switch (rule) {
            case A -> kind == Kind.DRINK ? 8 : 5;
            case B -> 4;
            case C -> 1;
        };
    }

    private static Entry parseEntry(JsonElement element, int index) {
        if (!element.isJsonObject()) {
            throw new IllegalArgumentException("Override entry #" + index + " must be a JSON object");
        }
        JsonObject object = element.getAsJsonObject();
        String item = requireString(object, "item", "override entry #" + index);
        if (!ITEM_ID.matcher(item).matches()) {
            throw new IllegalArgumentException("Override entry #" + index + " has an invalid item id: " + item);
        }
        Kind kind = parseEnum(Kind.class, requireString(object, "kind", item), "kind", item);
        Rule rule = parseEnum(Rule.class, requireString(object, "rule", item), "rule", item);
        requireRuleKind(kind, rule, item);
        int thirst = requireInt(object, "thirst", item);
        int quenched = requireInt(object, "quenched", item);
        int expectedThirst = thirstFor(kind, rule);
        int expectedQuenched = quenchedFor(kind, rule);
        if (thirst != expectedThirst || quenched != expectedQuenched) {
            throw new IllegalArgumentException("Override entry " + item + " declares rule " + rule + " as "
                    + kind + " but carries " + thirst + "/" + quenched + "; expected "
                    + expectedThirst + "/" + expectedQuenched);
        }
        return new Entry(item, kind, rule, thirst, quenched);
    }

    private static void requireRuleKind(Kind kind, Rule rule, String item) {
        if (rule == Rule.B && kind != Kind.DRINK) {
            throw new IllegalArgumentException("Override entry " + item + " uses fruit-drink rule B but is a food");
        }
        if (rule == Rule.C && kind != Kind.FOOD) {
            throw new IllegalArgumentException("Override entry " + item + " uses fruit-food rule C but is a drink");
        }
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String raw, String field, String item) {
        try {
            return Enum.valueOf(type, raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Override entry " + item + " has unknown " + field + ": " + raw, e);
        }
    }

    private static String requireString(JsonObject object, String key, String owner) {
        JsonElement element = object.get(key);
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException(owner + " is missing string field '" + key + "'");
        }
        return element.getAsString();
    }

    private static int requireInt(JsonObject object, String key, String owner) {
        JsonElement element = object.get(key);
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException(owner + " is missing number field '" + key + "'");
        }
        double raw = element.getAsDouble();
        int value = element.getAsInt();
        if (raw != value) {
            throw new IllegalArgumentException(owner + " field '" + key + "' must be an integer but is " + raw);
        }
        return value;
    }

    private EdibleThirstRuleEngine() {
    }
}
