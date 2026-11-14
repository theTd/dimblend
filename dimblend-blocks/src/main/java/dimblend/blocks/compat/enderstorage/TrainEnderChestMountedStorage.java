package dimblend.blocks.compat.enderstorage;

import org.jetbrains.annotations.Nullable;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.simibubi.create.api.contraption.storage.item.MountedItemStorage;
import com.simibubi.create.content.contraptions.Contraption;
import com.simibubi.create.foundation.utility.CreateLang;

import codechicken.enderstorage.api.Frequency;
import codechicken.enderstorage.config.EnderStorageConfig;
import codechicken.enderstorage.manager.EnderStorageManager;
import codechicken.enderstorage.storage.EnderItemStorage;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.wrapper.InvWrapper;

/**
 * 列车末影物流箱在 Create 动态装置上的挂载存储实现（对齐 {@code createenderstorage}，
 * 绑定 {@link TrainEnderItemStorage#TYPE} 通道）。
 */
public class TrainEnderChestMountedStorage extends MountedItemStorage {

    public static final MapCodec<TrainEnderChestMountedStorage> CODEC = RecordCodecBuilder.mapCodec(inst -> inst.group(
            Frequency.CODEC.fieldOf("chest_frequency").forGetter(TrainEnderChestMountedStorage::getFrequency),
            Codec.INT.fieldOf("rotation").forGetter(TrainEnderChestMountedStorage::getRotation))
            .apply(inst, TrainEnderChestMountedStorage::new));

    private final Frequency frequency;
    private final int rotation;

    @Nullable
    private IItemHandler itemHandler;

    @Nullable
    private EnderItemStorage storage;

    public TrainEnderChestMountedStorage(Frequency frequency, int rotation) {
        super(TrainEnderChestCreateCompat.TRAIN_ENDER_CHEST_MOUNTED_STORAGE.get());
        this.frequency = frequency;
        this.rotation = rotation;
    }

    public Frequency getFrequency() {
        return this.frequency;
    }

    public int getRotation() {
        return this.rotation;
    }

    public IItemHandler getItemHandler() {
        if (this.itemHandler == null) {
            this.itemHandler = new InvWrapper(this.getStorage());
        }
        return this.itemHandler;
    }

    public EnderItemStorage getStorage() {
        if (this.storage == null) {
            if (TrainEnderChests.ISOLATED_TRAIN_CHANNEL) {
                this.storage = EnderStorageManager.instance(false)
                        .getStorage(this.frequency, TrainEnderItemStorage.TYPE);
            } else {
                this.storage = EnderStorageManager.instance(false)
                        .getStorage(this.frequency, EnderItemStorage.TYPE);
            }
        }
        return this.storage;
    }

    @Override
    public void unmount(Level level, BlockState blockState, BlockPos blockPos, @Nullable BlockEntity blockEntity) {
        if (!(blockEntity instanceof TrainEnderChestBlockEntity enderChest)) {
            return;
        }
        if (level instanceof ServerLevel serverLevel) {
            ClientboundBlockUpdatePacket packet = new ClientboundBlockUpdatePacket(blockPos, blockState);
            for (ServerPlayer player : serverLevel.getChunkSource().chunkMap.getPlayers(new ChunkPos(blockPos), false)) {
                player.connection.send(packet);
            }
        }
        enderChest.rotation = Math.floorMod(this.rotation, 4);
        enderChest.setFreq(this.frequency);
    }

    @Override
    public void setStackInSlot(int slot, ItemStack stack) {
        this.getStorage().setItem(slot, stack);
    }

    @Override
    public int getSlots() {
        return EnderItemStorage.sizes[EnderStorageConfig.storageSize];
    }

    @Override
    public ItemStack getStackInSlot(int slot) {
        return this.getStorage().getItem(slot);
    }

    @Override
    public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
        return this.getItemHandler().insertItem(slot, stack, simulate);
    }

    @Override
    public ItemStack extractItem(int slot, int amount, boolean simulate) {
        return this.getItemHandler().extractItem(slot, amount, simulate);
    }

    @Override
    public int getSlotLimit(int slot) {
        return this.getItemHandler().getSlotLimit(slot);
    }

    @Override
    public boolean isItemValid(int slot, ItemStack stack) {
        return this.getItemHandler().isItemValid(slot, stack);
    }

    @Override
    public boolean handleInteraction(ServerPlayer player, Contraption contraption,
            StructureTemplate.StructureBlockInfo info) {
        MutableComponent chestName = Component.translatable(info.state().getBlock().getDescriptionId());
        MutableComponent movingName = CreateLang.translateDirect("contraptions.moving_container", chestName);
        this.getStorage().openContainer(player, movingName);
        return true;
    }
}
