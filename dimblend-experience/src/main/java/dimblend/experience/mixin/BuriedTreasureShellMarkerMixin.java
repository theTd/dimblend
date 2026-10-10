package dimblend.experience.mixin;

import dimblend.experience.treasure.TreasureShellMarker;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.structures.BuriedTreasurePieces;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A11 藏宝箱贝壳 X 标记（原版目标，无条件应用；开关与方块缺席降级见
 * {@link TreasureShellMarker}）。
 *
 * <p>注入点选在 {@code BuriedTreasurePiece.postProcess} 内 {@code createChest} 调用之后：
 * 该处只在宝箱成功生成的路径执行（扫不到石质基座时根本不会走到），且
 * {@code boundingBox} 恰好在此之前一行被重设为宝箱单格坐标（原版
 * {@code BuriedTreasurePieces}：先 {@code this.boundingBox = new BoundingBox(pos)} 再
 * {@code createChest(...)}），所以这里读 {@code getBoundingBox()} 即宝箱精确位置，
 * 无需本地变量捕获，也无需排除 Y=90 占位。</p>
 */
@Mixin(BuriedTreasurePieces.BuriedTreasurePiece.class)
public abstract class BuriedTreasureShellMarkerMixin {

    @Inject(method = "postProcess",
            at = @At(value = "INVOKE", shift = At.Shift.AFTER,
                    // 字节码里 this.createChest(...) 的 owner 是接收者静态类型
                    // BuriedTreasurePiece（方法本体在父类 StructurePiece）
                    target = "Lnet/minecraft/world/level/levelgen/structure/structures/BuriedTreasurePieces$BuriedTreasurePiece;"
                            + "createChest(Lnet/minecraft/world/level/ServerLevelAccessor;"
                            + "Lnet/minecraft/world/level/levelgen/structure/BoundingBox;"
                            + "Lnet/minecraft/util/RandomSource;Lnet/minecraft/core/BlockPos;"
                            + "Lnet/minecraft/resources/ResourceKey;"
                            + "Lnet/minecraft/world/level/block/state/BlockState;)Z"))
    private void dimblend$markShellX(WorldGenLevel level, StructureManager structureManager,
            ChunkGenerator chunkGenerator, RandomSource random, BoundingBox chunkBounds,
            ChunkPos chunkPos, BlockPos meetingPos, CallbackInfo ci) {
        BoundingBox chestBB = ((StructurePiece) (Object) this).getBoundingBox();
        TreasureShellMarker.mark(level, new BlockPos(chestBB.minX(), chestBB.minY(), chestBB.minZ()));
    }
}
