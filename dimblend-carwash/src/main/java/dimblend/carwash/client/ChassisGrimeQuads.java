package dimblend.carwash.client;

import dimblend.carwash.chassis.ChassisGrimeRules;
import dimblend.carwash.chassis.ChassisGrimeVisual;
import net.minecraft.client.renderer.FaceInfo;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.Direction;
import net.neoforged.neoforge.client.model.IQuadTransformer;
import net.neoforged.neoforge.client.model.pipeline.QuadBakingVertexConsumer;

import java.util.List;

/**
 * 脏污贴层的四边形构造。UV 按方块内坐标投影（侧面 v = 1 - y），因此“去掉下半/上半”
 * 由贴图本身的透明半边实现，与几何形状无关。
 */
final class ChassisGrimeQuads {

    private static final Direction[] SIDES = {Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST};

    /**
     * 车架本体的泥：复制原模型的每个面（位置与顶点顺序不变，避免与底层材质 z-fighting），
     * 只改贴图与 UV。顶/底面用整张，四个侧面用去下半的贴图。
     */
    static void addDirt(List<BakedQuad> out, List<BakedQuad> geometry, ChassisGrimeVisual visual,
            ChassisGrimeSprites.Resolved sprites) {
        for (BakedQuad quad : geometry) {
            Direction face = quad.getDirection();
            TextureAtlasSprite[] set = face.getAxis().isHorizontal() ? sprites.dirtUpper() : sprites.dirtFull();
            for (int layer = 1; layer <= visual.dirtLayers(); layer++) {
                out.add(retexture(quad, set[spriteIndex(visual.dirtVariant(layer), face)]));
            }
        }
    }

    /**
     * 上方一格（y+1）的碎石：上方为实心不透明方块时贴其四个侧面与顶面（整张），
     * 否则贴该格的四个侧面（去上半）。
     */
    static void addGravel(List<BakedQuad> out, ChassisGrimeVisual visual, boolean aboveSolid,
            ChassisGrimeSprites.Resolved sprites) {
        if (visual.gravelLayers() == 0) {
            return;
        }
        TextureAtlasSprite[] sideSet = aboveSolid ? sprites.gravelFull() : sprites.gravelLower();
        QuadBakingVertexConsumer baker = new QuadBakingVertexConsumer();
        for (int layer = 1; layer <= visual.gravelLayers(); layer++) {
            int variant = visual.gravelVariant(layer);
            for (Direction side : SIDES) {
                out.add(bakeAboveFace(baker, side, sideSet[spriteIndex(variant, side)]));
            }
            if (aboveSolid) {
                out.add(bakeAboveFace(baker, Direction.UP, sprites.gravelFull()[spriteIndex(variant, Direction.UP)]));
            }
        }
    }

    /** 同一层内各面错开取图，免得六个面图案一样。 */
    private static int spriteIndex(int variant, Direction face) {
        return (variant + face.get3DDataValue() * 5) & (ChassisGrimeRules.VARIANTS_PER_SET - 1);
    }

    private static BakedQuad retexture(BakedQuad quad, TextureAtlasSprite sprite) {
        int[] vertices = quad.getVertices().clone();
        Direction face = quad.getDirection();
        for (int i = 0; i < 4; i++) {
            int base = i * IQuadTransformer.STRIDE;
            float x = Float.intBitsToFloat(vertices[base + IQuadTransformer.POSITION]);
            float y = Float.intBitsToFloat(vertices[base + IQuadTransformer.POSITION + 1]);
            float z = Float.intBitsToFloat(vertices[base + IQuadTransformer.POSITION + 2]);
            vertices[base + IQuadTransformer.COLOR] = -1;
            vertices[base + IQuadTransformer.UV0] = Float.floatToRawIntBits(sprite.getU(projectU(face, x, y, z)));
            vertices[base + IQuadTransformer.UV0 + 1] = Float.floatToRawIntBits(sprite.getV(projectV(face, x, y, z)));
            vertices[base + IQuadTransformer.UV2] = 0;
        }
        return new BakedQuad(vertices, -1, face, sprite, quad.isShade(), quad.hasAmbientOcclusion());
    }

    /** 上方一格的整面，顶点顺序同原版 FaceBakery（与该格方块自身的面重合时不打架）。 */
    private static BakedQuad bakeAboveFace(QuadBakingVertexConsumer baker, Direction face, TextureAtlasSprite sprite) {
        float[] bounds = new float[6];
        bounds[FaceInfo.Constants.MIN_X] = 0.0F;
        bounds[FaceInfo.Constants.MAX_X] = 1.0F;
        bounds[FaceInfo.Constants.MIN_Y] = 1.0F;
        bounds[FaceInfo.Constants.MAX_Y] = 2.0F;
        bounds[FaceInfo.Constants.MIN_Z] = 0.0F;
        bounds[FaceInfo.Constants.MAX_Z] = 1.0F;
        FaceInfo faceInfo = FaceInfo.fromFacing(face);
        baker.setSprite(sprite);
        baker.setDirection(face);
        baker.setTintIndex(-1);
        baker.setShade(true);
        baker.setHasAmbientOcclusion(true);
        for (int i = 0; i < 4; i++) {
            FaceInfo.VertexInfo info = faceInfo.getVertexInfo(i);
            float x = bounds[info.xFace];
            float y = bounds[info.yFace];
            float z = bounds[info.zFace];
            baker.addVertex(x, y, z)
                    .setColor(-1)
                    .setUv(sprite.getU(projectU(face, x, y - 1.0F, z)), sprite.getV(projectV(face, x, y - 1.0F, z)))
                    .setUv2(0, 0)
                    .setNormal(face.getStepX(), face.getStepY(), face.getStepZ());
        }
        return baker.bakeQuad();
    }

    private static float projectU(Direction face, float x, float y, float z) {
        float u = switch (face) {
            case UP, DOWN, SOUTH -> x;
            case NORTH -> 1.0F - x;
            case WEST -> z;
            case EAST -> 1.0F - z;
        };
        return clamp01(u);
    }

    private static float projectV(Direction face, float x, float y, float z) {
        float v = switch (face) {
            case UP -> z;
            case DOWN -> 1.0F - z;
            case NORTH, SOUTH, WEST, EAST -> 1.0F - y;
        };
        return clamp01(v);
    }

    private static float clamp01(float value) {
        return Math.max(0.0F, Math.min(1.0F, value));
    }

    private ChassisGrimeQuads() {
    }
}
