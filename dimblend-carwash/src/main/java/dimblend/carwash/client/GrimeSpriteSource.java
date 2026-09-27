package dimblend.carwash.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dimblend.carwash.DimBlendCarwash;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.atlas.SpriteSource;
import net.minecraft.client.renderer.texture.atlas.SpriteSourceType;
import net.minecraft.client.resources.metadata.animation.FrameSize;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceMetadata;

import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.Optional;

/**
 * 图集精灵源：从现有方块贴图（原版泥土/沙砾，随资源包）生成若干张随机镂空变体，
 * 登记为 {@code <prefix><序号>}。在 {@code assets/minecraft/atlases/blocks.json} 中声明。
 * 动画贴图只取第一帧（正方形）。
 */
public record GrimeSpriteSource(ResourceLocation texture, ResourceLocation prefix, int variants, float remove,
        GrimeMask.Clear clear) implements SpriteSource {

    private static final Codec<GrimeMask.Clear> CLEAR_CODEC = Codec.STRING.comapFlatMap(
            name -> {
                for (GrimeMask.Clear value : GrimeMask.Clear.values()) {
                    if (value.name().toLowerCase(Locale.ROOT).equals(name)) {
                        return DataResult.success(value);
                    }
                }
                return DataResult.error(() -> "Unknown clear mode: " + name);
            },
            value -> value.name().toLowerCase(Locale.ROOT));

    public static final MapCodec<GrimeSpriteSource> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            ResourceLocation.CODEC.fieldOf("texture").forGetter(GrimeSpriteSource::texture),
            ResourceLocation.CODEC.fieldOf("prefix").forGetter(GrimeSpriteSource::prefix),
            Codec.intRange(1, 64).fieldOf("variants").forGetter(GrimeSpriteSource::variants),
            Codec.floatRange(0.0F, 1.0F).fieldOf("remove").forGetter(GrimeSpriteSource::remove),
            CLEAR_CODEC.optionalFieldOf("clear", GrimeMask.Clear.NONE).forGetter(GrimeSpriteSource::clear))
            .apply(instance, GrimeSpriteSource::new));

    /** 由 {@link CarwashClientSetup} 在 {@code RegisterSpriteSourceTypesEvent} 中登记。 */
    static final SpriteSourceType TYPE = new SpriteSourceType(CODEC);

    @Override
    public void run(ResourceManager resourceManager, SpriteSource.Output output) {
        ResourceLocation file = TEXTURE_ID_CONVERTER.idToFile(texture);
        Optional<Resource> resource = resourceManager.getResource(file);
        if (resource.isEmpty()) {
            DimBlendCarwash.LOGGER.warn("Grime sprite source texture {} not found", file);
            return;
        }
        int size;
        int[] frame;
        try (InputStream in = resource.get().open(); NativeImage image = NativeImage.read(in)) {
            size = Math.min(image.getWidth(), image.getHeight());
            frame = new int[size * size];
            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) {
                    frame[y * size + x] = image.getPixelRGBA(x, y);
                }
            }
        } catch (IOException e) {
            DimBlendCarwash.LOGGER.warn("Failed to read grime sprite source texture {}", file, e);
            return;
        }
        for (int i = 0; i < variants; i++) {
            ResourceLocation id = prefix.withSuffix(Integer.toString(i));
            // 种子只由精灵名决定：重载资源图案不变
            int[] masked = GrimeMask.apply(frame, size, size, id.hashCode() * 31L + i, remove, clear);
            int frameSize = size;
            output.add(id, loader -> toContents(id, masked, frameSize));
        }
    }

    private static SpriteContents toContents(ResourceLocation id, int[] pixels, int size) {
        NativeImage image = new NativeImage(size, size, false);
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                image.setPixelRGBA(x, y, pixels[y * size + x]);
            }
        }
        return new SpriteContents(id, new FrameSize(size, size), image, ResourceMetadata.EMPTY);
    }

    @Override
    public SpriteSourceType type() {
        return TYPE;
    }
}
