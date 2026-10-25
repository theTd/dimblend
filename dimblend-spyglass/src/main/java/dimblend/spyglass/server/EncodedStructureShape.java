package dimblend.spyglass.server;

import dimblend.spyglass.net.SpyglassShapePayload;
import dimblend.spyglass.structure.StructureKey;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * One encoded structure shape, already cut into wire parts and immutable.
 *
 * <p>Immutable so the shape cache can share one instance between all players and the per-player
 * streaming state only has to remember a part index. Parts are cut at
 * {@link SpyglassShapePayload#MAX_PART_BYTES}, which is sized so that one part (plus the part header)
 * is exactly one tick of the per-player streaming budget; a body is at most
 * {@link dimblend.spyglass.structure.StructureShapeCodec#MAX_BODY_BYTES}, so there are at most
 * {@link SpyglassShapePayload#MAX_PART_COUNT} parts.
 *
 * @param blockCount kept blocks, for logs and for the client cache's block budget accounting
 * @param bodyBytes  encoded body size, the shape cache's bound is measured in these bytes
 * @param parts      body slices in transmission order; all but the last are full
 */
record EncodedStructureShape(
        StructureKey key,
        int revision,
        int blockCount,
        int bodyBytes,
        List<byte[]> parts) {
    EncodedStructureShape {
        if (revision < 0) {
            throw new IllegalArgumentException("Negative shape revision: " + revision);
        }
        if (blockCount <= 0) {
            throw new IllegalArgumentException("A shape needs at least one block");
        }
        if (bodyBytes <= 0) {
            throw new IllegalArgumentException("A shape needs at least one encoded byte");
        }
        parts = List.copyOf(parts);
        if (parts.isEmpty() || parts.size() > SpyglassShapePayload.MAX_PART_COUNT) {
            throw new IllegalArgumentException("Invalid shape part count: " + parts.size());
        }
        int total = 0;
        for (byte[] part : parts) {
            if (part.length == 0 || part.length > SpyglassShapePayload.MAX_PART_BYTES) {
                throw new IllegalArgumentException("Invalid shape part size: " + part.length);
            }
            total += part.length;
        }
        if (total != bodyBytes) {
            throw new IllegalArgumentException("Shape parts hold " + total + " bytes, expected " + bodyBytes);
        }
    }

    static EncodedStructureShape split(
            StructureKey key, int revision, int blockCount, byte[] body) {
        List<byte[]> parts = new ArrayList<>();
        int offset = 0;
        while (offset < body.length) {
            int length = Math.min(SpyglassShapePayload.MAX_PART_BYTES, body.length - offset);
            parts.add(Arrays.copyOfRange(body, offset, offset + length));
            offset += length;
        }
        return new EncodedStructureShape(key, revision, blockCount, body.length, parts);
    }

    int partCount() {
        return this.parts.size();
    }

    /** Joins the parts back into the encoded body, for diagnostics and tests. */
    byte[] joinParts() {
        byte[] body = new byte[this.bodyBytes];
        int offset = 0;
        for (byte[] part : this.parts) {
            System.arraycopy(part, 0, body, offset, part.length);
            offset += part.length;
        }
        return body;
    }
}
