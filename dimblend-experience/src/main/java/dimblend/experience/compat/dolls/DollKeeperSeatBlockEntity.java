package dimblend.experience.compat.dolls;

import com.simibubi.create.content.logistics.stockTicker.StockTickerBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** Full item components persist and sync by the normal block entity protocol, including Sable assembly NBT. */
public final class DollKeeperSeatBlockEntity extends BlockEntity {
    private ItemStack doll = ItemStack.EMPTY;
    private int checkDelay;

    public DollKeeperSeatBlockEntity(BlockPos pos, BlockState state) {
        super(DollKeeperSeats.BLOCK_ENTITY.get(), pos, state);
    }

    public ItemStack doll() {
        return doll.copy();
    }

    public void install(ItemStack stack) {
        doll = stack.copyWithCount(1);
        setChanged();
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
        }
    }

    /** Creative destruction has no vanilla loot pass. Consume the stored item before spawning it. */
    public ItemStack takeDoll() {
        ItemStack result = doll;
        doll = ItemStack.EMPTY;
        setChanged();
        return result;
    }

    public BlockPos tickerPos() {
        return worldPosition.relative(getBlockState().getValue(DollKeeperSeatBlock.FACING));
    }

    public boolean isValidKeeperFor(BlockPos ticker) {
        return !isRemoved() && !doll.isEmpty() && VanillaDollItems.contains(doll) && level != null
                && tickerPos().equals(ticker)
                && ticker.equals(DollStockKeeperInteraction.uniqueTicker(level, worldPosition));
    }

    public void checkTicker(Level level) {
        BlockPos ticker = tickerPos();
        // An unloaded neighbour is unknown, never evidence of removal. Do not request a chunk.
        if (!level.isClientSide && !isRemoved() && level.getBlockEntity(worldPosition) == this && level.hasChunkAt(ticker)
                && !(level.getBlockState(ticker).getBlock() instanceof StockTickerBlock)) {
            level.destroyBlock(worldPosition, true);
        }
    }

    public static void tick(Level level, BlockPos pos, BlockState state, DollKeeperSeatBlockEntity keeper) {
        if (--keeper.checkDelay <= 0) {
            keeper.checkDelay = 20;
            keeper.checkTicker(level);
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (!doll.isEmpty()) {
            tag.put("Doll", doll.save(registries));
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        doll = ItemStack.parseOptional(registries, tag.getCompound("Doll"));
        if (!doll.isEmpty()) {
            doll.setCount(1);
        }
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return saveWithoutMetadata(registries);
    }

    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}
