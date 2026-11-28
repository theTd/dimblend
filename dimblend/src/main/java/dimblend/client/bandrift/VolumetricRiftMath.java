package dimblend.client.bandrift;

/**
 * Pure-Java mirror of the volumetric rift mask, shared by the two GLSL implementations
 * ({@code sodium:blocks/block_layer_opaque} after our injection, and
 * {@code dimblend:mirage_terrain}) and by the unit tests.
 *
 * <p><b>What it decides.</b> The seam at {@code slabX = k * bandSize} carries two complete,
 * symmetric universes: A = real west of the plane plus band-A mirage east of it, B = the mirror
 * image. Two questions are answered per fragment:</p>
 * <ol>
 *   <li>{@link #eval} 鈥?did the camera-to-fragment ray pass <em>through the aperture on the
 *       stationary seam plane</em> {@code X = slabX}? A fragment reached through the aperture
 *       belongs to the <em>other</em> universe and is drawn by the second pass; a fragment
 *       reached without crossing it belongs to the near universe. This is a ray/plane
 *       intersection inside a fixed {@code (Y,Z)} ellipse, <b>not</b> a point-inside-shape test:
 *       a point far behind the plane must stay "other", otherwise the far band would be clipped
 *       into a fish bowl. And because the aperture lives on the world-locked plane, walking
 *       toward the seam never drags the glowing opening forward with the camera.</li>
 *   <li>{@link #keeps} 鈥?the four exclusive pass masks:
 *       <ul>
 *         <li>{@code mode 1} real near side, {@code mode 3} near-universe mirage on the far side;</li>
 *         <li>{@code mode 2} real far side through the aperture, {@code mode 4} far-universe mirage
 *             through the aperture on the near side. {@code mode 4} is never issued any more: the
 *             aperture sits on the plane, so no near-side fragment can be reached through it.</li>
 *       </ul>
 *       Every {@code (nearSide, other)} pair is kept by exactly one mode, so the four passes
 *       partition space with no overlap and no gaps.</li>
 * </ol>
 *
 * <p>All coordinates are camera-relative block positions (the same space the Sodium vertex
 * shader passes as {@code v_WorldRel}). The math is written in {@code float} with the same
 * operation order as the GLSL so a test failure points at a real divergence.</p>
 */
public final class VolumetricRiftMath {
    public static final int MODE_OFF = 0;
    /** Sodium pass 1: the near universe's real chunks (near side, not through the aperture). */
    public static final int MODE_NEAR_REAL = 1;
    /** Sodium pass 2: the far universe's real chunks (far side, through the aperture). */
    public static final int MODE_FAR_REAL = 2;
    /** Mirage pass 1: the near universe projected onto the far side (not through the aperture). */
    public static final int MODE_NEAR_MIRAGE = 3;
    /** Mirage pass 2: the far universe projected onto the near side. Unused since 07. */
    public static final int MODE_FAR_MIRAGE = 4;

    /** Below this open factor the aperture does not exist at all (no second pass, no far sky). */
    public static final float CLOSED_OPEN = 0.002F;
    /**
     * {@code u_RiftRadii.x}. The aperture no longer has any depth: every fragment is resolved on
     * the world-locked seam plane, so the X radius only has to stay a finite positive number for
     * the uniform layout (and for any old shader that still reads {@code .x}).
     */
    public static final float MIN_RADIUS = 0.05F;
    /** Default distance over which the cut-surface glow decays inside the shell, in blocks. */
    public static final float RIM_WIDTH = 0.45F;
    /** {@code solidScale} ramps in over this range of {@code openFactor} (mirror of the GLSL). */
    public static final float SOLID_EDGE_LOW = 0.20F;
    /** Upper edge of the {@code solidScale} ramp (mirror of the GLSL). */
    public static final float SOLID_EDGE_HIGH = 0.95F;
    /** Aperture cutoff in normalised {@code (Y,Z)} units: past this the fragment is near-universe. */
    public static final float APERTURE_LIMIT = 1.15F;
    /**
     * Distance a chunk section's {@code flat} centre must sit from the seam plane before the
     * section rule is trusted over the per-fragment one. Half a section: every 16-aligned seam
     * puts a section centre 8.0 blocks off the plane, so 7.5 leaves 0.5 blocks of slack for
     * non-aligned seams before the fallback takes over.
     */
    public static final float SECTION_CENTER_MARGIN = 7.5F;
    /** Up to this distance from the track the rift aperture is locked to the railway vault. */
    public static final float SHELL_LOCK_RADIUS = 16.0F;
    /** From this distance on the rift aperture is centred on the camera itself. */
    public static final float SHELL_FREE_RADIUS = 28.0F;

    private VolumetricRiftMath() {
    }

    /** One frame's aperture: plane, camera-relative centre, radii and the world-locked noise seed. */
    public record Params(
            float normalX, float normalY, float normalZ, float planeW,
            float centerX, float centerY, float centerZ, float open,
            float radiusX, float radiusY, float radiusZ, float glitch,
            float seconds, float camX, float camY, float camZ, float rimWidth) {

        /** True when this frame has no shell at all. */
        public boolean closed() {
            return this.open <= CLOSED_OPEN;
        }
    }

    /** Mask evaluation for one fragment: {@code hit} = other universe, {@code rim} = glow weight. */
    public record Rim(boolean hit, float rim) {
        public static final Rim MISS = new Rim(false, 0.0F);
    }

    /**
     * Builds the frame's shell from world-space inputs.
     *
     * @param slabX       seam block X ({@code k * bandSize})
     * @param centerWorldY vault centre Y in world space
     * @param centerWorldZ vault centre Z in world space
     * @param open        spring opening in {@code [0,1]}
     * @param radiusY     cross-section half height (blocks), already including the bulge margin
     * @param radiusZ     cross-section half width (blocks), already including the bulge margin
     * @param glitch      random hole density in {@code [0,0.35]}
     */
    public static Params volume(
            int slabX,
            double cameraX, double cameraY, double cameraZ,
            double centerWorldY, double centerWorldZ,
            double open, double radiusY, double radiusZ, double glitch,
            double seconds, double rimWidth) {
        double direction = cameraX < slabX ? 1.0 : -1.0;
        // Absolute distance, not the signed product: the product is -0.0 when the camera stands
        // exactly on the plane, and a negative zero in a uniform is a needless special case.
        double planeDistance = Math.abs(slabX - cameraX);
        float planeW = (float) planeDistance;
        // The X radius is a constant, never derived from planeDistance or open: the aperture is
        // resolved on the world-locked plane itself, so the glowing front face stays exactly at
        // X = slabX while the player walks the last fifteen blocks toward it. Deriving it from the
        // distance (radiusX = open^2 * 14, clamped to planeDistance - 1) used to slide the whole
        // opening forward at the speed of the player.
        float radiusX = MIN_RADIUS;
        return new Params(
                (float) direction, 0.0F, 0.0F, planeW,
                (float) (slabX - cameraX), (float) (centerWorldY - cameraY), (float) (centerWorldZ - cameraZ),
                (float) open,
                radiusX,
                (float) Math.max(1.0E-3, radiusY),
                (float) Math.max(1.0E-3, radiusZ),
                (float) Math.max(0.0, glitch),
                (float) seconds,
                modulo64(cameraX), modulo64(cameraY), modulo64(cameraZ),
                (float) Math.max(0.05, rimWidth));
    }

    /** {@code world % 64} in {@code [0,64)}: keeps the pixel grid world-locked as the camera moves. */
    public static float modulo64(double value) {
        double wrapped = value % 64.0;
        if (wrapped < 0.0) {
            wrapped += 64.0;
        }
        return (float) wrapped;
    }

    /**
     * Pass mask. {@code other} is {@link Rim#hit()} for the same fragment. Exactly one mode keeps
     * each {@code (nearSide, other)} pair; {@code mode 0} keeps everything (normal drawing).
     */
    public static boolean keeps(int mode, boolean nearSide, boolean other) {
        return switch (mode) {
            case MODE_NEAR_REAL -> nearSide && !other;
            case MODE_FAR_REAL -> !nearSide && other;
            case MODE_NEAR_MIRAGE -> !nearSide && !other;
            case MODE_FAR_MIRAGE -> nearSide && other;
            default -> true;
        };
    }

    /** True when the fragment is on the camera's side of the seam plane. */
    public static boolean nearSide(Params p, float x, float y, float z) {
        return x * p.normalX() + y * p.normalY() + z * p.normalZ() < p.planeW();
    }

    /**
     * Pass-side test anchored on the fragment's chunk section instead of its own interpolated
     * position.
     *
     * <p><b>Why.</b> A real chunk section whose west face is coplanar with the seam plane has
     * every vertex on {@code dot(v_WorldRel, normal) == planeW}. Perspective-correct interpolation
     * of a perspective-divided varying is only accurate to a few ULP, so that wall flips between
     * "near" and "far" per pixel and ~half of it leaks into the wrong pass. The section centre is
     * a {@code flat} varying: constant per primitive, exact, and 8 blocks off the plane for a
     * chunk-aligned seam, so the side is read from the section whenever the section is far enough
     * from the plane to be unambiguous.</p>
     *
     * <p>Sections that straddle the plane ({@code |secDiff| < 7.5}, only possible on a seam that
     * is not 16-aligned) fall back to the per-fragment test, which is still the only thing that
     * can split geometry inside one section.</p>
     */
    public static boolean nearSideSection(Params p, float x, float y, float z,
                                          float secCenterX, float secCenterY, float secCenterZ) {
        float secDiff = secCenterX * p.normalX() + secCenterY * p.normalY() + secCenterZ * p.normalZ()
                - p.planeW();
        if (Math.abs(secDiff) >= SECTION_CENTER_MARGIN) {
            return secDiff < 0.0F;
        }
        return nearSide(p, x, y, z);
    }

    /** Off-track blend factor: 0 locked to the railway, 1 fully centred on the camera. */
    public static float shellCenterBlend(float cameraY, float cameraZ, float trackY, float trackZ) {
        float distance = (float) Math.hypot(cameraY - trackY, cameraZ - trackZ);
        return smoothstep(SHELL_LOCK_RADIUS, SHELL_FREE_RADIUS, distance);
    }

    /** Effective {@code (centerY, centerZ)} of the rift shell in world space. */
    public record ShellCenter(double centerY, double centerZ) {
    }

    /**
     * Effective aperture centre. Riding the train or walking the corridor keeps the aperture 100%
     * on the railway vault; the further the camera wanders off the track the more the aperture
     * follows it, so flying at {@code Y=92, Z=-40} still opens the rift right in front of the
     * camera instead of leaving it 40 blocks to the side. Between 16 and 28 blocks it blends
     * smoothly, so crossing never hard-snaps, and climbing a canyon ledge at 12 blocks off the
     * centre line no longer nudges the opening sideways.
     */
    public static ShellCenter shellCenter(double cameraY, double cameraZ, double trackY, double trackZ) {
        float blend = shellCenterBlend((float) cameraY, (float) cameraZ, (float) trackY, (float) trackZ);
        return new ShellCenter(trackY + (cameraY - trackY) * blend, trackZ + (cameraZ - trackZ) * blend);
    }

    /**
     * Evaluates the aperture for one camera-relative fragment position: the mirror of
     * {@code dimblendEvalRift}. The ray is intersected with the stationary seam plane and the
     * intersection point is normalised inside a fixed {@code (Y,Z)} ellipse, so the opening is
     * pinned in world space instead of being dragged along with the camera.
     */
    public static Rim eval(Params p, float x, float y, float z) {
        float open = p.open();
        if (open <= CLOSED_OPEN) {
            return Rim.MISS;
        }
        float distance = (float) Math.sqrt(x * x + y * y + z * z);
        float invDistance = 1.0F / Math.max(distance, 1.0E-4F);
        float dirX = x * invDistance;
        float dirY = y * invDistance;
        float dirZ = z * invDistance;

        float denom = dirX * p.normalX() + dirY * p.normalY() + dirZ * p.normalZ();
        if (denom <= 1.0E-4F) {
            // The ray runs parallel to the plane (or backwards out of it): no aperture crossing.
            return Rim.MISS;
        }
        float tPlane = p.planeW() / denom;
        if (tPlane < 0.0F || distance < tPlane) {
            // The plane is behind the camera, or this fragment sits in front of the opening.
            return Rim.MISS;
        }
        float hitX = dirX * tPlane;
        float hitY = dirY * tPlane;
        float hitZ = dirZ * tPlane;
        float radiusY = Math.max(p.radiusY(), 1.0E-3F);
        float radiusZ = Math.max(p.radiusZ(), 1.0E-3F);
        float deltaY = (hitY - p.centerY()) / radiusY;
        float deltaZ = (hitZ - p.centerZ()) / radiusZ;
        float radial = (float) Math.sqrt(deltaY * deltaY + deltaZ * deltaZ);
        if (radial >= APERTURE_LIMIT) {
            return Rim.MISS;
        }
        // Two-octave world-locked voxel fBM on a 1/8-block grid: the opening is a stable hole
        // punched through the world at X = slabX, so its tattered edge never swims with the camera.
        float hitVx = voxel8(hitX + p.camX());
        float hitVy = voxel8(hitY + p.camY());
        float hitVz = voxel8(hitZ + p.camZ());
        float wobble = edgeWobble(hitVx, hitVy, hitVz, p.seconds());
        float solidScale = smoothstep(SOLID_EDGE_LOW, SOLID_EDGE_HIGH, open);
        float solidEdge = solidScale * (0.74F + 0.38F * wobble);
        float glitch = clamp(p.glitch(), 0.0F, 0.35F);
        boolean hole = false;
        float border = 1.0F;
        float fringe = holeFringe(solidScale, solidEdge);
        if (radial < fringe && glitch > 0.001F) {
            float cellX = floor(hitVx * 12.0F);
            float cellY = floor(hitVy * 12.0F);
            float cellZ = floor(hitVz * 12.0F);
            float tick = floor(p.seconds() * 10.0F);
            float cellHash = hash(
                    cellX + tick * 7.13F,
                    cellY + tick * 3.71F,
                    cellZ + tick * 11.17F);
            hole = cellHash < glitch;
            if (hole) {
                // Subtle lane-tinted rim on tattered fracture cells without white QR blowout.
                border = 0.055F;
            }
        }
        float spark = hash(
                floor(hitVx * 16.0F),
                floor(hitVy * 16.0F),
                floor(hitVz * 16.0F) + floor(p.seconds() * 7.0F)) >= 0.985F ? 1.0F : 0.0F;
        boolean hit = false;
        float edgeMetric = 1.0F;
        if (radial < solidEdge) {
            hit = true;
            edgeMetric = Math.abs(radial - solidEdge);
        } else if (hole) {
            hit = true;
            edgeMetric = border;
        }
        if (!hit) {
            return Rim.MISS;
        }
        // Surf rim: a near-side wall or the real terrain that the aperture cuts through.
        // Silhouette rim: the punched hole edge right on the stationary plane.
        float surface = 1.0F - smoothstep(0.0F, Math.max(p.rimWidth(), 0.05F), distance - tPlane);
        float silhouette = 1.0F - smoothstep(0.0F, 0.09F, edgeMetric);
        float rim = Math.max(surface * 0.85F, silhouette);
        // World-locked 1/16-block pinpricks: the fracture rim twinkles instead of glowing flat.
        float rimSpark = spark * (silhouette + surface * 0.35F);
        return new Rim(true, Math.min(1.0F, rim + rimSpark * 0.35F));
    }

    /**
     * Radius (in normalised {@code (Y,Z)} aperture units) inside which fracture cells may be
     * punched. Tied to the solid edge so the crack fringe stays a thin tatter on the aperture rim:
     * the offset is {@code 0.14 * max(solidScale, 0.25)}, i.e. at most 0.049 of the radius, against
     * the old flat {@code solidEdge + 0.22} which scattered holes over half the opening the moment
     * the first crack appeared.
     */
    public static float holeFringe(float solidScale, float solidEdge) {
        return Math.min(1.0F, solidEdge + 0.14F * Math.max(solidScale, 0.25F));
    }

    /**
     * Two-octave world-locked voxel fBM of the fracture edge, the mirror of
     * {@code dimblendRiftWobble}. {@code hit} is already quantized to the 1/8-block world grid.
     */
    public static float edgeWobble(float hitX, float hitY, float hitZ, float seconds) {
        return 0.68F * noise(hitX * 0.20F, hitY * 0.20F + seconds * 0.18F, hitZ * 0.20F)
                + 0.32F * noise(hitX * 0.55F, hitY * 0.55F + seconds * 0.31F, hitZ * 0.55F);
    }

    /** Quantizes a camera-relative coordinate to the 1/8-block world grid the wobble samples. */
    public static float voxel8(float cameraRelative) {
        return floor(cameraRelative * 8.0F) * 0.125F;
    }

    /** GLSL {@code smoothstep}, also accepting descending edges. */
    public static float smoothstep(float edge0, float edge1, float value) {
        if (edge0 == edge1) {
            return value >= edge0 ? 1.0F : 0.0F;
        }
        float t = clamp((value - edge0) / (edge1 - edge0), 0.0F, 1.0F);
        return t * t * (3.0F - 2.0F * t);
    }

    public static float clamp(float value, float min, float max) {
        return value < min ? min : Math.min(value, max);
    }

    /** GLSL {@code fract}. */
    public static float fract(float value) {
        return value - (float) Math.floor(value);
    }

    /** 3D hash used by both shaders and this mirror; deterministic and allocation free. */
    public static float hash(float x, float y, float z) {
        float px = fract(x * 0.1031F);
        float py = fract(y * 0.1031F);
        float pz = fract(z * 0.1031F);
        float dot = px * (py + 33.33F) + py * (pz + 33.33F) + pz * (px + 33.33F);
        px += dot;
        py += dot;
        pz += dot;
        return fract((px + py) * pz);
    }

    /** Value noise on the 3D integer lattice, trilinearly interpolated with a smooth fade. */
    public static float noise(float x, float y, float z) {
        float cellX = (float) Math.floor(x);
        float cellY = (float) Math.floor(y);
        float cellZ = (float) Math.floor(z);
        float fx = x - cellX;
        float fy = y - cellY;
        float fz = z - cellZ;
        float ux = fx * fx * (3.0F - 2.0F * fx);
        float uy = fy * fy * (3.0F - 2.0F * fy);
        float uz = fz * fz * (3.0F - 2.0F * fz);
        float n000 = hash(cellX, cellY, cellZ);
        float n100 = hash(cellX + 1.0F, cellY, cellZ);
        float n010 = hash(cellX, cellY + 1.0F, cellZ);
        float n110 = hash(cellX + 1.0F, cellY + 1.0F, cellZ);
        float n001 = hash(cellX, cellY, cellZ + 1.0F);
        float n101 = hash(cellX + 1.0F, cellY, cellZ + 1.0F);
        float n011 = hash(cellX, cellY + 1.0F, cellZ + 1.0F);
        float n111 = hash(cellX + 1.0F, cellY + 1.0F, cellZ + 1.0F);
        float nx00 = n000 + (n100 - n000) * ux;
        float nx10 = n010 + (n110 - n010) * ux;
        float nx01 = n001 + (n101 - n001) * ux;
        float nx11 = n011 + (n111 - n011) * ux;
        float nxy0 = nx00 + (nx10 - nx00) * uy;
        float nxy1 = nx01 + (nx11 - nx01) * uy;
        return nxy0 + (nxy1 - nxy0) * uz;
    }

    private static float floor(float value) {
        return (float) Math.floor(value);
    }
}
