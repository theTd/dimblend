package dimblend.radio;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import net.minecraft.Util;
import net.minecraft.util.RandomSource;
import net.neoforged.fml.loading.FMLPaths;

/**
 * 服务端曲库：站台 →（hash → 时长秒）。只记元数据，不存音频。
 *
 * <p>双来源并集：服务端直扫游戏目录（hash 全、时长未知，占位 -1）+
 * 客户端 hello 上报（hash + 时长，取 max）。hello 只增不减，永不覆盖掉服务端扫到的 hash
 * （旧代码按站整表替换，空 hello 会洗掉全表）。</p>
 */
public final class RadioCatalog {
    private static final Map<Integer, Map<String, Double>> LENGTHS = new HashMap<>();
    private static volatile boolean serverScanned;

    public synchronized static void put(int station, Map<String, Double> hashToSeconds) {
        LENGTHS.put(station, new HashMap<>(hashToSeconds));
    }

    public synchronized static long lengthTicks(int station, String hash) {
        Double seconds = null;
        Map<String, Double> stationMap = LENGTHS.get(station);
        if (stationMap != null) {
            seconds = stationMap.get(hash);
        }
        if (seconds == null || seconds <= 0) {
            seconds = 180.0;
        }
        return (long) Math.ceil(seconds * 20.0);
    }

    public synchronized static boolean isKnown(int station, String hash) {
        if (hash == null || hash.isEmpty()) {
            return false;
        }
        Map<String, Double> stationMap = LENGTHS.get(station);
        return stationMap != null && stationMap.containsKey(hash);
    }

    public synchronized static boolean hasTracks(int station) {
        Map<String, Double> stationMap = LENGTHS.get(station);
        return stationMap != null && !stationMap.isEmpty();
    }

    /** C2S hello 并集：只增不减，时长取 max。 */
    public synchronized static void mergeHello(Map<Integer, Map<String, Double>> stations) {
        int added = 0;
        for (var stationEntry : stations.entrySet()) {
            Map<String, Double> map = LENGTHS.computeIfAbsent(stationEntry.getKey(), k -> new HashMap<>());
            for (var track : stationEntry.getValue().entrySet()) {
                Double prev = map.get(track.getKey());
                if (prev == null) {
                    added++;
                }
                map.merge(track.getKey(), track.getValue(), Math::max);
            }
        }
        if (added > 0) {
            DimBlendRadio.LOGGER.info("[radio] server catalog merged hello: +{} tracks", added);
        }
    }

    /** 服务端直扫（同步，首次信号触发时兜底；正常走启动时异步预热）。只算 hash 不解码。 */
    public synchronized static void ensureServerScannedSync() {
        if (!serverScanned) {
            scanServerNow();
        }
    }

    public static void scanServerAsync() {
        Util.ioPool().execute(() -> {
            try {
                ensureServerScannedSync();
            } catch (Exception e) {
                DimBlendRadio.LOGGER.warn("[radio] server scan failed", e);
            }
        });
    }

    public synchronized static void scanServerNow() {
        try {
            Path root = FMLPaths.GAMEDIR.get().resolve("dimblend_radio");
            Files.createDirectories(root);
            int total = 0;
            for (int station = 1; station <= 14; station++) {
                Path dir = root.resolve(String.valueOf(station));
                Files.createDirectories(dir);
                Map<String, Double> map = LENGTHS.computeIfAbsent(station, k -> new HashMap<>());
                try (var stream = Files.list(dir)) {
                    for (Path file : stream.filter(Files::isRegularFile).sorted().toList()) {
                        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
                        if (!(name.endsWith(".ogg") || name.endsWith(".mp3")
                                || name.endsWith(".flac") || name.endsWith(".wav"))) {
                            continue;
                        }
                        try {
                            String hash = sha256(Files.readAllBytes(file));
                            if (!map.containsKey(hash)) {
                                map.put(hash, -1.0);
                                total++;
                            }
                        } catch (Exception e) {
                            DimBlendRadio.LOGGER.warn("[radio] server skip unreadable file {}", file, e);
                        }
                    }
                }
            }
            serverScanned = true;
            int known = LENGTHS.values().stream().mapToInt(Map::size).sum();
            DimBlendRadio.LOGGER.info("[radio] server catalog: 14 stations, {} tracks (+{} new)",
                    known, total);
        } catch (Exception e) {
            DimBlendRadio.LOGGER.warn("[radio] server scan failed", e);
        }
    }

    public synchronized static String pickNext(int station, String avoidHash, RandomSource random) {
        Map<String, Double> stationMap = LENGTHS.get(station);
        if (stationMap == null || stationMap.isEmpty()) {
            return avoidHash != null ? avoidHash : "empty";
        }
        List<String> hashes = new ArrayList<>(stationMap.keySet());
        Collections.sort(hashes);
        if (hashes.size() == 1) {
            return hashes.get(0);
        }
        String pick = hashes.get(random.nextInt(hashes.size()));
        if (pick.equals(avoidHash)) {
            pick = hashes.get((hashes.indexOf(pick) + 1) % hashes.size());
        }
        return pick;
    }

    private static String sha256(byte[] bytes) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(digest.digest(bytes));
    }

    private RadioCatalog() {
    }
}
