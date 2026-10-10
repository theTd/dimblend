package dimblend.asyncsave;

import com.mojang.logging.LogUtils;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;

/**
 * 自动存档后台写线程：主线程序列化出 NBT 快照后，把「压缩 + 写临时文件 + safeReplaceFile」
 * 投递到这里。单线程 FIFO 保证同一文件（按目标路径合并）的写顺序；同一文件在队列里
 * 只保留最新一份快照（被取代的写直接跳过——反正下一次自动存档会再写）。
 *
 * 屏障语义见 {@link #drain()}：手动 /save-all、/save-all flush、关服与玩家数据读取前
 * 由 mixin 触发，保证「同步存档返回 = 数据已落盘」的原版语义不被破坏。
 */
public final class AsyncSaveQueue {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String SERVER_THREAD_NAME = "Server thread";

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "dimblend-asyncsave-io");
        thread.setDaemon(true);
        return thread;
    });
    private static final ConcurrentMap<Path, PendingWrite> PENDING = new ConcurrentHashMap<>();

    @FunctionalInterface
    public interface IoWrite {
        void run() throws Exception;
    }

    /**
     * 是否把当前调用点上的文件写挪到后台：只在服务器线程上、且不在同步存档窗口内才异步。
     * 世界创建界面等其它线程触发的写保持原版同步行为。
     */
    public static boolean shouldRunAsync() {
        return Thread.currentThread().getName().equals(SERVER_THREAD_NAME) && !SaveMode.isSync();
    }

    public static void submit(Path target, IoWrite write) {
        PendingWrite task = new PendingWrite(target, write);
        PendingWrite previous = PENDING.put(target, task);
        if (previous != null) {
            previous.superseded = true;
        }
        EXECUTOR.execute(task);
    }

    /** 阻塞当前线程直到已投递的写全部完成。 */
    public static void drain() {
        if (isWriterThread()) {
            return;
        }
        CountDownLatch barrier = new CountDownLatch(1);
        EXECUTOR.execute(barrier::countDown);
        try {
            barrier.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static boolean isWriterThread() {
        return Thread.currentThread().getName().equals("dimblend-asyncsave-io");
    }

    private AsyncSaveQueue() {
    }

    private static final class PendingWrite implements Runnable {
        private final Path target;
        private final IoWrite write;
        private volatile boolean superseded;

        private PendingWrite(Path target, IoWrite write) {
            this.target = target;
            this.write = write;
        }

        @Override
        public void run() {
            PENDING.remove(this.target, this);
            if (this.superseded) {
                return;
            }
            try {
                this.write.run();
            } catch (Throwable t) {
                // 与原版一致：存档失败只记日志，不炸服务器；下一次自动存档会重试。
                LOGGER.error("Async save write failed for {}", this.target, t);
            }
        }
    }
}
