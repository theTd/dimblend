package dimblend.experience.tuning.perf;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import net.neoforged.fml.loading.FMLPaths;

/** spark's {@code config/spark/config.json} on this side (the server's game directory). */
public final class SparkConfigFile {
    private static final String HEADER = "spark configuration file - https://spark.lucko.me/docs/Configuration";

    public static Path path() {
        return FMLPaths.CONFIGDIR.get().resolve("spark").resolve("config.json");
    }

    /** Raw file content, or null when spark has not written its config yet. */
    public static String read() throws IOException {
        Path file = path();
        return Files.isRegularFile(file) ? Files.readString(file, StandardCharsets.UTF_8) : null;
    }

    /**
     * Sets {@code backgroundProfiler} to false, keeping every other key. spark reads the file at
     * startup only, so the running background sampler keeps going until the next restart.
     */
    public static void disableBackgroundProfiler() throws IOException {
        JsonObject root = new JsonObject();
        String existing = read();
        if (existing != null) {
            try {
                JsonElement parsed = JsonParser.parseString(existing);
                if (parsed.isJsonObject()) {
                    root = parsed.getAsJsonObject();
                }
            } catch (JsonParseException e) {
                // An unreadable file would make spark fall back to its defaults; replace it.
            }
        }
        if (!root.has("_header")) {
            root.addProperty("_header", HEADER);
        }
        root.addProperty("backgroundProfiler", false);
        Path file = path();
        Files.createDirectories(file.getParent());
        Path temp = Files.createTempFile(file.getParent(), "config", ".json.tmp");
        Files.writeString(temp, new GsonBuilder().setPrettyPrinting().create().toJson(root), StandardCharsets.UTF_8);
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private SparkConfigFile() {
    }
}
