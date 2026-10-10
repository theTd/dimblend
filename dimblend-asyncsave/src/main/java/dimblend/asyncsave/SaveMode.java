package dimblend.asyncsave;

/**
 * 同步存档窗口：手动 /save-all、/save-all flush 与关服走原版全同步路径。
 * 由 MinecraftServerSaveMixin 在进入 saveEverything/saveAllChunks（flush 或 force 参数为真）时
 * 调用 {@link #enterSync()}（先 drain 掉队列里所有挂起的异步写，避免与紧随其后的同步写
 * 在同一批文件上交错），返回时调用 {@link #exitSync()}。支持嵌套（saveEverything 内部会
 * 再调 saveAllChunks）。
 *
 * 注意：若同步存档过程中抛出异常导致 RETURN 注入未触发，同步标记会残留在服务器线程上，
 * 之后的自动存档退化为原版同步行为——安全方向上的降级，可接受。
 */
public final class SaveMode {
    private static final ThreadLocal<Integer> SYNC_DEPTH = ThreadLocal.withInitial(() -> 0);

    public static void enterSync() {
        if (SYNC_DEPTH.get() == 0) {
            AsyncSaveQueue.drain();
        }
        SYNC_DEPTH.set(SYNC_DEPTH.get() + 1);
    }

    public static void exitSync() {
        int depth = SYNC_DEPTH.get() - 1;
        if (depth <= 0) {
            SYNC_DEPTH.remove();
        } else {
            SYNC_DEPTH.set(depth);
        }
    }

    public static boolean isSync() {
        return SYNC_DEPTH.get() > 0;
    }

    private SaveMode() {
    }
}
