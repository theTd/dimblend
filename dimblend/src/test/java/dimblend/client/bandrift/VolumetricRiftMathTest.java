package dimblend.client.bandrift;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Mirror tests for the volumetric rift mask: the same arithmetic runs in two GLSL shaders, so the
 * pure-Java copy is the only place the invariants from the design can be pinned.
 *
 * <p>The first block pins the 07 fix: the aperture is a <b>stationary</b> hole on the seam plane
 * {@code X = slabX}. Walking toward the seam must not drag the glowing front face along with the
 * camera, and a wall in front of the seam must never be classified as the other universe.</p>
 */
class VolumetricRiftMathTest {
    private static final int SEAM = 4096;
    private static final double CAMERA_Y = 64.0;
    private static final double CAMERA_Z = 0.0;
    private static final double VAULT_Y = 64.0;
    private static final double VAULT_Z = 0.0;

    private static VolumetricRiftMath.Params aperture(double cameraX, double open) {
        return VolumetricRiftMath.volume(
                SEAM, cameraX, CAMERA_Y, CAMERA_Z,
                VAULT_Y, VAULT_Z,
                open, 60.0, 60.0, 0.0, 0.0, 0.45);
    }

    // ---- stationary aperture ------------------------------------------------------------------

    @Test
    void closedApertureNeverMasks() {
        VolumetricRiftMath.Params params = aperture(4000.0, 0.0);
        assertTrue(params.closed());
        VolumetricRiftMath.Rim rim = VolumetricRiftMath.eval(params, 500.0F, 0.0F, 0.0F);
        assertFalse(rim.hit(), "no opening means no other universe, however far the fragment is");
    }

    @Test
    void nearSideIsCameraSideOfThePlane() {
        VolumetricRiftMath.Params params = aperture(4000.0, 1.0);
        assertEquals(1.0F, params.normalX(), 1.0E-6F, "camera west of the seam looks east");
        assertEquals(96.0F, params.planeW(), 1.0E-4F);
        assertTrue(VolumetricRiftMath.nearSide(params, 30.0F, 0.0F, 0.0F));
        assertFalse(VolumetricRiftMath.nearSide(params, 130.0F, 0.0F, 0.0F));
    }

    /**
     * The 07 report: at close range the old {@code radiusX = min(open^2 * 14, planeDistance - 1)}
     * pulled the front face of the shell forward with the player, so the glowing opening slid
     * along at walking speed instead of staying pinned to {@code X = slabX}. Every fragment in
     * front of the plane must now be near universe for every open value.
     */
    @Test
    void theApertureNeverSlidesForwardAsItOpens() {
        for (double cameraX : new double[]{4000.0, 4090.0, 4095.0, 4095.9}) {
            float planeW = aperture(cameraX, 1.0).planeW();
            for (double open = 0.25; open <= 1.0001; open += 0.05) {
                VolumetricRiftMath.Params params = aperture(cameraX, open);
                assertEquals(planeW, params.planeW(), 1.0E-4F,
                        "planeW is the seam distance, independent of open");
                assertTrue(VolumetricRiftMath.eval(params, planeW, 0.0F, 0.0F).hit(),
                        "on the plane the opening is open, cameraX=" + cameraX + " open=" + open);
                assertFalse(VolumetricRiftMath.eval(params, planeW - 0.5F, 0.0F, 0.0F).hit(),
                        "half a block in front of the plane stays near, cameraX=" + cameraX
                                + " open=" + open);
            }
        }
    }

    /**
     * The other half of the 07 report: with a 14-block shell the front face sat up to 14 blocks
     * inside the seam, so a wall standing in front of the seam was "inside the shell" and got
     * painted as the far band. A ray/plane crossing cannot do that: a fragment in front of the
     * plane is never through the opening, however far behind it the fragment points.
     */
    @Test
    void aWallInFrontOfTheSeamStaysInTheNearUniverse() {
        VolumetricRiftMath.Params params = aperture(4000.0, 1.0);
        assertEquals(96.0F, params.planeW(), 1.0E-4F);
        for (float inFront : new float[]{-60.0F, -30.0F, -6.0F, -0.05F}) {
            assertFalse(VolumetricRiftMath.eval(params, 96.0F + inFront, 0.0F, 0.0F).hit(),
                    "a fragment " + (-inFront) + " blocks in front of the seam is near universe");
            assertFalse(VolumetricRiftMath.eval(params, 96.0F + inFront, 4.0F, 3.0F).hit(),
                    "off-axis too: " + (-inFront) + " blocks in front is near universe");
        }
    }

    @Test
    void farBandPastTheSeamStaysInTheFarUniverse() {
        VolumetricRiftMath.Params params = aperture(4000.0, 1.0);
        assertTrue(VolumetricRiftMath.eval(params, 500.0F, 0.0F, 0.0F).hit(),
                "a fragment far past the plane still belongs to the other universe: the mask is a "
                        + "ray/plane crossing, not a fish bowl around the plane");
        assertTrue(VolumetricRiftMath.eval(params, 5000.0F, 0.0F, 0.0F).hit(),
                "no distance truncation at full open");
    }

    @Test
    void lateralOffsetOutsideTheApertureRadiiStaysNear() {
        VolumetricRiftMath.Params params = aperture(4000.0, 1.0);
        // Sample points that sit ON the plane: radiusY = radiusZ = 60, so a 90-block offset
        // normalises past the 1.15 aperture limit and a 30-block offset lands at 0.5.
        assertFalse(VolumetricRiftMath.eval(params, 96.0F, 90.0F, 0.0F).hit());
        assertFalse(VolumetricRiftMath.eval(params, 96.0F, 0.0F, -90.0F).hit());
        assertTrue(VolumetricRiftMath.eval(params, 96.0F, 30.0F, 0.0F).hit());
        assertTrue(VolumetricRiftMath.eval(params, 96.0F, 0.0F, -30.0F).hit());
        // Diagonal: (30, 30) normalises to 0.707, still inside the solid disc at full open.
        assertTrue(VolumetricRiftMath.eval(params, 96.0F, 30.0F, 30.0F).hit());
        // (60, 60) normalises to 1.41, past the aperture limit.
        assertFalse(VolumetricRiftMath.eval(params, 96.0F, 60.0F, 60.0F).hit());
    }

    @Test
    void apertureSegmentationIsRecoveredAtHalfOpenPrefetch() {
        // The 0.45 preview at 38 blocks out must already punch a smaller disc through the plane,
        // not stay fully closed: the player sees the hole growing, never a popping wall.
        VolumetricRiftMath.Params preview = aperture(4058.0, 0.45);
        int hits = 0;
        for (float y = -60.0F; y <= 60.0F; y += 4.0F) {
            if (VolumetricRiftMath.eval(preview, 38.0F, y, 0.0F).hit()) {
                hits++;
            }
        }
        assertTrue(hits > 0 && hits < 31, "preview disc is partially open, was " + hits + "/31");
    }

    @Test
    void radiusXIsAConstantNotARecedingDepth() {
        assertEquals(VolumetricRiftMath.MIN_RADIUS, aperture(4000.0, 1.0).radiusX(), 1.0E-6F);
        assertEquals(VolumetricRiftMath.MIN_RADIUS, aperture(4000.0, 0.5).radiusX(), 1.0E-6F);
        assertEquals(VolumetricRiftMath.MIN_RADIUS, aperture(SEAM - 0.5, 1.0).radiusX(), 1.0E-6F);
        assertEquals(VolumetricRiftMath.MIN_RADIUS, aperture(SEAM, 1.0).radiusX(), 1.0E-6F);
    }

    @Test
    void planeDistanceIsExactOnTheSeam() {
        VolumetricRiftMath.Params onPlane = aperture(SEAM, 1.0);
        assertEquals(0.0F, onPlane.planeW(), 1.0E-6F, "camera exactly on the seam plane");
        assertEquals(0.01F, aperture(SEAM - 0.01, 1.0).planeW(), 1.0E-5F);
        assertEquals(0.01F, aperture(SEAM + 0.01, 1.0).planeW(), 1.0E-5F);
    }

    /**
     * The reported screenshot: 45 blocks west of {@code X = 4096}, looking obliquely east-northeast
     * at Band B's stone. Band B's chunks start at chunkX 256, so their WEST faces lie dead on the
     * seam plane and interpolation jitter decided their side per pixel. The flat section centre
     * must win for every one of those pixels.
     */
    @Test
    void coplanarChunkWallNeverFlipsThroughInterpolationJitter() {
        VolumetricRiftMath.Params params = aperture(4051.16, 1.0);
        float planeW = params.planeW();
        assertEquals(44.84F, planeW, 1.0E-3F);

        // East-hand chunk (Band B): world X 4096 -> section centre 8 blocks past the plane.
        float eastCenter = planeW + 8.0F;
        assertTrue(VolumetricRiftMath.nearSide(params, planeW - 1.0E-5F, 0.0F, 0.0F),
                "precondition: negative ULP jitter makes the raw interpolated x read as near side");
        for (float jitter : new float[]{-1.0E-5F, 0.0F, 1.0E-5F}) {
            assertFalse(VolumetricRiftMath.nearSideSection(params,
                            planeW + jitter, 0.0F, 0.0F, eastCenter, 0.0F, 0.0F),
                    "a wall 8 blocks past the plane must be far side at jitter " + jitter);
        }

        // West-hand chunk (Band A): section centre 8 blocks before the plane must stay near even
        // when the interpolated x lands on the far side.
        float westCenter = planeW - 8.0F;
        assertFalse(VolumetricRiftMath.nearSide(params, planeW + 1.0E-5F, 0.0F, 0.0F));
        assertTrue(VolumetricRiftMath.nearSideSection(params,
                planeW + 1.0E-5F, 0.0F, 0.0F, westCenter, 0.0F, 0.0F));
    }

    @Test
    void sectionsStraddlingThePlaneFallBackToThePerFragmentTest() {
        VolumetricRiftMath.Params params = aperture(4000.0, 1.0);
        float planeW = params.planeW();
        // A seam that is not 16-aligned puts the section centre 1 block off the plane: too close
        // to trust, so the per-fragment value decides.
        float nearCenter = planeW + 1.0F;
        assertTrue(VolumetricRiftMath.nearSideSection(params, planeW - 3.0F, 0.0F, 0.0F, nearCenter, 0.0F, 0.0F));
        assertFalse(VolumetricRiftMath.nearSideSection(params, planeW + 3.0F, 0.0F, 0.0F, nearCenter, 0.0F, 0.0F));

        float belowMargin = VolumetricRiftMath.SECTION_CENTER_MARGIN - 0.1F;
        float atMargin = VolumetricRiftMath.SECTION_CENTER_MARGIN;
        assertFalse(VolumetricRiftMath.nearSideSection(params, planeW + 5.0F, 0.0F, 0.0F,
                        planeW - belowMargin, 0.0F, 0.0F),
                "just inside the margin still trusts the fragment, which says far here");
        assertTrue(VolumetricRiftMath.nearSideSection(params, planeW + 5.0F, 0.0F, 0.0F,
                        planeW - atMargin, 0.0F, 0.0F),
                "at the margin the section rule takes over and says near");
    }

    // ---- rim -----------------------------------------------------------------------------------

    @Test
    void rimPeaksAtThePunchedEdgeAndDecaysWithDepth() {
        VolumetricRiftMath.Params params = aperture(4000.0, 1.0);
        // Straight ahead on the plane: full surf rim and no silhouette term in the middle.
        VolumetricRiftMath.Rim centre = VolumetricRiftMath.eval(params, 96.0F, 0.0F, 0.0F);
        assertTrue(centre.hit());
        assertTrue(centre.rim() >= 0.8F, "the plane itself is the brightest place, was " + centre.rim());

        // A fragment far past the plane: the surface rim has faded out by then.
        float farGlow = VolumetricRiftMath.eval(params, 500.0F, 0.0F, 0.0F).rim();
        assertTrue(farGlow < 0.2F, "rim decays on the far band surface, was " + farGlow);
    }

    @Test
    void halfOpenApertureHasHoles() {
        int glitchless = countHits(0.5, 0.0);
        int holey = countHits(0.5, 0.35);
        int total = sampleCount();
        assertTrue(glitchless > 0 && glitchless < total,
                "half open must be neither fully solid nor fully open, was " + glitchless + "/" + total);
        assertTrue(holey > glitchless,
                "glitch holes punch extra other-universe pixels through the shell ("
                        + glitchless + " -> " + holey + ")");
        assertTrue(holey < total, "even with maximum glitch the shell is not transparent");
    }

    private static int sampleCount() {
        return 41;
    }

    private static int countHits(double open, double glitch) {
        VolumetricRiftMath.Params params = VolumetricRiftMath.volume(
                SEAM, 4000.0, CAMERA_Y, CAMERA_Z,
                VAULT_Y, VAULT_Z,
                open, 60.0, 60.0, glitch, 0.0, 0.45);
        int hits = 0;
        for (int y = -40; y <= 40; y += 2) {
            // Aim just past the seam plane so crossing it is guaranteed for the solid disc.
            if (VolumetricRiftMath.eval(params, 100.0F, y, 0.0F).hit()) {
                hits++;
            }
        }
        return hits;
    }

    @Test
    void fourPassMasksPartitionSpaceExactly() {
        for (boolean nearSide : new boolean[]{true, false}) {
            for (boolean other : new boolean[]{true, false}) {
                int kept = 0;
                for (int mode = VolumetricRiftMath.MODE_NEAR_REAL; mode <= VolumetricRiftMath.MODE_FAR_MIRAGE; mode++) {
                    if (VolumetricRiftMath.keeps(mode, nearSide, other)) {
                        kept++;
                    }
                }
                assertEquals(1, kept,
                        "nearSide=" + nearSide + " other=" + other + " must be kept by exactly one pass");
            }
        }
        assertTrue(VolumetricRiftMath.keeps(VolumetricRiftMath.MODE_OFF, true, true));
        assertTrue(VolumetricRiftMath.keeps(VolumetricRiftMath.MODE_OFF, false, false));
        assertTrue(VolumetricRiftMath.keeps(99, false, true), "unknown modes degrade to normal drawing");
    }

    @Test
    void seamCrossingIsSymmetricAt360Degrees() {
        VolumetricRiftMath.Params west = aperture(SEAM - 0.01, 1.0);
        VolumetricRiftMath.Params east = aperture(SEAM + 0.01, 1.0);
        assertTrue(west.planeW() >= 0.0F && east.planeW() >= 0.0F, "planeW is always in front");
        assertEquals(west.planeW(), east.planeW(), 1.0E-5F);
        assertEquals(-1.0F, east.normalX(), 1.0E-6F);
        assertEquals(west.radiusX(), east.radiusX(), 1.0E-6F);

        // A point five blocks east of the camera is the far side on both sides of the crossing,
        // and the mirrored point must produce the mirrored mask value.
        float x = 5.0F;
        assertEquals(VolumetricRiftMath.nearSide(west, x, 0.0F, 0.0F),
                VolumetricRiftMath.nearSide(east, -x, 0.0F, 0.0F));
        assertEquals(VolumetricRiftMath.eval(west, x, 3.0F, 0.0F).hit(),
                VolumetricRiftMath.eval(east, -x, 3.0F, 0.0F).hit());
    }

    @Test
    void modulo64KeepsTheNoiseWorldLocked() {
        assertEquals(0.0F, VolumetricRiftMath.modulo64(0.0), 1.0E-6F);
        assertEquals(0.0F, VolumetricRiftMath.modulo64(4096.0), 1.0E-4F);
        assertEquals(1.0F, VolumetricRiftMath.modulo64(-63.0), 1.0E-4F);
        assertEquals(VolumetricRiftMath.modulo64(31.5), VolumetricRiftMath.modulo64(31.5 + 64.0), 1.0E-4F);
    }

    @Test
    void fractureEdgeIsAWorldLockedVoxelStaircase() {
        assertEquals(1.0F, VolumetricRiftMath.voxel8(1.03F), 1.0E-6F, "floor to the 1/8 grid");
        assertEquals(1.125F, VolumetricRiftMath.voxel8(1.13F), 1.0E-6F);
        assertEquals(VolumetricRiftMath.voxel8(-2.03F), -2.125F, 1.0E-6F, "negative cells floor away");

        float min = Float.MAX_VALUE;
        float max = -Float.MAX_VALUE;
        for (int i = 0; i < 64; i++) {
            for (int j = 0; j < 64; j++) {
                float wobble = VolumetricRiftMath.edgeWobble(i * 0.125F, j * 0.125F, 3.0F, 7.0F);
                min = Math.min(min, wobble);
                max = Math.max(max, wobble);
            }
        }
        assertTrue(min >= 0.0F && max <= 1.0F, "the fBM stays in [0,1], was " + min + ".." + max);
        assertTrue(max - min > 0.45F,
                "two octaves must span the fracture band across a few blocks, was " + (max - min));
        assertEquals(VolumetricRiftMath.edgeWobble(1.0F, 2.0F, 3.0F, 4.0F),
                VolumetricRiftMath.edgeWobble(1.0F, 2.0F, 3.0F, 4.0F), "deterministic");
        assertFalse(VolumetricRiftMath.edgeWobble(1.0F, 2.0F, 3.0F, 4.0F)
                        == VolumetricRiftMath.edgeWobble(1.0F, 2.0F, 3.0F, 4.0F + 2.5F),
                "the slow time offset must breathe the edge");
    }

    @Test
    void apertureEdgeIsOrganicNotACircle() {
        // radiusY == radiusZ == 60 and a constant normalised radial on the plane: every miss/hit
        // flip around that ring can only come from the world-locked edge wobble.
        VolumetricRiftMath.Params params = VolumetricRiftMath.volume(
                SEAM, 4000.0, CAMERA_Y, CAMERA_Z, VAULT_Y, VAULT_Z,
                0.7, 60.0, 60.0, 0.0, 0.0, 0.45);
        float solidScale = VolumetricRiftMath.smoothstep(
                VolumetricRiftMath.SOLID_EDGE_LOW, VolumetricRiftMath.SOLID_EDGE_HIGH, 0.7F);
        // 0.69 sits inside the solidEdge band 0.74*solidScale .. 1.12*solidScale.
        float radial = 0.69F;
        float radiusY = 60.0F;
        int total = 120;
        int hit = 0;
        int runs = 0;
        boolean first = false;
        boolean previous = false;
        for (int i = 0; i < total; i++) {
            double phi = Math.PI * 2.0 * i / total;
            float y = (float) (Math.cos(phi) * radial * radiusY);
            float z = (float) (Math.sin(phi) * radial * radiusY);
            boolean current = VolumetricRiftMath.eval(params, 96.0F, y, z).hit();
            if (i == 0) {
                first = current;
            }
            if (current != previous && i > 0) {
                runs++;
            }
            previous = current;
            if (current) {
                hit++;
            }
        }
        if (first != previous) {
            runs++;
        }
        assertTrue(hit > 0 && hit < total, "the ring straddles the fracture band, was " + hit + "/" + total);
        assertTrue(runs >= 4,
                "a circular fracture would flip at most twice around the ring, got " + runs
                        + " (" + hit + "/" + total + " hits, solidScale " + solidScale + ")");
    }

    // ---- off-track aperture centering ----------------------------------------------------------

    /** The railway vault centre the aperture is locked to. */
    private static final double TRACK_CENTER_Y = 72.5;
    private static final double TRACK_CENTER_Z = 0.5;

    @Test
    void apertureStaysOnTheRailwayWhileRidingOrWalkingIt() {
        VolumetricRiftMath.ShellCenter onTrack = VolumetricRiftMath.shellCenter(
                TRACK_CENTER_Y + 2.0, TRACK_CENTER_Z - 3.0, TRACK_CENTER_Y, TRACK_CENTER_Z);
        assertEquals(TRACK_CENTER_Y, onTrack.centerY(), 1.0E-9, "d <= 16 keeps the vault centre");
        assertEquals(TRACK_CENTER_Z, onTrack.centerZ(), 1.0E-9);

        VolumetricRiftMath.ShellCenter onEdge = VolumetricRiftMath.shellCenter(
                TRACK_CENTER_Y + 16.0, TRACK_CENTER_Z, TRACK_CENTER_Y, TRACK_CENTER_Z);
        assertEquals(TRACK_CENTER_Y, onEdge.centerY(), 1.0E-6, "exactly 16 blocks is still locked");
        assertEquals(0.0F, VolumetricRiftMath.shellCenterBlend(16.0F, 0.0F, 0.0F, 0.0F), 1.0E-6F);
        assertEquals(1.0F, VolumetricRiftMath.shellCenterBlend(28.0F, 0.0F, 0.0F, 0.0F), 1.0E-6F);
    }

    @Test
    void apertureCentresOnTheFlyingPlayerInsteadOfStayingFortyBlocksAside() {
        // The reported pose: Y = 92.74, Z = -40.16. d = 45.4, far past the free radius.
        VolumetricRiftMath.ShellCenter center = VolumetricRiftMath.shellCenter(
                92.74, -40.16, TRACK_CENTER_Y, TRACK_CENTER_Z);
        assertEquals(92.74, center.centerY(), 1.0E-6, "the aperture must open in front of the camera");
        assertEquals(-40.16, center.centerZ(), 1.0E-6);
        assertEquals(1.0F, VolumetricRiftMath.shellCenterBlend(92.74F, -40.16F,
                (float) TRACK_CENTER_Y, (float) TRACK_CENTER_Z), 1.0E-6F);

        // A purely lateral wanders far enough to take the centre with it as well.
        VolumetricRiftMath.ShellCenter lateral = VolumetricRiftMath.shellCenter(
                TRACK_CENTER_Y, 40.0, TRACK_CENTER_Y, TRACK_CENTER_Z);
        assertEquals(40.0, lateral.centerZ(), 1.0E-6);
    }

    /** A 12-block ledge climb off the centre line must not nudge a 100%-open gate sideways. */
    @Test
    void apertureDoesNotDriftAtTwelveBlocksOffTheTrack() {
        VolumetricRiftMath.ShellCenter ledge = VolumetricRiftMath.shellCenter(
                TRACK_CENTER_Y + 12.0, TRACK_CENTER_Z + 4.0, TRACK_CENTER_Y, TRACK_CENTER_Z);
        assertEquals(TRACK_CENTER_Y, ledge.centerY(), 1.0E-9);
        assertEquals(TRACK_CENTER_Z, ledge.centerZ(), 1.0E-9);
    }

    @Test
    void apertureBlendBetweenSixteenAndTwentyEightBlocksIsSmoothAndMonotonic() {
        float previous = -1.0F;
        for (float d = 0.0F; d <= 30.0F; d += 0.25F) {
            float blend = VolumetricRiftMath.shellCenterBlend(0.0F, d, 0.0F, 0.0F);
            assertTrue(blend >= 0.0F && blend <= 1.0F, "blend stays in [0,1] at d=" + d);
            assertTrue(blend >= previous - 1.0E-6F, "blend must not regress at d=" + d);
            previous = blend;
        }
        // Mid-blend: 22 blocks out is exactly half way, so the centre is half way too.
        float half = VolumetricRiftMath.shellCenterBlend(0.0F, 22.0F, 0.0F, 0.0F);
        assertEquals(0.5F, half, 1.0E-6F);
        VolumetricRiftMath.ShellCenter center = VolumetricRiftMath.shellCenter(0.0, 22.0, 0.0, 0.0);
        assertEquals(11.0, center.centerZ(), 1.0E-6, "no hard snap anywhere in (16,28)");
        assertEquals(0.0, center.centerY(), 1.0E-9, "on-track Y is not dragged off by a lateral wander");
    }

    // ---- initial crack fringe ------------------------------------------------------------------

    @Test
    void initialCrackFringeIsTightAt045Open() {
        float open = 0.45F;
        float solidScale = VolumetricRiftMath.smoothstep(
                VolumetricRiftMath.SOLID_EDGE_LOW, VolumetricRiftMath.SOLID_EDGE_HIGH, open);
        // The floor of the fringe offset is 0.14 * 0.25 = 0.035 normalised radii, against the old
        // flat +0.22 that scattered holes across half the bubble on the first crack.
        assertEquals(0.0363F, VolumetricRiftMath.holeFringe(solidScale, 0.0F), 1.0E-3F);
        for (float wobble = 0.0F; wobble <= 1.0F; wobble += 0.1F) {
            float solidEdge = solidScale * (0.74F + 0.38F * wobble);
            float fringe = VolumetricRiftMath.holeFringe(solidScale, solidEdge);
            assertEquals(solidEdge + 0.0363F, fringe, 1.0E-3F, "fringe hugs the solid edge");
            assertTrue(fringe - solidEdge <= 0.05F, "holes reach only a hair past the edge: " + fringe);
            assertTrue(fringe > solidEdge, "holes still reach slightly past the solid edge");
        }
        // At full open the extra reach keeps its old meaning: a thin fringe, not a new bubble.
        assertEquals(1.0F, VolumetricRiftMath.holeFringe(1.0F, 0.95F), 1.0E-6F);
        assertEquals(0.44F, VolumetricRiftMath.holeFringe(1.0F, 0.30F), 1.0E-6F);
    }
}
