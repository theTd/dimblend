package dimblend.spyglass.test;

import com.mojang.serialization.Lifecycle;
import net.minecraft.core.Holder;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeGenerationSettings;
import net.minecraft.world.level.biome.BiomeSpecialEffects;
import net.minecraft.world.level.biome.MobSpawnSettings;

/**
 * Minimal frozen biome registry for the shape tests.
 *
 * <p>{@link Biome} can be built without the game: its constructor only stores data, and the builder
 * needs a few effects colours. The strings are the vanilla dimension's own.
 */
public final class TestBiomes {
    public static final ResourceKey<Biome> PLAINS = key("plains");
    public static final ResourceKey<Biome> DESERT = key("desert");
    public static final ResourceKey<Biome> OCEAN = key("ocean");

    private TestBiomes() {
    }

    public static Registry<Biome> create() {
        VanillaTestBootstrap.ensure();
        MappedRegistry<Biome> registry = new MappedRegistry<>(Registries.BIOME, Lifecycle.stable());
        register(registry, PLAINS, 0.8F, 0.4F);
        register(registry, DESERT, 2.0F, 0.0F);
        register(registry, OCEAN, 0.5F, 0.5F);
        registry.freeze();
        return registry;
    }

    public static Holder<Biome> holder(Registry<Biome> registry, ResourceKey<Biome> key) {
        return registry.getHolderOrThrow(key);
    }

    private static void register(
            MappedRegistry<Biome> registry, ResourceKey<Biome> key, float temperature, float downfall) {
        registry.register(key, biome(temperature, downfall), RegistrationInfo.BUILT_IN);
    }

    private static Biome biome(float temperature, float downfall) {
        return new Biome.BiomeBuilder()
                .hasPrecipitation(downfall > 0.0F)
                .temperature(temperature)
                .downfall(downfall)
                .specialEffects(new BiomeSpecialEffects.Builder()
                        .fogColor(0x11_2233)
                        .waterColor(0x22_3344)
                        .waterFogColor(0x33_4455)
                        .skyColor(0x44_5566)
                        .build())
                .mobSpawnSettings(MobSpawnSettings.EMPTY)
                .generationSettings(BiomeGenerationSettings.EMPTY)
                .build();
    }

    private static ResourceKey<Biome> key(String path) {
        return ResourceKey.create(Registries.BIOME, ResourceLocation.withDefaultNamespace(path));
    }
}
