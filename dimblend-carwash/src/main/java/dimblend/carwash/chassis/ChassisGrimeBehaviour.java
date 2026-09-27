package dimblend.carwash.chassis;

import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BehaviourType;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
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
 * 随 BE 存盘，经 Create 的 {@code sendData} 同步客户端，客户端收到后重建网格。
 *
 * <p>伪装方块的 BE 不 tick（Create {@code CopycatBlock#getTicker} 返回 null），行为只在
 * 首次读 NBT 时由 {@link ChassisBehaviourBinding} 挂上；从未读过 NBT 的新 BE 由
 * {@link #obtain} 按需补挂。</p>
 */
public class ChassisGrimeBehaviour extends BlockEntityBehaviour {

    public static final BehaviourType<ChassisGrimeBehaviour> TYPE = new BehaviourType<>("dimblend_carwash_grime");

    private static final String DIRT_KEY = "DimBlendCarwashDirt";
    private static final String VARIANTS_KEY = "DimBlendCarwashLayerVariants";

    private int dirt;
    private long layerVariants;
    /** 服务端瞬态：上次喷淋清洗生效的游戏刻（不存盘）。 */
    private long lastSprayWashTick;
    private boolean sprayWashed;
    /** 渲染线程读取的不可变快照。 */
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
     * 服务端：改变脏值（夹在 0–255）。新达到的 32 倍数层重掷随机图案；贴图层数变化时同步客户端。
     */
    public void changeDirt(int delta, RandomSource random) {
        int next = ChassisGrimeRules.clampDirt(dirt + delta);
        if (next == dirt) {
            return;
        }
        int oldLayers = ChassisGrimeRules.dirtLayers(dirt);
        int newLayers = ChassisGrimeRules.dirtLayers(next);
        for (int layer = oldLayers + 1; layer <= newLayers; layer++) {
            layerVariants = ChassisGrimeRules.withLayerVariant(layerVariants, layer, random.nextInt(256));
        }
        dirt = next;
        blockEntity.setChanged();
        if (newLayers != oldLayers) {
            visual = ChassisGrimeVisual.of(dirt, layerVariants);
            blockEntity.sendData();
        }
    }

    /** 服务端：喷淋冷却判定，通过则记下本刻。 */
    public boolean tryStartSprayWash(long gameTime) {
        if (sprayWashed && gameTime - lastSprayWashTick < ChassisGrimeRules.SPRAY_WASH_COOLDOWN_TICKS) {
            return false;
        }
        sprayWashed = true;
        lastSprayWashTick = gameTime;
        return true;
    }

    @Override
    public void write(CompoundTag nbt, HolderLookup.Provider registries, boolean clientPacket) {
        if (dirt > 0) {
            nbt.putInt(DIRT_KEY, dirt);
            nbt.putLong(VARIANTS_KEY, layerVariants);
        }
    }

    @Override
    public void read(CompoundTag nbt, HolderLookup.Provider registries, boolean clientPacket) {
        dirt = ChassisGrimeRules.clampDirt(nbt.getInt(DIRT_KEY));
        layerVariants = nbt.getLong(VARIANTS_KEY);
        ChassisGrimeVisual previous = visual;
        visual = ChassisGrimeVisual.of(dirt, layerVariants);
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
