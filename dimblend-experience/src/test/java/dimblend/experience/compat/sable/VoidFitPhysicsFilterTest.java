package dimblend.experience.compat.sable;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dimblend.experience.mixin.DimBlendMixinPlugin;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class VoidFitPhysicsFilterTest {

    @Test
    void airAndStructureVoidTransitionsArePhysicsEquivalent() {
        // AIR -> STRUCTURE_VOID
        assertTrue(VoidFitPhysicsFilter.isPhysicsEquivalentTransition(
                true, false, true,
                false, true, true));
        // STRUCTURE_VOID -> AIR
        assertTrue(VoidFitPhysicsFilter.isPhysicsEquivalentTransition(
                false, true, true,
                true, false, true));
        // AIR -> CAVE_AIR
        assertTrue(VoidFitPhysicsFilter.isPhysicsEquivalentTransition(
                true, false, true,
                true, false, true));
    }

    @Test
    void liquidAndSolidTransitionsAreNeverShortCircuited() {
        // WATER/LAVA -> STRUCTURE_VOID: Rapier bakes liquids as 1x1x1 buoyancy voxel colliders!
        assertFalse(VoidFitPhysicsFilter.isPhysicsEquivalentTransition(
                false, false, false,
                false, true, true),
                "evicting water/lava into structure_void MUST notify Rapier to remove the liquid buoyancy collider");
        // STRUCTURE_VOID -> WATER/LAVA
        assertFalse(VoidFitPhysicsFilter.isPhysicsEquivalentTransition(
                false, true, true,
                false, false, false),
                "fluid flowing back into former void cell MUST notify Rapier to add the liquid buoyancy collider");
        // Hypothetical modded air-flagged block with non-empty fluid state
        assertFalse(VoidFitPhysicsFilter.isPhysicsEquivalentTransition(
                true, false, false,
                false, true, true));
        // STRUCTURE_VOID -> STONE (player overwrote void with real block)
        assertFalse(VoidFitPhysicsFilter.isPhysicsEquivalentTransition(
                false, true, true,
                false, false, true));
    }

    @Test
    void mixinConfigAndPluginWireWorldBlockChangeShortCircuitSafely() throws Exception {
        Path moduleRoot = Path.of("").toAbsolutePath();
        if (!Files.exists(moduleRoot.resolve("src/main/resources/dimblend_experience.mixins.json"))) {
            moduleRoot = moduleRoot.resolve("dimblend-experience");
        }
        String mixinsJson = Files.readString(
                moduleRoot.resolve("src/main/resources/dimblend_experience.mixins.json"),
                StandardCharsets.UTF_8);
        int mixinsIdx = mixinsJson.indexOf("\"mixins\"");
        int clientIdx = mixinsJson.indexOf("\"client\"");
        int entryIdx = mixinsJson.indexOf("\"compat.sable.SableVoidFitBlockChangeMixin\"");
        assertTrue(entryIdx > mixinsIdx && entryIdx < clientIdx,
                "SableVoidFitBlockChangeMixin must be registered in common mixins array");

        String pluginSrc = Files.readString(
                moduleRoot.resolve("src/main/java/dimblend/experience/mixin/DimBlendMixinPlugin.java"),
                StandardCharsets.UTF_8);
        assertTrue(pluginSrc.contains(".compat.sable.SableVoidFitBlockChangeMixin"),
                "DimBlendMixinPlugin must gate SableVoidFitBlockChangeMixin");
        assertTrue(pluginSrc.contains("classPresent(\"dev.ryanhcode.sable.SableCommonEvents\")"),
                "DimBlendMixinPlugin must verify SableCommonEvents bytecode presence without Class.forName");
        assertFalse(pluginSrc.contains("getClassNode("),
                "ModLauncher throws IllegalArgumentException on untransformed getClassNode(name, false)");

        Path mixinFile = moduleRoot.resolve(
                "src/main/java/dimblend/experience/mixin/compat/sable/SableVoidFitBlockChangeMixin.java");
        assertTrue(Files.exists(mixinFile), "SableVoidFitBlockChangeMixin source must exist");
        String mixinSrc = Files.readString(mixinFile, StandardCharsets.UTF_8);
        assertTrue(mixinSrc.contains("getChunkHolder(chunk.getPos()) == null"),
                "must only short-circuit non-plot world chunks; plot chunks still need heatmap/bbox updates");
        assertTrue(mixinSrc.contains("require = 1") && mixinSrc.contains("allow = 1"),
                "injector must pin require = 1, allow = 1");
    }

    @Test
    void classPresentFindsExistingClassesWithoutLoadingOrTransforming() {
        assertTrue(DimBlendMixinPlugin.classPresent("dev.ryanhcode.sable.SableCommonEvents"),
                "existing Sable class must be detected during mixin gating");
        assertTrue(DimBlendMixinPlugin.classPresent("dev.ryanhcode.sable.util.SubLevelRainColumn"),
                "existing SubLevelRainColumn class must be detected during mixin gating");
        assertTrue(DimBlendMixinPlugin.classPresent("com.momosoftworks.coldsweat.util.world.WorldHelper"),
                "existing Cold Sweat WorldHelper class must be detected during mixin gating");
        assertFalse(DimBlendMixinPlugin.classPresent("dev.ryanhcode.sable.NonExistentClass"),
                "missing class must return false");
        assertFalse(DimBlendMixinPlugin.classPresent(""));
        assertFalse(DimBlendMixinPlugin.classPresent(null));

        ClassLoader probeOnlyLoader = new ClassLoader(null) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) {
                throw new AssertionError("classPresent must never load or define class " + name);
            }

            @Override
            public URL getResource(String name) {
                if ("fake/OptionalTarget.class".equals(name)) {
                    try {
                        return URI.create("file:/fake/OptionalTarget.class").toURL();
                    } catch (MalformedURLException e) {
                        throw new AssertionError(e);
                    }
                }
                return null;
            }
        };
        assertTrue(DimBlendMixinPlugin.classPresent("fake.OptionalTarget", probeOnlyLoader),
                "classPresent must resolve .class resource via ClassLoader without loading");
        assertFalse(DimBlendMixinPlugin.classPresent("fake.MissingTarget", probeOnlyLoader));
    }
}
