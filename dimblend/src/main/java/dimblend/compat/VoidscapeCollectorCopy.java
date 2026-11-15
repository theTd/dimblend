package dimblend.compat;

import java.lang.reflect.Field;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ColorParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.fluids.capability.templates.FluidTank;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;

/** Lookup and server tick logic for the Voidscape collector ore-copy replacement. */
public final class VoidscapeCollectorCopy {
    private static volatile Handles cachedHandles;
    private static volatile boolean handlesResolved;

    private record Handles(Class<?> collectorClass, Field processTickField, Field fluidsField) {}

    private VoidscapeCollectorCopy() {}

    public static int costFor(ResourceLocation id) {
        if (id == null) {
            return 0;
        }
        if (id.equals(ResourceLocation.withDefaultNamespace("cobblestone"))) {
            return 1;
        }
        if (id.getNamespace().equals("create")
                && switch (id.getPath()) {
                    case "veridium", "asurine", "ochrum", "crimsite" -> true;
                    default -> false;
                }) {
            return 10;
        }
        return 0;
    }

    public static int costFor(Block block) {
        return costFor(BuiltInRegistries.BLOCK.getKey(block));
    }

    public static void tick(Level level, BlockPos pos, BlockEntity be) {
        if (!(level instanceof ServerLevel server) || level.hasNeighborSignal(pos)) {
            return;
        }
        Handles handles = handles();
        if (handles == null || !handles.collectorClass().isInstance(be)) {
            return;
        }
        int tick = readTick(handles, be) + 1;
        if (tick < 40) {
            writeTick(handles, be, tick);
            return;
        }
        writeTick(handles, be, 0);

        BlockState below = level.getBlockState(pos.below());
        int cost = costFor(BuiltInRegistries.BLOCK.getKey(below.getBlock()));
        Item outputItem = below.getBlock().asItem();
        if (cost == 0 || outputItem == Items.AIR) {
            return;
        }
        FluidTank tank = readTank(handles, be);
        if (tank == null || tank.drain(cost, IFluidHandler.FluidAction.SIMULATE).getAmount() < cost) {
            return;
        }
        tank.drain(cost, IFluidHandler.FluidAction.EXECUTE);
        be.setChanged();

        ItemStack stack = new ItemStack(outputItem, 1);
        for (Direction direction : Direction.values()) {
            BlockPos adj = pos.relative(direction);
            if (server.getChunkSource().getChunkNow(adj.getX() >> 4, adj.getZ() >> 4) == null) {
                continue;
            }
            IItemHandler handler =
                    level.getCapability(Capabilities.ItemHandler.BLOCK, adj, direction.getOpposite());
            if (handler != null) {
                stack = ItemHandlerHelper.insertItemStacked(handler, stack, false);
            }
            if (stack.isEmpty()) {
                break;
            }
        }
        if (!stack.isEmpty()) {
            ItemEntity item =
                    new ItemEntity(level, pos.getX() + 0.5D, pos.getY() + 1.05D, pos.getZ() + 0.5D, stack);
            item.setDeltaMovement(0.0D, 0.1D, 0.0D);
            item.setDefaultPickUpDelay();
            level.addFreshEntity(item);
        }
        server.sendParticles(
                ColorParticleOption.create(ParticleTypes.ENTITY_EFFECT, 0xFFFFFFFF),
                pos.getX() + 0.5D,
                pos.getY() + 1.05D,
                pos.getZ() + 0.5D,
                6,
                0.2D,
                0.15D,
                0.2D,
                0.02D);
    }

    private static int readTick(Handles handles, BlockEntity be) {
        try {
            return handles.processTickField().getInt(be);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return 0;
        }
    }

    private static void writeTick(Handles handles, BlockEntity be, int value) {
        try {
            handles.processTickField().setInt(be, value);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // Ignore when field access fails.
        }
    }

    private static FluidTank readTank(Handles handles, BlockEntity be) {
        try {
            return (FluidTank) handles.fluidsField().get(be);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return null;
        }
    }

    private static Handles handles() {
        if (handlesResolved) {
            return cachedHandles;
        }
        synchronized (VoidscapeCollectorCopy.class) {
            if (handlesResolved) {
                return cachedHandles;
            }
            try {
                Class<?> collectorClass =
                        Class.forName("tamaized.voidscape.block.entity.CollectorBlockEntity");
                Field processTickField = collectorClass.getDeclaredField("processTick");
                processTickField.setAccessible(true);
                Field fluidsField = collectorClass.getDeclaredField("fluids");
                fluidsField.setAccessible(true);
                cachedHandles = new Handles(collectorClass, processTickField, fluidsField);
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                cachedHandles = null;
            }
            handlesResolved = true;
            return cachedHandles;
        }
    }
}
