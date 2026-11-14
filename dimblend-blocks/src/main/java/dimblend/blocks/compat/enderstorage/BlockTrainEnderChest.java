package dimblend.blocks.compat.enderstorage;

import org.jetbrains.annotations.Nullable;

import com.google.common.collect.ImmutableSet;
import com.mojang.serialization.MapCodec;

import codechicken.enderstorage.block.BlockEnderStorage;
import codechicken.enderstorage.misc.EnderKnobSlot;
import codechicken.enderstorage.tile.TileFrequencyOwner;
import codechicken.lib.raytracer.IndexedVoxelShape;
import codechicken.lib.raytracer.MultiIndexedVoxelShape;
import codechicken.lib.raytracer.VoxelShapeCache;
import codechicken.lib.vec.Cuboid6;
import codechicken.lib.vec.Rotation;
import codechicken.lib.vec.Transformation;
import codechicken.lib.vec.Translation;
import codechicken.lib.vec.Vector3;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * 列车末影物流箱方块：完整复刻 {@code codechicken.enderstorage.block.BlockEnderChest} 的碰撞箱、
 * 顶部三枚染色垫片子碰撞箱（subHit 1..3）、正面锁扣子碰撞箱（subHit 4）、染色/上锁/掉落/比较器交互，
 * 并绑定自有 {@link TrainEnderChestBlockEntity}。
 */
public class BlockTrainEnderChest extends BlockEnderStorage {

    public static final MapCodec<BlockTrainEnderChest> CODEC = simpleCodec(BlockTrainEnderChest::new);

    private static final IndexedVoxelShape CHEST = new IndexedVoxelShape(
            VoxelShapeCache.getShape(new Cuboid6(0.0625, 0.0, 0.0625, 0.9375, 0.875, 0.9375)), 0);
    private static final IndexedVoxelShape[][] BUTTONS = new IndexedVoxelShape[4][3];
    private static final IndexedVoxelShape[] LATCH = new IndexedVoxelShape[4];
    private static final VoxelShape[][] SHAPES = new VoxelShape[4][2];
    public static final Transformation[] BUTTON_T = new Transformation[3];

    static {
        for (int button = 0; button < 3; ++button) {
            BUTTON_T[button] = new Translation(-0.1875 + 0.1875 * button, 0.875, 0.0);
        }
        for (int rot = 0; rot < 4; ++rot) {
            for (int button = 0; button < 3; ++button) {
                Cuboid6 cuboid = TileFrequencyOwner.SELECTION_BUTTON.copy();
                cuboid.apply(BUTTON_T[button]);
                cuboid.apply(new Translation(0.5, 0.0, 0.5));
                cuboid.apply(new Rotation((-90 * rot) * 0.017453292519943, Vector3.Y_POS)
                        .at(new Vector3(0.5, 0.0, 0.5)));
                BUTTONS[rot][button] = new IndexedVoxelShape(VoxelShapeCache.getShape(cuboid), button + 1);
            }
            LATCH[rot] = new IndexedVoxelShape(
                    VoxelShapeCache.getShape(new Cuboid6(new EnderKnobSlot(rot).getSelectionBB())), 4);
            for (int state = 0; state < 2; ++state) {
                ImmutableSet.Builder<IndexedVoxelShape> cuboids = ImmutableSet.builder();
                cuboids.add(CHEST);
                if (state == 0) {
                    cuboids.add(BUTTONS[rot]);
                    cuboids.add(LATCH[rot]);
                }
                SHAPES[rot][state] = new MultiIndexedVoxelShape(CHEST, cuboids.build());
            }
        }
    }

    public BlockTrainEnderChest(BlockBehaviour.Properties properties) {
        super(properties);
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter worldIn, BlockPos pos, CollisionContext context) {
        VoxelShape shape = CHEST;
        BlockEntity be = worldIn.getBlockEntity(pos);
        if (be instanceof TrainEnderChestBlockEntity tile) {
            shape = SHAPES[tile.rotation & 3][tile.getRadianLidAngle(0.0f) >= 0.0 ? 0 : 1];
        }
        return shape;
    }

    @Override
    @Nullable
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new TrainEnderChestBlockEntity(pos, state);
    }

    @Override
    @Nullable
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(
            Level level, BlockState state, BlockEntityType<T> type) {
        return createTickerHelper(type, TrainEnderChests.TRAIN_ENDER_CHEST_BE.get(),
                (lvl, pos, st, tile) -> tile.tick());
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }
}
