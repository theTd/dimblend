package dimblend.blocks.client;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import dimblend.blocks.DimBlendBlocks;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/** Rebuild once after a newly loaded copycat's model data is available, including command placement. */
@EventBusSubscriber(modid = DimBlendBlocks.MODID, value = Dist.CLIENT)
public final class CopycatLoadRenderRefresh {
    // Only current-world logical-client loads reach this client-thread queue.
    private static final Set<BlockEntity> PENDING = Collections.newSetFromMap(new IdentityHashMap<>());

    private CopycatLoadRenderRefresh() {}

    public static void schedule(BlockEntity blockEntity) {
        var level = blockEntity.getLevel();
        // A client mixin also runs on the integrated server; reject it before touching PENDING.
        if (level == null || !level.isClientSide() || level != Minecraft.getInstance().level) return;
        PENDING.add(blockEntity);
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        if (PENDING.isEmpty()) return;
        var pending = PENDING.toArray(BlockEntity[]::new);
        PENDING.clear();
        var currentLevel = Minecraft.getInstance().level;
        for (BlockEntity blockEntity : pending) {
            var level = blockEntity.getLevel();
            if (level == null || level != currentLevel || blockEntity.isRemoved()
                    || level.getBlockEntity(blockEntity.getBlockPos()) != blockEntity) continue;
            blockEntity.requestModelDataUpdate();
            var state = blockEntity.getBlockState();
            level.sendBlockUpdated(blockEntity.getBlockPos(), state, state, Block.UPDATE_KNOWN_SHAPE);
        }
    }
}
