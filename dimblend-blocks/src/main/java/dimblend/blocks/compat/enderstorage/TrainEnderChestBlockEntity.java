package dimblend.blocks.compat.enderstorage;

import org.jetbrains.annotations.Nullable;

import codechicken.enderstorage.config.EnderStorageConfig;
import codechicken.enderstorage.manager.EnderStorageManager;
import codechicken.enderstorage.storage.EnderItemStorage;
import codechicken.enderstorage.tile.TileFrequencyOwner;
import codechicken.lib.data.MCDataInput;
import codechicken.lib.data.MCDataOutput;
import codechicken.lib.math.MathHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;
import net.neoforged.neoforge.items.wrapper.InvWrapper;

/**
 * 列车末影物流箱方块实体：直接继承 {@link TileFrequencyOwner}（避免 {@code TileEnderChest}
 * 硬编码 {@code ENDER_CHEST_TILE}），完整复刻 {@code TileEnderChest} 的频率同步、四向朝向、
 * 开合盖动画与音效、容器打开、红石比较器输出以及 {@link IItemHandler} 自动化接口。
 */
public class TrainEnderChestBlockEntity extends TileFrequencyOwner {

    public double a_lidAngle;
    public double b_lidAngle;
    public int c_numOpen;
    public int rotation;

    @Nullable
    private IItemHandler itemHandler;

    public TrainEnderChestBlockEntity(BlockPos pos, BlockState state) {
        super(TrainEnderChests.TRAIN_ENDER_CHEST_BE.get(), pos, state);
    }

    @Override
    public void tick() {
        super.tick();
        assert this.level != null;
        if (!this.level.isClientSide
                && (this.level.getGameTime() % 20L == 0L || this.c_numOpen != this.getStorage().getNumOpen())) {
            this.c_numOpen = this.getStorage().getNumOpen();
            this.level.blockEvent(this.getBlockPos(), this.getBlockState().getBlock(), 1, this.c_numOpen);
            this.level.updateNeighborsAt(this.worldPosition, this.getBlockState().getBlock());
        }
        this.b_lidAngle = this.a_lidAngle;
        this.a_lidAngle = MathHelper.approachLinear(this.a_lidAngle, this.c_numOpen > 0 ? 1.0 : 0.0, 0.1);
        if (this.b_lidAngle >= 0.5 && this.a_lidAngle < 0.5) {
            this.level.playSound(null, this.getBlockPos(),
                    EnderStorageConfig.useVanillaEnderChestSounds ? SoundEvents.ENDER_CHEST_CLOSE : SoundEvents.CHEST_CLOSE,
                    SoundSource.BLOCKS, 0.5f, this.level.random.nextFloat() * 0.1f + 0.9f);
        } else if (this.b_lidAngle == 0.0 && this.a_lidAngle > 0.0) {
            this.level.playSound(null, this.getBlockPos(),
                    EnderStorageConfig.useVanillaEnderChestSounds ? SoundEvents.ENDER_CHEST_OPEN : SoundEvents.CHEST_OPEN,
                    SoundSource.BLOCKS, 0.5f, this.level.random.nextFloat() * 0.1f + 0.9f);
        }
    }

    @Override
    public boolean triggerEvent(int id, int type) {
        if (id == 1) {
            this.c_numOpen = type;
            return true;
        }
        return false;
    }

    public double getRadianLidAngle(float frame) {
        double a = MathHelper.interpolate(this.b_lidAngle, this.a_lidAngle, frame);
        a = 1.0 - a;
        a = 1.0 - a * a * a;
        return a * 3.141593 * -0.5;
    }

    @Override
    public EnderItemStorage getStorage() {
        assert this.level != null;
        if (TrainEnderChests.ISOLATED_TRAIN_CHANNEL) {
            return EnderStorageManager.instance(this.level.isClientSide)
                    .getStorage(this.frequency, TrainEnderItemStorage.TYPE);
        }
        return EnderStorageManager.instance(this.level.isClientSide)
                .getStorage(this.frequency, EnderItemStorage.TYPE);
    }

    @Override
    public void onFrequencySet() {
        this.invalidateCapabilities();
        this.itemHandler = null;
    }

    @Override
    public void writeToPacket(MCDataOutput packet) {
        super.writeToPacket(packet);
        packet.writeByte(this.rotation);
    }

    @Override
    public void readFromPacket(MCDataInput packet) {
        super.readFromPacket(packet);
        this.rotation = packet.readUByte() & 3;
    }

    @Override
    public void onPlaced(@Nullable LivingEntity entity) {
        assert this.level != null;
        this.rotation = entity != null ? (int) Math.floor(entity.getYRot() * 4.0f / 360.0f + 2.5) & 3 : 0;
        this.onFrequencySet();
        if (!this.level.isClientSide) {
            this.sendUpdatePacket();
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putByte("rot", (byte) this.rotation);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        this.rotation = tag.getByte("rot") & 3;
    }

    @Override
    public boolean activate(Player player, int subHit, InteractionHand hand) {
        this.getStorage().openContainer((ServerPlayer) player,
                Component.translatable(this.getBlockState().getBlock().getDescriptionId()));
        return true;
    }

    @Override
    public boolean rotate() {
        assert this.level != null;
        if (!this.level.isClientSide) {
            this.rotation = (this.rotation + 1) % 4;
            this.sendUpdatePacket();
        }
        return true;
    }

    @Override
    public int comparatorOutput() {
        return ItemHandlerHelper.calcRedstoneFromInventory(this.getItemHandler());
    }

    public IItemHandler getItemHandler() {
        if (this.itemHandler == null) {
            this.itemHandler = new InvWrapper(this.getStorage());
        }
        return this.itemHandler;
    }
}
