package dimblend;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 定位模块源码树：test 任务的 workingDir 是 {@code build/}（日志配置要求），相对路径
 * {@code src/main/...} 会解析到 {@code build/src} 下而找不到文件。从 user.dir 向上找
 * 第一个含 {@code src/main} 的目录作为模块根。
 */
public final class TestSourceTree {
    private static final Path MAIN = locate();

    private static Path locate() {
        for (Path dir = Path.of("").toAbsolutePath(); dir != null; dir = dir.getParent()) {
            Path main = dir.resolve("src/main");
            if (Files.isDirectory(main)) {
                return main;
            }
        }
        throw new IllegalStateException("cannot locate src/main above " + Path.of("").toAbsolutePath());
    }

    /** src/main 下的文件，如 {@code mainFile("resources/dimblend.mixins.json")}。 */
    public static Path mainFile(String relative) {
        return MAIN.resolve(relative);
    }

    private TestSourceTree() {
    }
}
