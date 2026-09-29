package dimblend.carwash.chassis;

import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BehaviourType;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import dimblend.blocks.api.CreativeCopycatHiding;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 车架脏值，挂在伪装方块 BE（Create {@link SmartBlockEntity}）上的行为：
 * 随 BE 存盘，外观档位变化时经 Create 的 {@code sendData} 同步客户端，客户端收到后重建网格。
 *
 * <p>伪装方块的 BE 不 tick（Create {@code CopycatBlock#getTicker} 返回 null），行为只在
 * 首次读 NBT 时由 {@link ChassisBehaviourBinding} 挂上；从未读过 NBT 的新 BE 由
 * {@link #obtain} 按需补挂。</p>
 */
public class ChassisGrimeBehaviour extends BlockEntityBehaviour {

    public static final BehaviourType<ChassisGrimeBehaviour> TYPE = new BehaviourType<>("dimblend_carwash_grime");

    private static final String DIRT_KEY = "DimBlendCarwashDirt";
    private static final String VARIANT_KEY = "DimBlendCarwashVariant";

    private int dirt;
    private int variant;
    /** 网格构建线程读取的不可变快照。 */
    private volatile ChassisGrimeVisual visual = ChassisGrimeVisual.CLEAN;

    public ChassisGrimeBehaviour(SmartBlockEntity be) {
        super(be);
    }

    @Override
    public BehaviourType<?> getType() {
        return TYPE;
    }

    /**
     * 网格构建线程用：取该位置车架的脏污快照，没有行为即干净。
     * 行为表可能正被主线程首次读包时补挂（非线程安全的数组 map），读失败按干净处理，下次重建再取。
     */
    public static ChassisGrimeVisual visualAt(BlockGetter level, BlockPos pos) {
        try {
            // 隐藏车架不渲染脏污（泥与上方碎石一并消失）；隐藏期脏值冻结见 changeDirt
            if (CreativeCopycatHiding.isHidden(level, pos)) {
                return ChassisGrimeVisual.CLEAN;
            }
            ChassisGrimeBehaviour grime = BlockEntityBehaviour.get(level, pos, TYPE);
            return grime == null ? ChassisGrimeVisual.CLEAN : grime.visual;
        } catch (RuntimeException e) {
            return ChassisGrimeVisual.CLEAN;
        }
    }

    /** 取行为，没有则补挂（服务端改值前调用）。 */
    public static ChassisGrimeBehaviour obtain(SmartBlockEntity be) {
        ChassisGrimeBehaviour existing = be.getBehaviour(TYPE);
        if (existing != null) {
            return existing;
        }
        ChassisGrimeBehaviour created = new ChassisGrimeBehaviour(be);
        be.attachBehaviourLate(created);
        return created;
    }

    public int dirt() {
        return dirt;
    }

    public ChassisGrimeVisual visual() {
        return visual;
    }

    /**
     * 服务端：改变脏值（夹在 0–511）。外观档位变化时重掷整个贴图变体字节（泥图+碎石图一起换）；
     * 档位不变但碎石激活期间弄脏时只重掷高 4 位（换碎石图，泥图保持档位内的那张）。
     * 可见快照变化时经 Create 的 {@code sendData} 同步客户端，客户端收到后重建网格；其余情况只标记存盘。
     */
    public void changeDirt(int delta, RandomSource random) {
        Level level = blockEntity.getLevel();
        // 隐藏车架脏值冻结：隐藏期间 dirty 值不增长（清洗/雨洗不受影响）。
        // 判定走 dimblend-blocks 的 BE 隐藏标记（不动方块状态，Sable 子关卡无物理扰动）。
        if (delta > 0 && level != null
                && CreativeCopycatHiding.isHidden(level, blockEntity.getBlockPos())) {
            return;
        }
        int next = ChassisGrimeRules.clampDirt(dirt + delta);
        if (next == dirt) {
            return;
        }
        if (ChassisGrimeRules.dirtLevel(next) != ChassisGrimeRules.dirtLevel(dirt)) {
            variant = random.nextInt(256);
        } else if (delta > 0 && ChassisGrimeRules.hasGravel(next)) {
            // 只换碎石图：泥图随档位走，不随每次弄脏闪变
            variant = (variant & 0x0F) | (random.nextInt(16) << 4);
        }
        dirt = next;
        ChassisGrimeVisual previous = visual;
        visual = ChassisGrimeVisual.of(dirt, variant);
        // 只标区块待存盘：脏值不影响比较器输出，不走 setChanged 的邻居通知
        if (level != null) {
            level.blockEntityChanged(blockEntity.getBlockPos());
        }
        if (!previous.equals(visual)) {
            blockEntity.sendData();
        }
    }

    @Override
    public void write(CompoundTag nbt, HolderLookup.Provider registries, boolean clientPacket) {
        if (dirt > 0) {
            nbt.putInt(DIRT_KEY, dirt);
            nbt.putInt(VARIANT_KEY, variant);
        }
    }

    @Override
    public void read(CompoundTag nbt, HolderLookup.Provider registries, boolean clientPacket) {
        dirt = ChassisGrimeRules.clampDirt(nbt.getInt(DIRT_KEY));
        variant = nbt.getInt(VARIANT_KEY) & 0xFF;
        ChassisGrimeVisual previous = visual;
        visual = ChassisGrimeVisual.of(dirt, variant);
        if (clientPacket && !previous.equals(visual)) {
            redraw();
        }
    }

    /** 同 Create 伪装方块换材质后的重绘：刷新模型数据并标脏所在区段。 */
    private void redraw() {
        if (!blockEntity.isVirtual()) {
            blockEntity.requestModelDataUpdate();
        }
        Level level = blockEntity.getLevel();
        if (level != null) {
            BlockState state = blockEntity.getBlockState();
            level.sendBlockUpdated(blockEntity.getBlockPos(), state, state, Block.UPDATE_KNOWN_SHAPE);
        }
    }
}
