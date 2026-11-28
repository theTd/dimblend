package dimblend.client.bandrift;

import javax.annotation.Nullable;

/**
 * String surgery that adds the volumetric rift mask to Sodium's chunk shader source.
 *
 * <p>Kept out of the mixin on purpose: this is the riskiest part of S2 (it depends on the exact
 * text of {@code sodium:blocks/block_layer_opaque.vsh/.fsh}), and a plain class can be unit
 * tested against the real 0.8.13 sources, with the mixin reduced to a single call.</p>
 *
 * <p><b>Anchors.</b> The vertex shader gets two extra varyings ({@code v_WorldRel} and the
 * {@code flat} {@code v_SectionCenter}) plus their assignments after
 * {@code vec3 position = _vert_position + translation;} (fallback: first line of {@code main()},
 * using the same three symbols once Sodium has inlined its imports). The fragment shader gets the
 * declarations after {@code #version}, the four-quadrant mask as the first statement of
 * {@code main()}, and the rim tint after the existing {@code _linearFog} assignment. Every
 * insertion is idempotent through {@link #MARKER}.</p>
 *
 * <p>The GLSL block is byte-identical to {@code mirage_terrain.fsh} and {@code band_far_sky.fsh};
 * {@link VolumetricRiftMath} is the Java mirror. Change one, change all four.</p>
 */
public final class SodiumRiftShaderSource {
    public static final String MARKER = "// dimblend:rift-mask v1";

    private static final String VERTEX_DECLARATION = """
            // dimblend:rift-mask v1
            out vec3 v_WorldRel;
            flat out vec3 v_SectionCenter;
            """;

    private static final String VERTEX_POSITION_ANCHOR = "vec3 position = _vert_position + translation;";

    private static final String VERTEX_INIT_ANCHOR = "_vert_init();";

    /** {@code flat} section centre: constant per primitive, so it cannot jitter like a fragment. */
    private static final String VERTEX_ASSIGN = """
                v_WorldRel = position;
                v_SectionCenter = translation + vec3(8.0);
            """;

    private static final String VERTEX_FALLBACK = """
                vec3 dimblendTrans = u_RegionOffset + _get_draw_translation(_draw_id);
                v_WorldRel = dimblendTrans + _vert_position;
                v_SectionCenter = dimblendTrans + vec3(8.0); // dimblend:rift-mask v1
            """;

    private static final String FRAGMENT_HEADER = """
            // dimblend:rift-mask v1
            in vec3 v_WorldRel;
            flat in vec3 v_SectionCenter;
            uniform int u_RiftMode;
            uniform vec4 u_RiftPlane;   // xyz = near->far unit normal, w = (slabX - cameraX) * normal.x
            uniform vec4 u_RiftCenter;  // xyz = aperture centre, camera relative; w = openFactor
            uniform vec4 u_RiftRadii;   // x = fixed epsilon depth; yz = aperture radii; w = random hole density
            uniform vec4 u_RiftStyle;   // xyz = far-lane glow colour; w = real seconds
            uniform vec4 u_RiftCam;     // xyz = mod(cameraPos, 64); w = rim width in blocks

            float dimblendRiftHash(vec3 p) {
                p = fract(p * 0.1031);
                float spread = dot(p, p.yzx + 33.33);
                p += spread;
                return fract((p.x + p.y) * p.z);
            }

            float dimblendRiftNoise(vec3 p) {
                vec3 cell = floor(p);
                vec3 f = p - cell;
                vec3 u = f * f * (3.0 - 2.0 * f);
                float n000 = dimblendRiftHash(cell);
                float n100 = dimblendRiftHash(cell + vec3(1.0, 0.0, 0.0));
                float n010 = dimblendRiftHash(cell + vec3(0.0, 1.0, 0.0));
                float n110 = dimblendRiftHash(cell + vec3(1.0, 1.0, 0.0));
                float n001 = dimblendRiftHash(cell + vec3(0.0, 0.0, 1.0));
                float n101 = dimblendRiftHash(cell + vec3(1.0, 0.0, 1.0));
                float n011 = dimblendRiftHash(cell + vec3(0.0, 1.0, 1.0));
                float n111 = dimblendRiftHash(cell + vec3(1.0, 1.0, 1.0));
                float nx00 = mix(n000, n100, u.x);
                float nx10 = mix(n010, n110, u.x);
                float nx01 = mix(n001, n101, u.x);
                float nx11 = mix(n011, n111, u.x);
                return mix(mix(nx00, nx10, u.y), mix(nx01, nx11, u.y), u.z);
            }

            // Two-octave world-locked voxel fBM for the fracture edge; hitW is on the 1/8-block world grid.
            float dimblendRiftWobble(vec3 hitW, float seconds) {
                return 0.68 * dimblendRiftNoise(hitW * 0.20 + vec3(0.0, seconds * 0.18, 0.0))
                     + 0.32 * dimblendRiftNoise(hitW * 0.55 + vec3(0.0, seconds * 0.31, 0.0));
            }

            // (x = 1 when this fragment is reached through the portal, y = edge glow weight)
            vec2 dimblendEvalRift(vec3 p) {
                float open = u_RiftCenter.w;
                if (open <= 0.002) {
                    return vec2(0.0);
                }
                float distP = length(p);
                vec3 dir = p / max(distP, 1e-4);
                float denom = dot(dir, u_RiftPlane.xyz);
                if (denom <= 1e-4) {
                    return vec2(0.0);
                }
                float tPlane = u_RiftPlane.w / denom;
                if (tPlane < 0.0 || distP < tPlane) {
                    return vec2(0.0);
                }
                vec3 hitRel = dir * tPlane;
                vec2 radiiYZ = max(u_RiftRadii.yz, vec2(1e-3));
                vec2 deltaYZ = (hitRel.yz - u_RiftCenter.yz) / radiiYZ;
                float radial = length(deltaYZ);
                if (radial >= 1.15) {
                    return vec2(0.0);
                }
                // World-locked 1/8-block voxel point right on the stationary seam plane X = slabX.
                vec3 hitW = floor((hitRel + u_RiftCam.xyz) * 8.0) * 0.125;
                float wobble = dimblendRiftWobble(hitW, u_RiftStyle.w);
                float solidScale = smoothstep(0.20, 0.95, open);
                float solidEdge = solidScale * (0.74 + 0.38 * wobble);
                float glitch = clamp(u_RiftRadii.w, 0.0, 0.35);
                bool hole = false;
                float border = 1.0;
                float fringe = min(1.0, solidEdge + 0.14 * max(solidScale, 0.25));
                if (radial < fringe && glitch > 0.001) {
                    vec3 cell = floor(hitW * 12.0);
                    float tick = floor(u_RiftStyle.w * 10.0);
                    float cellHash = dimblendRiftHash(cell + vec3(tick * 7.13, tick * 3.71, tick * 11.17));
                    hole = cellHash < glitch;
                    if (hole) {
                        border = 0.055;
                    }
                }
                float spark = step(0.985, dimblendRiftHash(floor(hitW * 16.0) + vec3(0.0, 0.0, floor(u_RiftStyle.w * 7.0))));
                bool hit = false;
                float edgeMetric = 1.0;
                if (radial < solidEdge) {
                    hit = true;
                    edgeMetric = abs(radial - solidEdge);
                } else if (hole) {
                    hit = true;
                    edgeMetric = border;
                }
                if (!hit) {
                    return vec2(0.0);
                }
                float surfRim = 1.0 - smoothstep(0.0, max(u_RiftCam.w, 0.05), distP - tPlane);
                float silRim = 1.0 - smoothstep(0.0, 0.09, edgeMetric);
                float rim = max(surfRim * 0.85, silRim);
                // World-locked 1/16-block pinpricks: the fracture rim twinkles instead of glowing flat.
                float rimSpark = spark * (silRim + surfRim * 0.35);
                return vec2(1.0, min(1.0, rim + rimSpark * 0.35));
            }

            bool dimblendRiftKeeps(int mode, bool nearSide, bool other) {
                if (mode == 1) {
                    return nearSide && !other;
                }
                if (mode == 2) {
                    return !nearSide && other;
                }
                if (mode == 3) {
                    return !nearSide && !other;
                }
                if (mode == 4) {
                    return nearSide && other;
                }
                return true;
            }
            """;

    private static final String FRAGMENT_MAIN_PREFIX = """
                vec2 dimblendRift = dimblendEvalRift(v_WorldRel);
                if (u_RiftMode != 0) {
                    // A chunk section whose west face lies on the seam plane interpolates
                    // v_WorldRel by a few ULP either way, so that wall leaked into the wrong pass
                    // and wrote depth over the mirage and sky. The section centre is flat: read the
                    // side from it whenever it sits more than half a section off the plane.
                    float dimblendSecDiff = dot(v_SectionCenter, u_RiftPlane.xyz) - u_RiftPlane.w;
                    bool dimblendNear = abs(dimblendSecDiff) >= 7.5
                            ? (dimblendSecDiff < 0.0)
                            : (dot(v_WorldRel, u_RiftPlane.xyz) < u_RiftPlane.w);
                    if (!dimblendRiftKeeps(u_RiftMode, dimblendNear, dimblendRift.x > 0.5)) {
                        discard;
                    }
                }
            """;

    private static final String FRAGMENT_FOG_ANCHOR =
            "fragColor = _linearFog(color, v_FragDistance, u_FogColor, u_FogStart, u_FogEnd);";

    private static final String FRAGMENT_TINT = """
                if ((u_RiftMode == 2 || u_RiftMode == 4) && dimblendRift.y > 0.01) {
                    // Saturated far-lane colour on the outer ring; only the tight fracture core goes white.
                    vec3 dimblendHot = mix(u_RiftStyle.rgb, vec3(1.0, 0.97, 0.92), smoothstep(0.75, 1.0, dimblendRift.y) * 0.6) * 1.30;
                    fragColor.rgb = mix(fragColor.rgb, dimblendHot, dimblendRift.y * 0.85);
                }
            """;

    private SodiumRiftShaderSource() {
    }

    /** True when {@code source} already carries the mask. */
    public static boolean injected(String source) {
        return source.contains(MARKER);
    }

    /**
     * Rewrites one shader stage.
     *
     * @param vertex true for the vertex stage, false for the fragment stage
     * @param source the raw Sodium shader text (imports still unexpanded)
     * @return the injected source, or null when an anchor is missing
     */
    @Nullable
    public static String inject(boolean vertex, String source) {
        return vertex ? injectVertex(source) : injectFragment(source);
    }

    /** True when the vertex position anchor (or gl_Position anchor inside main) is present. */
    public static boolean hasVertexPositionAnchor(String source) {
        return source.contains(VERTEX_POSITION_ANCHOR) || findMainAnchor(source, "gl_Position") >= 0;
    }

    /** True when the fragment fog anchor (rim tint) is present. */
    public static boolean hasFragmentFogAnchor(String source) {
        return source.contains(FRAGMENT_FOG_ANCHOR);
    }

    @Nullable
    private static String injectVertex(String source) {
        String withVarying = insertAfterVersion(source, VERTEX_DECLARATION);
        if (withVarying == null) {
            return null;
        }
        int anchor = withVarying.indexOf(VERTEX_POSITION_ANCHOR);
        if (anchor >= 0) {
            int lineEnd = withVarying.indexOf('\n', anchor);
            int insertAt = lineEnd < 0 ? withVarying.length() : lineEnd + 1;
            String pad = lineEnd < 0 ? "\n" : "";
            return withVarying.substring(0, insertAt) + VERTEX_ASSIGN
                    + pad + withVarying.substring(insertAt);
        }
        // Veil's AST rewriter reformats `vec3 position = ...`; insert right before `gl_Position`
        // inside `main()`, which is guaranteed to run after `_vert_init()` has unpacked
        // `_vert_position` and `_draw_id` (putting it at the top of main() leaves both zeroed).
        int glPos = findMainAnchor(withVarying, "gl_Position");
        if (glPos >= 0) {
            int lineStart = withVarying.lastIndexOf('\n', glPos) + 1;
            return withVarying.substring(0, lineStart) + VERTEX_FALLBACK
                    + withVarying.substring(lineStart);
        }
        int initAnchor = findMainAnchor(withVarying, VERTEX_INIT_ANCHOR);
        if (initAnchor >= 0) {
            int lineEnd = withVarying.indexOf('\n', initAnchor);
            int insertAt = lineEnd < 0 ? withVarying.length() : lineEnd + 1;
            String pad = lineEnd < 0 ? "\n" : "";
            return withVarying.substring(0, insertAt) + VERTEX_FALLBACK
                    + pad + withVarying.substring(insertAt);
        }
        return null;
    }

    /** Finds {@code token} strictly after the {@code void main()} opening brace. */
    private static int findMainAnchor(String source, String token) {
        int mainIdx = source.indexOf("void main()");
        if (mainIdx < 0) {
            return -1;
        }
        int openBrace = source.indexOf('{', mainIdx);
        if (openBrace < 0) {
            return -1;
        }
        return source.indexOf(token, openBrace + 1);
    }

    @Nullable
    private static String injectFragment(String source) {
        String withHeader = insertAfterVersion(source, FRAGMENT_HEADER);
        if (withHeader == null) {
            return null;
        }
        String withMask = insertAfterMainOpen(withHeader, FRAGMENT_MAIN_PREFIX);
        if (withMask == null) {
            return null;
        }
        int anchor = withMask.indexOf(FRAGMENT_FOG_ANCHOR);
        if (anchor < 0) {
            // The mask (the functional half) is in; only the rim tint is lost. Ship the masked
            // source rather than disabling S2 for a cosmetic miss.
            return withMask;
        }
        int lineEnd = withMask.indexOf('\n', anchor);
        int insertAt = lineEnd < 0 ? withMask.length() : lineEnd + 1;
        String pad = lineEnd < 0 ? "\n" : "";
        return withMask.substring(0, insertAt) + FRAGMENT_TINT + pad + withMask.substring(insertAt);
    }

    /** Inserts {@code text} on its own line(s) directly after the {@code #version} line. */
    @Nullable
    private static String insertAfterVersion(String source, String text) {
        int lineStart = 0;
        while (lineStart < source.length()) {
            int lineEnd = source.indexOf('\n', lineStart);
            if (source.startsWith("#version", lineStart)) {
                int insertAt = lineEnd < 0 ? source.length() : lineEnd + 1;
                String pad = lineEnd < 0 ? "\n" : "";
                return source.substring(0, insertAt) + text + pad + source.substring(insertAt);
            }
            if (lineEnd < 0) {
                break;
            }
            lineStart = lineEnd + 1;
        }
        return null;
    }

    /** Inserts {@code text} directly after the first {@code void main() {} line. */
    @Nullable
    private static String insertAfterMainOpen(String source, String text) {
        int lineStart = 0;
        while (lineStart < source.length()) {
            int lineEnd = source.indexOf('\n', lineStart);
            int end = lineEnd < 0 ? source.length() : lineEnd;
            String line = source.substring(lineStart, end);
            if (line.contains("void main()") && line.contains("{")) {
                int insertAt = lineEnd < 0 ? source.length() : lineEnd + 1;
                String pad = lineEnd < 0 ? "\n" : "";
                return source.substring(0, insertAt) + text + pad + source.substring(insertAt);
            }
            if (lineEnd < 0) {
                break;
            }
            lineStart = lineEnd + 1;
        }
        return null;
    }
}
