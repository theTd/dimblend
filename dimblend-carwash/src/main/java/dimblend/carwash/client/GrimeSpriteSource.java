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
import java.util.List;
import java.util.Locale;

/**
 * 图集精灵源：从原版方块贴图（只取 vanilla 包的 16x16，不跟资源包）按档生成随机镂空变体，
 * 登记为 {@code <prefix><档>_<变体>}。镂空比例从 {@code remove_from}（第 1 档）线性到
 * {@code remove_to}（最后一档）。在 {@code assets/minecraft/atlases/blocks.json} 中声明。
 */
public record GrimeSpriteSource(ResourceLocation texture, ResourceLocation prefix, int levels, float removeFrom,
        float removeTo, int variants, GrimeMask.Clear clear) implements SpriteSource {

    private static final String VANILLA_PACK_ID = "vanilla";

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
            Codec.intRange(1, 16).optionalFieldOf("levels", 1).forGetter(GrimeSpriteSource::levels),
            Codec.floatRange(0.0F, 1.0F).fieldOf("remove_from").forGetter(GrimeSpriteSource::removeFrom),
            Codec.floatRange(0.0F, 1.0F).fieldOf("remove_to").forGetter(GrimeSpriteSource::removeTo),
            Codec.intRange(1, 64).fieldOf("variants").forGetter(GrimeSpriteSource::variants),
            CLEAR_CODEC.optionalFieldOf("clear", GrimeMask.Clear.NONE).forGetter(GrimeSpriteSource::clear))
            .apply(instance, GrimeSpriteSource::new));

    /** 由 {@link CarwashClientSetup} 在 {@code RegisterSpriteSourceTypesEvent} 中登记。 */
    static final SpriteSourceType TYPE = new SpriteSourceType(CODEC);

    /** 第 {@code level} 档（1 起）第 {@code variant} 张的精灵名。 */
    static ResourceLocation spriteId(ResourceLocation prefix, int level, int variant) {
        return prefix.withSuffix(level + "_" + variant);
    }

    @Override
    public void run(ResourceManager resourceManager, SpriteSource.Output output) {
        ResourceLocation file = TEXTURE_ID_CONVERTER.idToFile(texture);
        Resource resource = vanillaResource(resourceManager.getResourceStack(file));
        if (resource == null) {
            DimBlendCarwash.LOGGER.warn("Grime sprite source texture {} not found", file);
            return;
        }
        int size;
        int[] frame;
        try (InputStream in = resource.open(); NativeImage image = NativeImage.read(in)) {
            // 原版为 16x16；动画贴图只取第一帧
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
        for (int level = 1; level <= levels; level++) {
            double remove = GrimeMask.ladderFraction(removeFrom, removeTo, level, levels);
            for (int i = 0; i < variants; i++) {
                ResourceLocation id = spriteId(prefix, level, i);
                // 种子只由精灵名决定：重载资源图案不变
                int[] masked = GrimeMask.apply(frame, size, size, id.hashCode() * 31L, remove, clear);
                int frameSize = size;
                output.add(id, loader -> toContents(id, masked, frameSize));
            }
        }
    }

    /** 资源栈按优先级从低到高：取 vanilla 包那份，找不到再退回最底层。 */
    private static Resource vanillaResource(List<Resource> stack) {
        for (Resource resource : stack) {
            if (VANILLA_PACK_ID.equals(resource.sourcePackId())) {
                return resource;
            }
        }
        return stack.isEmpty() ? null : stack.get(0);
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
