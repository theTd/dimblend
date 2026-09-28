package dimblend.carwash.client;

import dimblend.carwash.DimBlendCarwash;
import dimblend.carwash.chassis.ChassisGrimeRules;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.Material;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.InventoryMenu;
import org.jetbrains.annotations.Nullable;

import java.util.function.Function;

/**
 * 镂空贴图（由 {@link GrimeSpriteSource} 生成，前缀与档数须与 {@code assets/minecraft/atlases/blocks.json} 一致），
 * 每次模型烘焙时从新图集解析一次。泥按外观档位分 7 档，碎石只有一档。
 */
public final class ChassisGrimeSprites {

    /** 泥，整张：车架顶面、底面。 */
    static final ResourceLocation DIRT_FULL = DimBlendCarwash.id("block/grime/dirt_full_");
    /** 泥，去下半：车架四个侧面。 */
    static final ResourceLocation DIRT_UPPER = DimBlendCarwash.id("block/grime/dirt_upper_");
    /** 碎石，整张：上方实心方块的侧面与顶面。 */
    static final ResourceLocation GRAVEL_FULL = DimBlendCarwash.id("block/grime/gravel_full_");
    /** 碎石，去上半：上方非实心格的四个侧面。 */
    static final ResourceLocation GRAVEL_LOWER = DimBlendCarwash.id("block/grime/gravel_lower_");

    /**
     * @param dirtFull  [档-1][变体]
     * @param dirtUpper [档-1][变体]
     */
    public record Resolved(TextureAtlasSprite[][] dirtFull, TextureAtlasSprite[][] dirtUpper,
            TextureAtlasSprite[] gravelFull, TextureAtlasSprite[] gravelLower) {
    }

    private static volatile Resolved resolved;

    /** 任一张缺失（图集未生成）则整体置空：不画贴层，而不是画紫黑缺失贴图。 */
    static void resolve(Function<Material, TextureAtlasSprite> textureGetter) {
        TextureAtlasSprite[][] dirtFull = new TextureAtlasSprite[ChassisGrimeRules.MAX_LEVEL][];
        TextureAtlasSprite[][] dirtUpper = new TextureAtlasSprite[ChassisGrimeRules.MAX_LEVEL][];
        boolean complete = true;
        for (int level = 1; level <= ChassisGrimeRules.MAX_LEVEL; level++) {
            dirtFull[level - 1] = resolveSet(textureGetter, DIRT_FULL, level);
            dirtUpper[level - 1] = resolveSet(textureGetter, DIRT_UPPER, level);
            complete &= dirtFull[level - 1] != null && dirtUpper[level - 1] != null;
        }
        TextureAtlasSprite[] gravelFull = resolveSet(textureGetter, GRAVEL_FULL, 1);
        TextureAtlasSprite[] gravelLower = resolveSet(textureGetter, GRAVEL_LOWER, 1);
        if (!complete || gravelFull == null || gravelLower == null) {
            DimBlendCarwash.LOGGER.warn("Chassis grime sprites missing from the block atlas; grime will not render");
            resolved = null;
            return;
        }
        resolved = new Resolved(dirtFull, dirtUpper, gravelFull, gravelLower);
    }

    @Nullable
    static Resolved get() {
        return resolved;
    }

    @Nullable
    private static TextureAtlasSprite[] resolveSet(Function<Material, TextureAtlasSprite> textureGetter,
            ResourceLocation prefix, int level) {
        TextureAtlasSprite[] sprites = new TextureAtlasSprite[ChassisGrimeRules.VARIANTS_PER_SET];
        for (int i = 0; i < sprites.length; i++) {
            TextureAtlasSprite sprite = textureGetter.apply(
                    new Material(InventoryMenu.BLOCK_ATLAS, GrimeSpriteSource.spriteId(prefix, level, i)));
            if (sprite.contents().name().equals(MissingTextureAtlasSprite.getLocation())) {
                return null;
            }
            sprites[i] = sprite;
        }
        return sprites;
    }

    private ChassisGrimeSprites() {
    }
}
