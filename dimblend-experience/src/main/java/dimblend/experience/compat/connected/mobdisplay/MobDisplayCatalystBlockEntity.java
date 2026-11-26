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
import net.minecraft.world.phys.Vec3;

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
 * <p><b>展示实体必须每 tick 锁死朝向与插值历史（2026-11-27 修复"生物高速旋转/剧烈左右摆动"）</b>：
 * 原版 {@code Entity#load(CompoundTag)}（{@code Entity.java:1749-1751}）只
 * {@code setOldPosAndRot()}（同步 {@code yRotO}/{@code xRotO}）再
 * {@code setYHeadRot(getYRot())} / {@code setYBodyRot(getYRot())}，<b>不会</b>同步
 * {@code yBodyRotO}/{@code yHeadRotO}——于是 {@code yBodyRotO == 0} 而 {@code yBodyRot == 175}（抓捕朝向）。
 * 本方块实体只自增 {@code tickCount}、从不调 {@code Entity#tick()}，那两个 {@code O} 字段永远停在初值，
 * 而渲染器把实时 {@code partialTick}（每 tick {@code 0 → 1} 的锯齿）传给
 * {@code LivingEntityRenderer#render}，其 {@code Mth.rotLerp(partialTick, yBodyRotO, yBodyRot)}
 * （{@code LivingEntityRenderer.java:57-58}）就每秒 20 次在 {@code 0°} 与抓捕朝向之间狂甩，
 * 肉眼即"高速旋转/左右摆动"。修法：建实体后与每 tick 都走 {@link #lockDisplayPose(Entity)}，
 * 把所有朝向/插值历史字段（含 {@code walkAnimation}/{@code attackAnim}）全部归零。</p>
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
            // 每 tick 兜底再锁一次：只要某个字段被别处写过（例如某个 renderer/model 顺手改了朝向），
            // 下一 tick 立刻归零，绝不会累积成"0° ⇄ 抓捕朝向"的锯齿。
            lockDisplayPose(displayEntity);
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
            lockDisplayPose(displayEntity);
        }
    }

    /**
     * 把展示实体的朝向、位移速度与"上一 tick"插值历史字段全部归零并锁定（纯姿态操作，不含 AI/物理）。
     *
     * <p><b>为什么必须做</b>：{@code Entity#load} 只同步了 {@code yRotO}/{@code xRotO}，
     * {@code LivingEntity} 的 {@code yBodyRotO}/{@code yHeadRotO} 仍是 {@code 0.0F}，而
     * {@code yBodyRot}/{@code yHeadRot} 被设成抓捕瞬间的朝向。本实体不跑 {@code Entity#tick()}，
     * 这些 {@code O} 字段永远不会被推进，渲染器的 {@code Mth.rotLerp(partialTick, …)} 于是逐个
     * partialTick 帧在 {@code 0} 和抓捕朝向之间插值——用户看到的就是"高速旋转/剧烈左右摆动"。</p>
     *
     * <p>归零后 {@code yRot == yRotO == 0}、{@code yBodyRot == yBodyRotO == 0}、
     * {@code yHeadRot == yHeadRotO == 0}，{@code rotLerp} 对任何 partialTick 都恒返回 {@code 0}，
     * 朝向完全静止；{@code tickCount} 仍逐 tick 自增，所以 {@code getBob}（待机呼吸/翅膀微动，
     * {@code LivingEntityRenderer.java:214-216}）依旧平滑。</p>
     *
     * <p>渲染器每帧也会再调一次（见 {@code MobDisplayCatalystRenderer#render}），双保险。</p>
     *
     * <p><b>刻意没动</b>：{@code swimAmount}/{@code swimAmountO}、{@code animStep}/{@code animStepO}、
     * {@code run}/{@code oRun} 这些同为"当前值 + O 历史值"的字段是 {@code private}/{@code protected}
     * （本包无权写），但它们只在 {@code LivingEntity#tick} / {@code updateSwimming} 里推进、
     * 也不进 NBT——{@code Entity#load} 之后仍是构造期的 {@code 0}，所以不贡献抖动。
     * {@code tickCount} 也刻意保留自增：它是 {@code getBob}/{@code ageInTicks} 的时间轴，归零反而会让动画卡住。</p>
     */
    public static void lockDisplayPose(Entity entity) {
        entity.setDeltaMovement(Vec3.ZERO);
        entity.setYRot(0.0F);
        entity.yRotO = 0.0F;
        entity.setXRot(0.0F);
        entity.xRotO = 0.0F;
        entity.setYHeadRot(0.0F);
        entity.setYBodyRot(0.0F);
        if (entity instanceof LivingEntity living) {
            living.yBodyRot = 0.0F;
            living.yBodyRotO = 0.0F;
            living.yHeadRot = 0.0F;
            living.yHeadRotO = 0.0F;
            living.oAttackAnim = 0.0F;
            living.attackAnim = 0.0F;
            living.hurtTime = 0;
            living.deathTime = 0;
            // WalkAnimationState 的 speed(float) 在 speedOld 与 speed 之间插值（WalkAnimationState.java:24-26），
            // 所以必须先 setSpeed(0) 再 update(0, 1)——update 会把 speedOld 也拉成 0；只 setSpeed 不够。
            living.walkAnimation.setSpeed(0.0F);
            living.walkAnimation.update(0.0F, 1.0F);
        }
        // 最后同步"上一 tick 的位置/朝向"：渲染器若用 xOld/xo 做帧间插值也拿不到位移抖动。
        entity.setOldPosAndRot();
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
