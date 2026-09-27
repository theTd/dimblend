package dimblend.purge;

import com.mojang.datafixers.util.Either;
import dimblend.mixin.IOWorkerAccessor;
import dimblend.mixin.RegionFileStorageAccessor;
import it.unimi.dsi.fastutil.longs.Long2LongMap;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.IOWorker;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;

/**
 * Deletes one region file of one store (chunks, entities or POI) online, on that store's IO
 * worker mailbox.
 *
 * <p>Why the mailbox: {@code RegionFileStorage} keeps up to 256 region files open and rewrites
 * their whole in-memory header on every write, so deleting a file behind its back loses the
 * delete (Windows opens region files with share-delete, the delete "succeeds" and the cached
 * handle keeps writing). The task below runs FIFO with every load and store of that worker, so:
 * <ul>
 *   <li>a queued write for the region makes the task back off ({@link Outcome#DEFERRED}) instead
 *       of racing the BACKGROUND flush that would recreate the file with stale data;</li>
 *   <li>the cached handle is evicted and closed before the file is removed;</li>
 *   <li>any load submitted after this task (the caller submits from the server thread only after
 *       checking nothing in the region is loaded) sees no file and regenerates.</li>
 * </ul>
 */
public final class RegionFileDeleter {

    public enum Outcome {
        /** File (and oversized chunk files) removed, or nothing was there. */
        DELETED,
        /** A write for this region is still queued; retry on a later scan. */
        DEFERRED
    }

    public record Result(Outcome outcome, long bytes, int files) {
        static Result deferred() {
            return new Result(Outcome.DEFERRED, 0L, 0);
        }
    }

    private RegionFileDeleter() {
    }

    /** Folder of the worker's region files; the field is final, so any thread may read it. */
    public static Path folderOf(IOWorker worker) {
        RegionFileStorage storage = ((IOWorkerAccessor) worker).dimblend$getStorage();
        return ((RegionFileStorageAccessor) (Object) storage).dimblend$getFolder();
    }

    /**
     * Records every {@code r.X.Z.mca} in {@code folder} into {@code largestFile} as region key to
     * the largest size seen for that region across the folders listed so far.
     */
    public static void listRegions(Path folder, Long2LongMap largestFile) throws IOException {
        if (!Files.isDirectory(folder)) {
            return;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(folder, "r.*.mca")) {
            for (Path path : stream) {
                OptionalLong key = RegionPurgePlanner.parseRegionFileName(path.getFileName().toString());
                if (key.isPresent()) {
                    long size = sizeIfExists(path);
                    largestFile.mergeLong(key.getAsLong(), size, Math::max);
                }
            }
        }
    }

    public static CompletableFuture<Result> delete(IOWorker worker, int regionX, int regionZ) {
        IOWorkerAccessor access = (IOWorkerAccessor) worker;
        return access.dimblend$submitTask(() -> {
            for (ChunkPos pending : access.dimblend$getPendingWrites().keySet()) {
                if (pending.getRegionX() == regionX && pending.getRegionZ() == regionZ) {
                    return Either.left(Result.deferred());
                }
            }
            RegionFileStorageAccessor storage = (RegionFileStorageAccessor) (Object) access.dimblend$getStorage();
            try {
                RegionFile open = storage.dimblend$getRegionCache().remove(ChunkPos.asLong(regionX, regionZ));
                if (open != null) {
                    open.close();
                }
                return Either.left(deleteFiles(storage.dimblend$getFolder(), regionX, regionZ));
            } catch (IOException | RuntimeException e) {
                return Either.right(e);
            }
        });
    }

    private static Result deleteFiles(Path folder, int regionX, int regionZ) throws IOException {
        long bytes = 0L;
        int files = 0;
        Path mca = folder.resolve("r." + regionX + "." + regionZ + ".mca");
        long size = sizeIfExists(mca);
        if (Files.deleteIfExists(mca)) {
            bytes += size;
            files++;
        }
        // Chunks over 1 MiB live beside the region as c.X.Z.mcc; rare, so one listing beats 1024 probes.
        if (Files.isDirectory(folder)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(folder, "c.*.mcc")) {
                for (Path path : stream) {
                    if (!RegionPurgePlanner.isOversizedChunkOf(path.getFileName().toString(), regionX, regionZ)) {
                        continue;
                    }
                    long external = sizeIfExists(path);
                    if (Files.deleteIfExists(path)) {
                        bytes += external;
                        files++;
                    }
                }
            }
        }
        return new Result(Outcome.DELETED, bytes, files);
    }

    private static long sizeIfExists(Path path) throws IOException {
        try {
            return Files.size(path);
        } catch (NoSuchFileException e) {
            return 0L;
        }
    }
}
