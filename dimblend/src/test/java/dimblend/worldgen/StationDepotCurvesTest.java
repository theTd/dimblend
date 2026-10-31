package dimblend.worldgen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dimblend.api.StationDepotCurves;
import java.util.List;
import org.junit.jupiter.api.Test;

/** R6 merge curves: constants and geometry the server-thread builder depends on. */
class StationDepotCurvesTest {
    private static final int B = 2048;

    @Test
    void bothCurvesStartAtTheSidingsEastEndAndLandOnTheMainLine() {
        var positive = StationDepotCurves.positive(B);
        assertEquals(B + StationDepotCurves.CURVE_START_DX, positive.fromX());
        assertEquals(StationDepotCurves.SIDING_DZ, positive.fromZ(), "positive siding");
        assertEquals(B + StationDepotCurves.POSITIVE_MERGE_DX, positive.toX());
        assertEquals(0, positive.toZ(), "main line");

        var negative = StationDepotCurves.negative(B);
        assertEquals(B + StationDepotCurves.CURVE_START_DX, negative.fromX());
        assertEquals(-StationDepotCurves.SIDING_DZ, negative.fromZ(), "negative siding");
        assertEquals(B + StationDepotCurves.NEGATIVE_MERGE_DX, negative.toX());
        assertEquals(0, negative.toZ(), "main line");
    }

    @Test
    void bothCurvesStartFromTheSameSidingEndColumn() {
        assertEquals(StationDepotGeometry.SIDING_EAST_DX, StationDepotCurves.CURVE_START_DX,
                "the curve must begin where the worldgen straight run stops");
        for (var curve : StationDepotCurves.all(B)) {
            assertEquals(B + StationDepotGeometry.SIDING_EAST_DX, curve.fromX(),
                    "no gap and no overlap between straight run and curve");
        }
    }

    @Test
    void theTwoCurvesNeverShareAnEndpointBlock() {
        var positive = StationDepotCurves.positive(B);
        var negative = StationDepotCurves.negative(B);
        assertNotEquals(positive.toX(), negative.toX(),
                "a shared landing block would need a cross-shape junction");
        assertNotEquals(positive.fromX() + ":" + positive.fromZ(),
                negative.fromX() + ":" + negative.fromZ(),
                "different sidings, so different start blocks anyway");
    }

    @Test
    void bothTangentsAreUnitAndHorizontal() {
        for (double[] tangent : List.of(StationDepotCurves.fromTangent(), StationDepotCurves.toTangent())) {
            assertEquals(1.0D, Math.sqrt(tangent[0] * tangent[0] + tangent[2] * tangent[2]), 1.0E-9,
                    "unit vector in the horizontal plane");
            assertEquals(0.0D, tangent[1], 0.0D, "flat track: no vertical component");
        }
        assertEquals(1.0D, StationDepotCurves.fromTangent()[0], "siding end points east into the curve");
        assertEquals(-1.0D, StationDepotCurves.toTangent()[0], "main-line end points west into the curve");
    }

    @Test
    void theCurveStartsHalfABlockAlongTheTangentFromTheBlockCentre() {
        // TrackBlock#getCurveStart: block centre + 0.5 along the tangent, at rail level.
        double[] start = StationDepotCurves.curveStart(
                new double[] {B + 0.5D, OakTrackCorridor.TRACK_Y + 1.0D, 0.5D},
                StationDepotCurves.fromTangent());
        assertEquals(B + 1.0D, start[0], 1.0E-9);
        assertEquals(OakTrackCorridor.TRACK_Y + 1.0D, start[1], 1.0E-9);
        assertEquals(0.5D, start[2], 1.0E-9);
    }

    @Test
    void everyCurveEndSitsInsideTheCarvedHall() {
        for (var curve : StationDepotCurves.all(B)) {
            assertTrue(StationDepotCurves.insideCarvedHall(B, curve), "outside the hall: " + curve);
            assertTrue(StationDepotGeometry.carves(B, curve.fromX(), 0, curve.fromZ()), "start inside: " + curve);
            assertTrue(StationDepotGeometry.carves(B, curve.toX(), 0, curve.toZ()), "end inside: " + curve);
        }
    }

    @Test
    void noCurveEndpointBlocksABlueprintCellOfEitherChassis() {
        // ChassisSite.clear ignores air, the controller, the build's own expected cells,
        // and TrackBlock — so a curve endpoint inside a footprint box is harmless. What
        // would break assembly is a NON-track block appearing in a cell the blueprint
        // needs, which is why the depot's furniture checks are the ones that matter.
        // This test pins that every curve endpoint is in fact a track position.
        for (var curve : StationDepotCurves.all(B)) {
            assertTrue(StationDepotGeometry.placedCells(B).stream().anyMatch(cell ->
                            cell.x() == curve.fromX() && cell.z() == curve.fromZ()
                                    && cell.kind() == StationDepotGeometry.Kind.TRACK_XO),
                    "curve start is a track block: " + curve);
        }
        // The main-line landing blocks are track too, written by the depot or already
        // present from OakTrackCorridor.
        for (var curve : StationDepotCurves.all(B)) {
            assertEquals(0, curve.toZ(), "lands on the main line");
        }
    }

    @Test
    void chordLengthsArePlausibleForACurve() {
        for (var curve : StationDepotCurves.all(B)) {
            double chord = StationDepotCurves.chordLength(curve);
            assertTrue(chord > 0.0D, "non-zero chord: " + curve);
            assertTrue(chord < 64.0D, "a merge curve must stay short: " + chord);
        }
        // The negative siding merges from the same start column, further east, so its
        // chord is longer; both must remain inside the builder's length ceiling.
        assertTrue(StationDepotCurves.chordLength(StationDepotCurves.negative(B))
                > StationDepotCurves.chordLength(StationDepotCurves.positive(B)));
    }

    @Test
    void theSwitchSitsAtThePositiveCurvesLandingColumn() {
        assertEquals(StationDepotCurves.POSITIVE_MERGE_DX, StationDepotGeometry.JUNCTION_DX,
                "the switch must stand where a curve actually lands");
        assertTrue(StationDepotGeometry.carves(B, StationDepotGeometry.junctionX(B), 0, 0),
                "the switch position is inside the hall");
    }
}