package dimblend.experience.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Lightweight static hygiene contract over {@code dimblend-experience} Mixin sources.
 *
 * <p>Locks down verified failure modes:
 * <ol>
 *   <li>Superclass {@code @Shadow blockEntity} on {@code BlockEntityBehaviour} subclasses
 *       ({@code GutterDrainerPurityMixin}, {@code FluidDrainingBehaviourPurityMixin}),
 *       which crashes Mixin 0.8.5 apply because the field is declared on the superclass.</li>
 *   <li>{@code public} visibility on Mixin injector handlers in non-interface {@code @Mixin}
 *       classes, which leaks synthetic handler methods into the target class's public API.</li>
 * </ol>
 */
class MixinHygieneContractTest {

    private static final Set<String> INJECTOR_ANNOTATIONS = Set.of(
            "Inject",
            "Redirect",
            "ModifyArg",
            "ModifyArgs",
            "ModifyVariable",
            "ModifyConstant",
            "ModifyExpressionValue",
            "ModifyReturnValue",
            "WrapOperation",
            "WrapMethod",
            "WrapWithCondition");

    private static final Set<String> METHOD_MODIFIERS = Set.of(
            "public",
            "protected",
            "private",
            "static",
            "final",
            "abstract",
            "synchronized",
            "native",
            "strictfp",
            "default");

    @Test
    void behaviourPurityMixinsDoNotShadowSuperclassBlockEntityField() throws IOException {
        Path mixinRoot = resolveMixinRoot();
        List<Path> targets = List.of(
                mixinRoot.resolve("compat/yat/GutterDrainerPurityMixin.java"),
                mixinRoot.resolve("compat/yat/FluidDrainingBehaviourPurityMixin.java"));

        List<String> violations = new ArrayList<>();
        for (Path file : targets) {
            assertTrue(Files.exists(file), "Expected mixin file to exist: " + file);
            String source = Files.readString(file, StandardCharsets.UTF_8);
            if (hasShadowedField(source, "blockEntity")) {
                violations.add(mixinRoot.relativize(file).toString().replace('\\', '/'));
            }
        }
        assertEquals(List.of(), violations,
                "BlockEntityBehaviour subclasses must access ((BlockEntityBehaviour) (Object) this).blockEntity "
                        + "instead of declaring @Shadow blockEntity");
    }

    @Test
    void nonInterfaceMixinClassesDeclareNoPublicInjectorHandlers() throws IOException {
        Path mixinRoot = resolveMixinRoot();
        List<Path> javaFiles;
        try (Stream<Path> stream = Files.walk(mixinRoot)) {
            javaFiles = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".java"))
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
        }
        assertTrue(javaFiles.size() > 50, "Expected to scan mixin source tree under " + mixinRoot);

        int mixinClassCount = 0;
        List<String> violations = new ArrayList<>();
        for (Path file : javaFiles) {
            String source = Files.readString(file, StandardCharsets.UTF_8);
            String stripped = stripCommentsAndLiterals(source);
            if (!isNonInterfaceMixinClass(stripped)) {
                continue;
            }
            mixinClassCount++;
            String rel = mixinRoot.relativize(file).toString().replace('\\', '/');
            violations.addAll(findPublicInjectorHandlers(stripped, rel));
        }
        assertTrue(mixinClassCount > 50, "Expected to find >50 non-interface @Mixin classes, found " + mixinClassCount);
        assertEquals(List.of(), violations,
                "Non-interface @Mixin classes must not declare public injector handlers");
    }

    @Test
    void syntheticViolationsAreDetectedAndCleanSamplesPass() {
        String badShadow = """
                @Mixin(FluidDrainingBehaviour.class)
                public abstract class BadBehaviourMixin {
                    @Shadow(remap = false)
                    private SmartBlockEntity blockEntity;
                }
                """;
        assertTrue(hasShadowedField(badShadow, "blockEntity"),
                "must catch @Shadow(remap = false) on blockEntity");

        String commentOnlyShadow = """
                @Mixin(FluidDrainingBehaviour.class)
                public abstract class CleanBehaviourMixin {
                    // @Shadow private SmartBlockEntity blockEntity;
                    /* @Shadow(remap = false) private SmartBlockEntity blockEntity; */
                    private void ok() {
                        var be = ((BlockEntityBehaviour) (Object) this).blockEntity;
                    }
                }
                """;
        assertFalse(hasShadowedField(commentOnlyShadow, "blockEntity"),
                "must ignore @Shadow mentioned only inside comments");

        String badPublicInjectors = """
                @Mixin(SomeTarget.class)
                public abstract class BadPublicInjectorMixin {
                    @Inject(method = "tick()V", at = @At(value = "INVOKE", target = "Lfoo/Bar;baz()V"))
                    public void dimblend$badInject(CallbackInfo ci) {}

                    @ModifyExpressionValue(
                            at = @At(value = "FIELD", target = "Lfoo/Bar;active:Z", remap = false),
                            method = "tickAudio()V")
                    @SuppressWarnings("unused")
                    public static boolean dimblend$badModify(boolean orig) { return orig; }

                    @WrapMethod(method = "foo")
                    private void dimblend$goodPrivate() {}
                }
                """;
        String strippedBad = stripCommentsAndLiterals(badPublicInjectors);
        assertTrue(isNonInterfaceMixinClass(strippedBad));
        List<String> found = findPublicInjectorHandlers(strippedBad, "BadPublicInjectorMixin.java");
        assertEquals(2, found.size(), "Expected exactly 2 public injector violations, got: " + found);
        assertTrue(found.get(0).contains("@Inject"), found.get(0));
        assertTrue(found.get(1).contains("@ModifyExpressionValue"), found.get(1));

        String mixinInterface = """
                @Mixin(SomeTarget.class)
                public interface AccessorMixin {
                    @Accessor("foo")
                    int dimblend$getFoo();
                }
                """;
        assertFalse(isNonInterfaceMixinClass(stripCommentsAndLiterals(mixinInterface)),
                "interface @Mixin must be excluded from non-interface class check");
    }

    private static Path resolveMixinRoot() {
        Path moduleRoot = Path.of("").toAbsolutePath();
        Path direct = moduleRoot.resolve("src/main/java/dimblend/experience/mixin");
        if (Files.isDirectory(direct)) {
            return direct;
        }
        Path nested = moduleRoot.resolve("dimblend-experience/src/main/java/dimblend/experience/mixin");
        assertTrue(Files.isDirectory(nested), "Could not locate mixin source root from " + moduleRoot);
        return nested;
    }

    static boolean hasShadowedField(String rawSource, String fieldName) {
        String code = stripCommentsAndLiterals(rawSource);
        int len = code.length();
        int i = 0;
        while (i < len) {
            if (code.charAt(i) == '@') {
                int nameStart = skipWhitespace(code, i + 1);
                int nameEnd = readQualifiedIdentifierEnd(code, nameStart);
                String simple = simpleName(code.substring(nameStart, nameEnd));
                if ("Shadow".equals(simple)) {
                    int pos = skipAnnotationArgsAndStackedAnnotations(code, nameEnd);
                    // Read declaration up to ';', '{', or '(' — field declarations terminate with ';' before '(' or '{'.
                    int declEnd = pos;
                    while (declEnd < len) {
                        char c = code.charAt(declEnd);
                        if (c == ';' || c == '{' || c == '(') {
                            break;
                        }
                        declEnd++;
                    }
                    if (declEnd < len && code.charAt(declEnd) == ';') {
                        String decl = code.substring(pos, declEnd);
                        int eqIdx = decl.indexOf('=');
                        String lhs = (eqIdx >= 0 ? decl.substring(0, eqIdx) : decl).trim();
                        String declaredName = lastIdentifierToken(lhs);
                        if (fieldName.equals(declaredName)) {
                            return true;
                        }
                    }
                    i = declEnd + 1;
                    continue;
                }
            }
            i++;
        }
        return false;
    }

    static boolean isNonInterfaceMixinClass(String strippedCode) {
        int len = strippedCode.length();
        int i = 0;
        while (i < len) {
            if (strippedCode.charAt(i) == '@') {
                int nameStart = skipWhitespace(strippedCode, i + 1);
                int nameEnd = readQualifiedIdentifierEnd(strippedCode, nameStart);
                String simple = simpleName(strippedCode.substring(nameStart, nameEnd));
                if ("Mixin".equals(simple)) {
                    int pos = skipAnnotationArgsAndStackedAnnotations(strippedCode, nameEnd);
                    while (pos < len) {
                        pos = skipWhitespace(strippedCode, pos);
                        int tokEnd = readIdentifierEnd(strippedCode, pos);
                        if (tokEnd == pos) {
                            break;
                        }
                        String tok = strippedCode.substring(pos, tokEnd);
                        if ("class".equals(tok)) {
                            return true;
                        }
                        if ("interface".equals(tok) || "enum".equals(tok) || "record".equals(tok)) {
                            return false;
                        }
                        pos = tokEnd;
                    }
                }
            }
            i++;
        }
        return false;
    }

    static List<String> findPublicInjectorHandlers(String strippedCode, String fileLabel) {
        List<String> violations = new ArrayList<>();
        int len = strippedCode.length();
        int i = 0;
        while (i < len) {
            if (strippedCode.charAt(i) == '@') {
                int nameStart = skipWhitespace(strippedCode, i + 1);
                int nameEnd = readQualifiedIdentifierEnd(strippedCode, nameStart);
                String simple = simpleName(strippedCode.substring(nameStart, nameEnd));
                if (INJECTOR_ANNOTATIONS.contains(simple)) {
                    int pos = skipAnnotationArgsAndStackedAnnotations(strippedCode, nameEnd);
                    while (pos < len) {
                        pos = skipWhitespace(strippedCode, pos);
                        int tokEnd = readIdentifierEnd(strippedCode, pos);
                        if (tokEnd == pos) {
                            break;
                        }
                        String tok = strippedCode.substring(pos, tokEnd);
                        if (!METHOD_MODIFIERS.contains(tok)) {
                            break;
                        }
                        if ("public".equals(tok)) {
                            int line = lineNumberAt(strippedCode, pos);
                            violations.add(fileLabel + ":" + line + " declares public @" + simple + " handler");
                            break;
                        }
                        pos = tokEnd;
                    }
                    i = Math.max(pos, nameEnd);
                    continue;
                }
            }
            i++;
        }
        return violations;
    }

    /**
     * Strips {@code //} and {@code /* ... *\/} comments and blanks out string/char literal bodies
     * while preserving all line breaks so reported line numbers match the original file.
     */
    static String stripCommentsAndLiterals(String src) {
        int len = src.length();
        StringBuilder out = new StringBuilder(len);
        int i = 0;
        while (i < len) {
            char c = src.charAt(i);
            char next = i + 1 < len ? src.charAt(i + 1) : '\0';
            if (c == '/' && next == '/') {
                i += 2;
                while (i < len && src.charAt(i) != '\n') {
                    i++;
                }
            } else if (c == '/' && next == '*') {
                i += 2;
                while (i < len) {
                    char ch = src.charAt(i);
                    if (ch == '\n') {
                        out.append('\n');
                    }
                    if (ch == '*' && i + 1 < len && src.charAt(i + 1) == '/') {
                        i += 2;
                        break;
                    }
                    i++;
                }
            } else if (c == '"' && i + 2 < len && src.charAt(i + 1) == '"' && src.charAt(i + 2) == '"') {
                // Text block
                out.append('"').append('"');
                i += 3;
                while (i < len) {
                    char ch = src.charAt(i);
                    if (ch == '\n') {
                        out.append('\n');
                    }
                    if (ch == '"' && i + 2 < len && src.charAt(i + 1) == '"' && src.charAt(i + 2) == '"') {
                        i += 3;
                        break;
                    }
                    i++;
                }
            } else if (c == '"' || c == '\'') {
                char quote = c;
                out.append(quote);
                i++;
                while (i < len) {
                    char ch = src.charAt(i);
                    if (ch == '\\' && i + 1 < len) {
                        i += 2;
                        continue;
                    }
                    if (ch == '\n') {
                        out.append('\n');
                    }
                    i++;
                    if (ch == quote) {
                        out.append(quote);
                        break;
                    }
                }
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    private static int skipAnnotationArgsAndStackedAnnotations(String code, int pos) {
        pos = skipBalancedParensIfPresent(code, pos);
        int len = code.length();
        while (true) {
            int next = skipWhitespace(code, pos);
            if (next >= len || code.charAt(next) != '@') {
                return next;
            }
            int nameStart = skipWhitespace(code, next + 1);
            int nameEnd = readQualifiedIdentifierEnd(code, nameStart);
            if (nameEnd == nameStart) {
                return next;
            }
            pos = skipBalancedParensIfPresent(code, nameEnd);
        }
    }

    private static int skipBalancedParensIfPresent(String code, int pos) {
        int len = code.length();
        int p = skipWhitespace(code, pos);
        if (p >= len || code.charAt(p) != '(') {
            return p;
        }
        int depth = 0;
        while (p < len) {
            char c = code.charAt(p++);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    break;
                }
            }
        }
        return p;
    }

    private static int skipWhitespace(String code, int pos) {
        int len = code.length();
        while (pos < len && Character.isWhitespace(code.charAt(pos))) {
            pos++;
        }
        return pos;
    }

    private static int readIdentifierEnd(String code, int pos) {
        int len = code.length();
        if (pos >= len || !Character.isJavaIdentifierStart(code.charAt(pos))) {
            return pos;
        }
        int p = pos + 1;
        while (p < len && Character.isJavaIdentifierPart(code.charAt(p))) {
            p++;
        }
        return p;
    }

    private static int readQualifiedIdentifierEnd(String code, int pos) {
        int p = readIdentifierEnd(code, pos);
        int len = code.length();
        while (p < len && code.charAt(p) == '.') {
            int next = readIdentifierEnd(code, p + 1);
            if (next == p + 1) {
                break;
            }
            p = next;
        }
        return p;
    }

    private static String simpleName(String qualified) {
        int dot = qualified.lastIndexOf('.');
        return dot >= 0 ? qualified.substring(dot + 1) : qualified;
    }

    private static String lastIdentifierToken(String text) {
        int end = text.length();
        while (end > 0 && !Character.isJavaIdentifierPart(text.charAt(end - 1))) {
            end--;
        }
        int start = end;
        while (start > 0 && Character.isJavaIdentifierPart(text.charAt(start - 1))) {
            start--;
        }
        return text.substring(start, end);
    }

    private static int lineNumberAt(String text, int index) {
        int line = 1;
        for (int i = 0; i < index && i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }
}
