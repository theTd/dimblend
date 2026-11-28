package dimblend.client.bandrift;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * The Sodium injection is string surgery against a shader we cannot compile here, so its anchors
 * are pinned on synthetic sources that carry exactly the text Sodium emits. The section-centre
 * varying is the piece that fixed the coplanar seam wall, so both vertex paths and the fragment
 * side test are covered in their own case.
 */
class SodiumRiftShaderSourceTest {

    /** {@code sodium:blocks/block_layer_opaque.vsh} after import expansion, primary anchor. */
    private static final String VERTEX_PRIMARY = """
            #version 330 core
            #import <sodium:blocks/base.vsh>
            out vec4 v_Color;
            void main() {
                _vert_init();
                vec3 position = _vert_position + translation;
                gl_Position = u_ProjMat * u_ModelViewMat * vec4(position, 1.0);
                v_Color = _vert_color;
            }
            """;

    /** Same shader after Veil's AST rewriter moved the position expression. */
    private static final String VERTEX_REWRITTEN = """
            #version 330 core
            out vec4 v_Color;
            void main() {
                _vert_init();
                vec4 pos = vec4(_vert_position, 1.0);
                gl_Position = u_ProjMat * u_ModelViewMat * pos;
                v_Color = _vert_color;
            }
            """;

    private static final String FRAGMENT = """
            #version 330 core
            #import <sodium:blocks/base.fsh>
            in vec3 v_WorldRel;
            out vec4 fragColor;
            void main() {
                vec4 color = _vert_color;
                fragColor = _linearFog(color, v_FragDistance, u_FogColor, u_FogStart, u_FogEnd);
            }
            """;

    @Test
    void primaryVertexAnchorDeclaresAndAssignsTheFlatSectionCenter() {
        String injected = SodiumRiftShaderSource.inject(true, VERTEX_PRIMARY);
        assertNotNull(injected, "the primary anchor must always be found");
        assertTrue(injected.contains("flat out vec3 v_SectionCenter;"),
                "vertex declaration must carry the flat varying");
        assertTrue(injected.contains("v_SectionCenter = translation + vec3(8.0);"),
                "primary path anchors the section centre on the draw translation");
        assertTrue(SodiumRiftShaderSource.injected(injected), "marker present for idempotency");
        assertTrue(injected.indexOf("v_SectionCenter = translation") < injected.indexOf("gl_Position"),
                "the assignment must land before the vertex is projected");
    }

    @Test
    void veilRewrittenVertexFallsBackWithItsOwnSectionCenter() {
        String injected = SodiumRiftShaderSource.inject(true, VERTEX_REWRITTEN);
        assertNotNull(injected, "gl_Position inside main() is the fallback anchor");
        assertTrue(injected.contains("flat out vec3 v_SectionCenter;"));
        assertTrue(injected.contains("vec3 dimblendTrans = u_RegionOffset + _get_draw_translation(_draw_id);"));
        assertTrue(injected.contains("v_SectionCenter = dimblendTrans + vec3(8.0); // dimblend:rift-mask v1"),
                "fallback must keep the same section-centre semantics");
        assertTrue(injected.indexOf("v_SectionCenter = dimblendTrans") < injected.indexOf("gl_Position"),
                "the fallback assignment must precede gl_Position, not sit at the top of main()");
    }

    @Test
    void fragmentUsesTheSectionCentreSideTest() {
        String injected = SodiumRiftShaderSource.inject(false, FRAGMENT);
        assertNotNull(injected);
        assertTrue(injected.contains("flat in vec3 v_SectionCenter;"));
        assertTrue(injected.contains("float dimblendSecDiff = dot(v_SectionCenter, u_RiftPlane.xyz) - u_RiftPlane.w;"));
        assertTrue(injected.contains("abs(dimblendSecDiff) >= 7.5"),
                "the same 7.5-block margin as VolumetricRiftMath.SECTION_CENTER_MARGIN");
        assertTrue(injected.contains("dimblendRiftKeeps(u_RiftMode, dimblendNear, dimblendRift.x > 0.5)"));
        assertEquals(7.5F, VolumetricRiftMath.SECTION_CENTER_MARGIN, 1.0E-6F);
    }

    @Test
    void fragmentWithoutTheFogAnchorStillShipsTheMask() {
        String noFog = FRAGMENT.replace(
                "fragColor = _linearFog(color, v_FragDistance, u_FogColor, u_FogStart, u_FogEnd);",
                "fragColor = color;");
        assertFalse(SodiumRiftShaderSource.hasFragmentFogAnchor(noFog));
        String injected = SodiumRiftShaderSource.inject(false, noFog);
        assertNotNull(injected, "a cosmetic miss must not disable the mask");
        assertTrue(injected.contains("float dimblendSecDiff"));
    }

    @Test
    void bothVertexPathsAreAccepted() {
        assertTrue(SodiumRiftShaderSource.hasVertexPositionAnchor(VERTEX_PRIMARY));
        assertTrue(SodiumRiftShaderSource.hasVertexPositionAnchor(VERTEX_REWRITTEN));
        assertFalse(SodiumRiftShaderSource.hasVertexPositionAnchor("#version 330 core\nvoid main() { }\n"));
    }

    /**
     * The rift block exists in four copies (two GLSL files, the Sodium string surgery and the Java
     * mirror). {@code dimblendEvalRift} is the functional half, so it must stay token-identical —
     * a divergence here shows up as the mirage terrain and the real chunks disagreeing on holes.
     */
    @Test
    void threeGlslCopiesShareOneEvalRift() throws IOException {
        Path base = projectDir();
        Path shaders = base.resolve("src/main/resources/assets/dimblend/shaders/core");
        List<Path> copies = new ArrayList<>(List.of(
                shaders.resolve("mirage_terrain.fsh"),
                shaders.resolve("band_far_sky.fsh"),
                base.resolve("src/main/java/dimblend/client/bandrift/SodiumRiftShaderSource.java")));
        for (Path copy : copies) {
            Assumptions.assumeTrue(Files.isRegularFile(copy), "source tree not available: " + copy);
        }
        String expected = evalRift(Files.readString(copies.get(0)));
        for (Path copy : copies.subList(1, copies.size())) {
            assertEquals(expected, evalRift(copy.toString(), Files.readString(copy)),
                    copy.getFileName() + " diverged from mirage_terrain.fsh");
        }
        assertTrue(expected.contains("float fringe = min(1.0, solidEdge + 0.14 * max(solidScale, 0.25));"),
                "every copy must carry the tightened crack fringe");
        // The 07 fix: the aperture is resolved on the stationary seam plane, never on a 3D
        // ellipsoid whose front face slides forward with the camera.
        assertTrue(expected.contains("float tPlane = u_RiftPlane.w / denom;"),
                "the aperture must be found by a ray/plane crossing on the seam plane");
        assertFalse(expected.contains("u_RiftRadii.xyz"),
                "the aperture must never be a depth-carrying ellipsoid again");
        assertFalse(expected.contains("vec3 radii"),
                "no 3D radii anywhere in the eval: the mask is a flat hole on the plane");
    }

    private static Path projectDir() {
        if (Files.isDirectory(Path.of("src/main/resources"))) {
            return Path.of("");
        }
        if (Files.isDirectory(Path.of("../src/main/resources"))) {
            return Path.of("..");
        }
        return Path.of("dimblend");
    }

    private static String evalRift(String text) {
        return evalRift("text", text);
    }

    /** Brace-matches {@code dimblendEvalRift} and strips per-line indentation. */
    private static String evalRift(String name, String text) {
        int start = text.indexOf("vec2 dimblendEvalRift(vec3 p) {");
        assertTrue(start >= 0, name + " has no dimblendEvalRift");
        int depth = 0;
        int end = -1;
        for (int i = text.indexOf('{', start); i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                end = i;
                break;
            }
        }
        assertTrue(end > start, name + " has an unterminated dimblendEvalRift");
        return text.substring(start, end + 1).lines().map(String::strip).collect(Collectors.joining("\n"));
    }
}
