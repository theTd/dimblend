package dimblend.experience.chassis;

import com.simibubi.create.AllBlocks;
import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.ticket.SubLevelLoadingTicketType;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dimblend.api.CorridorRails;
import dimblend.experience.DimBlend;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Unit;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Small bounded batches on the server thread; no world mutation in a placement transaction. */
public final class ChassisConstruction {
    private static final TicketType<BlockPos> BUILD_TICKET = TicketType.create("dimblend_chassis", BlockPos::compareTo);
    private static final int TICKET_RADIUS = 3;
    private ChassisConstruction() {}

    public static boolean reserved(ServerLevel level, BlockPos center) {
        return ChassisBuildData.get(level).builds.stream()
                .anyMatch(build -> ChassisSite.bounds(build.center).intersects(ChassisSite.bounds(center)));
    }

    public static boolean start(ServerLevel level, BlockPos center, ChassisBlueprint blueprint) {
        var data = ChassisBuildData.get(level);
        if (data.builds.size() >= 8 || reserved(level, center)
                || !level.getBlockState(center).is(AllBlocks.TRAIN_CONTROLS.get())
                || !CorridorRails.isGeneratedTrack(level, center.below())
                || !ChassisSite.clear(level, center, Map.of(), true)) return false;
        // Resolve the complete block palette before consuming the controller.
        blueprint.cells().forEach(ChassisBlocks::state);
        data.builds.add(new ChassisBuildData.Build(center, blueprint));
        data.setDirty();
        level.getChunkSource().addRegionTicket(BUILD_TICKET, new ChunkPos(center), TICKET_RADIUS, center);
        level.removeBlock(center, false);
        return true;
    }

    public static void tick(ServerLevel level) {
        var data = ChassisBuildData.get(level);
        long deadline = System.nanoTime() + 2_000_000;
        for (var build : List.copyOf(data.builds)) {
            level.getChunkSource().addRegionTicket(BUILD_TICKET, new ChunkPos(build.center), TICKET_RADIUS, build.center);
            if (!ChassisSite.loaded(level, build.center)) continue;
            if (build.delay-- > 0) continue;
            build.delay = 5;
            Map<BlockPos, BlockState> placed = builtCells(build);
            if (!CorridorRails.isGeneratedTrack(level, build.center.below())
                    || !ChassisSite.clear(level, build.center, placed, false)) {
                cancel(level, data, build, placed);
                continue;
            }
            if (build.rows == ChassisBlueprint.LENGTH) {
                assemble(level, data, build, placed);
            } else {
                int row = build.rows;
                for (var cell : build.blueprint.cells()) {
                    if (cell.x() + 22 != row) continue;
                    BlockPos pos = build.center.offset(cell.x(), 0, cell.z());
                    level.setBlock(pos, ChassisBlocks.state(cell), Block.UPDATE_ALL);
                    ChassisBlocks.configureBogey(level, pos, cell.symbol());
                }
                build.rows++;
                data.setDirty();
                level.playSound(null, build.center.offset(row - 22, 0, 0), SoundEvents.ITEM_PICKUP,
                        SoundSource.BLOCKS, 0.35F, 1.0F + level.random.nextFloat() * 0.4F);
            }
            if (System.nanoTime() >= deadline) break;
        }
    }

    private static Map<BlockPos, BlockState> builtCells(ChassisBuildData.Build build) {
        Map<BlockPos, BlockState> placed = new HashMap<>();
        for (var cell : build.blueprint.cells()) {
            if (cell.x() + 22 < build.rows) placed.put(build.center.offset(cell.x(), 0, cell.z()), ChassisBlocks.state(cell));
        }
        return placed;
    }

    private static void assemble(ServerLevel level, ChassisBuildData data, ChassisBuildData.Build build,
            Map<BlockPos, BlockState> placed) {
        var center = build.center;
        // Explicit membership also assembles the separated side rails without gathering terrain or track.
        var car = SubLevelAssemblyHelper.assembleBlocks(level, center, placed.keySet(), new BoundingBox3i(
                center.getX() - 22, center.getY(), center.getZ() - 2,
                center.getX() + 22, center.getY(), center.getZ() + 2));
        if (car == null || car.isRemoved()) throw new IllegalStateException("Chassis assembly failed at " + center);
        SubLevelContainer.getContainer(level).addForceLoadTicket(car, SubLevelLoadingTicketType.COMMAND_FORCED, Unit.INSTANCE);
        finish(level, data, build);
    }

    private static void cancel(ServerLevel level, ChassisBuildData data, ChassisBuildData.Build build,
            Map<BlockPos, BlockState> placed) {
        placed.forEach((pos, state) -> {
            if (level.getBlockState(pos).equals(state)) level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        });
        Block.popResource(level, build.center, new ItemStack(AllBlocks.TRAIN_CONTROLS.asItem()));
        broadcast(level, "unsafe");
        finish(level, data, build);
        DimBlend.LOGGER.info("Chassis construction interrupted at {}: site obstructed", build.center);
    }

    private static void finish(ServerLevel level, ChassisBuildData data, ChassisBuildData.Build build) {
        data.builds.remove(build);
        data.setDirty();
        level.getChunkSource().removeRegionTicket(BUILD_TICKET, new ChunkPos(build.center), TICKET_RADIUS, build.center);
    }

    public static void broadcast(ServerLevel level, String message) {
        Component text = Component.translatable("message.dimblend_experience.chassis." + message);
        level.getServer().getPlayerList().getPlayers().forEach(player -> player.displayClientMessage(text, true));
    }
}
