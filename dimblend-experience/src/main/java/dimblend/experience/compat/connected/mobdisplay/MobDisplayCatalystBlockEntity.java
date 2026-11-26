package dimblend.experience.compat.connected.mobdisplay;

import java.util.Optional;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 生物展示催化剂的方块实体：存整只 {@code creatorsword:capture_box}（外壳与其内的生物数据都在里面），
 * 客户端再按盒子内容缓存一个"只用来画"的 {@link Entity}。
 *
 * <p><b>为什么存整只盒子而不是只抽生物 id/nbt</b>：取回时要原样还给玩家（附魔、名字、耐久都不能丢），
 * 而展示只需要读三个约定键——{@code HasCapturedEntity} / {@code CapturedEntityType} /
 * {@code CapturedEntityNbt}（{@code creatorsword} 的格式，写在 {@link DataComponents#CUSTOM_DATA} 里）。
 * 因此本类对 {@code creatorsword} 零编译期依赖，模组缺席时只是画不出东西。</p>
 *
 * <p><b>客户端实体只重建一次</b>：每次 {@code clientTick} 比较盒子内容，变了才重建（
 * {@code EntityType.byString → create(level) → load(nbt)}），并复位 {@code hurtTime/deathTime}、
 * 清火、隐藏自定义名字——保证展示的是"刚拍下来那一刻"的干净生物。</p>
 *
 * <p><b>必须同时覆盖 {@code getUpdateTag} 与 {@code getUpdatePacket}（2026-11-26 修复）</b>：
 * 原版 {@code BlockEntity#getUpdatePacket} 默认 {@code return null}，而
 * {@code ChunkHolder#broadcastBlockEntity} 见 null 就直接不发包——只写 {@code sync()} 的话
 * 玩家放进盒子后客户端永远拿不到那份 {@code StoredBox}，{@code clientTick} 遂把
 * {@code displayEntity} 置 null，方块里看不到生物（重启/重进区块走存档加载才看得到）。</p>
 */
public final class MobDisplayCatalystBlockEntity extends BlockEntity {

    /** 盒子的存档键。 */
    private static final String NBT_STORED_BOX = "StoredBox";

    private ItemStack storedBox = ItemStack.EMPTY;

    /** 客户端：当前画的那只生物，以及它是按哪只盒子建出来的。 */
    @Nullable
    private Entity displayEntity;
    private ItemStack displayBuiltFor = ItemStack.EMPTY;

    public MobDisplayCatalystBlockEntity(BlockPos pos, BlockState state) {
        super(MobDisplayCatalystBlocks.MOB_DISPLAY_CATALYST_ENTITY.get(), pos, state);
    }

    /** 里面装的抓捕盒（空 = 没东西，理论上不该出现：空的就是 empty_fan_catalyst 了）。 */
    public ItemStack storedBox() {
        return storedBox;
    }

    /** 服务端：装进一只盒子，并广播给客户端（渲染器只看同步过去的盒子）。 */
    public void setStoredBox(ItemStack box) {
        storedBox = box.isEmpty() ? ItemStack.EMPTY : box.copyWithCount(1);
        displayBuiltFor = ItemStack.EMPTY;
        setChanged();
        sync();
    }

    /** 服务端：取回/换回时清空（避免 {@code onRemove} 再掉一次）。 */
    public void clearStoredBox() {
        setStoredBox(ItemStack.EMPTY);
    }

    private void sync() {
        Level level = getLevel();
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(getBlockPos(), getBlockState(), getBlockState(), Block.UPDATE_ALL);
        }
    }

    // --- 客户端渲染态 ---

    /** 当前要画的生物（客户端）；没有/重建失败时为 null。 */
    @Nullable
    public Entity displayEntity() {
        return displayEntity;
    }

    /** 客户端每 tick：盒子变了就重建展示实体，没变就只推进 {@code tickCount} 让动画动起来。 */
    public void clientTick() {
        if (storedBox.isEmpty()) {
            displayEntity = null;
            displayBuiltFor = ItemStack.EMPTY;
            return;
        }
        if (displayEntity != null && ItemStack.matches(storedBox, displayBuiltFor)) {
            displayEntity.tickCount++;
            return;
        }
        displayBuiltFor = storedBox.copy();
        displayEntity = createDisplayEntity(storedBox);
        if (displayEntity != null) {
            displayEntity.tickCount = 0;
            if (displayEntity instanceof LivingEntity living) {
                living.hurtTime = 0;
                living.deathTime = 0;
            }
            displayEntity.clearFire();
            displayEntity.setCustomNameVisible(false);
        }
    }

    @Nullable
    private Entity createDisplayEntity(ItemStack box) {
        Level level = getLevel();
        if (level == null) {
            return null;
        }
        CustomData data = box.get(DataComponents.CUSTOM_DATA);
        if (data == null) {
            return null;
        }
        CompoundTag tag = data.copyTag();
        if (!MobDisplayCatalystRules.hasCapturedEntity(tag)) {
            return null;
        }
        String typeId = MobDisplayCatalystRules.capturedEntityTypeId(tag);
        Optional<EntityType<?>> type = EntityType.byString(typeId);
        if (type.isEmpty()) {
            return null;
        }
        Entity entity = type.get().create(level);
        if (entity == null) {
            return null;
        }
        CompoundTag captured = MobDisplayCatalystRules.capturedEntityTag(tag);
        if (captured != null) {
            try {
                entity.load(captured);
            } catch (RuntimeException ignored) {
                // 生物 nbt 与当前版本不兼容（换了模组/换了实体注册）：保留类型，丢掉数据，别把渲染带崩
            }
        }
        return entity;
    }

    // --- 存档与同步 ---

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (!storedBox.isEmpty()) {
            tag.put(NBT_STORED_BOX, storedBox.save(registries));
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        storedBox = tag.contains(NBT_STORED_BOX)
                ? ItemStack.parse(registries, tag.getCompound(NBT_STORED_BOX)).orElse(ItemStack.EMPTY)
                : ItemStack.EMPTY;
        // 客户端/服务端都可能走到这里（客户端是 handleUpdateTag / onDataPacket）：把展示缓存作废，
        // 下一次 clientTick 必定按新盒子重建，免得"同步来的盒子恰好和本地缓存相等"时继续画旧的。
        displayEntity = null;
        displayBuiltFor = ItemStack.EMPTY;
    }

    /** 客户端拿到的同步包：整只盒子（渲染器需要它的 nbt 来建展示实体）。 */
    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return saveWithoutMetadata(registries);
    }

    /**
     * 同步包本体：**必须在方块更新时真的把包发出去**。
     *
     * <p>原版 {@code BlockEntity#getUpdatePacket()} 默认返回 {@code null}，而
     * {@code ChunkHolder#broadcastBlockEntity}（{@code ChunkHolder.java:213-221}）判断
     * {@code if (packet != null) broadcast(...)}——只写 {@code getUpdateTag} 的话
     * {@code setStoredBox → sync()} 这条路一条包都不会发，客户端 {@code storedBox} 永远是空，
     * {@code clientTick} 就把 {@code displayEntity} 清掉了。这里要的语义正是"放进盒子立刻看见生物"，
     * 所以必须自己造包（{@code create(this)} 内部即 {@code getUpdateTag(level.registryAccess())}）。</p>
     */
    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}
