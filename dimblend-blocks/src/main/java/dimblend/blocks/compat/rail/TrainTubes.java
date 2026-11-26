package dimblend.blocks.compat.rail;

import dimblend.blocks.DimBlendBlocks;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 列车加压管道（{@code dimblend_blocks:train_pressured_tube}）总注册入口。
 *
 * <p>只在 {@code create && sable} 双在场时由 {@link DimBlendBlocks} 触达（本包内所有类都硬引用
 * Sable / Create 类型）。</p>
 *
 * <p>流体接口只在「朝高压水泵的那一面」暴露：普通机械泵看到的永远是 closed end，
 * 于是 Create 的 {@code FluidNetwork} 不会把它当管道、也不会喷粒子。</p>
 */
public final class TrainTubes {

    /** 唯一允许灌入/泵出的泵：消防模组的高压水泵。 */
    public static final ResourceLocation HIGH_PRESSURE_PUMP =
            ResourceLocation.fromNamespaceAndPath("createfirefightingadd", "high_pressure_pump");

    /** 创造模式玩家是否同样受「仅能放置于 Sable 上」限制（与末影物流箱同口径）。 */
    public static final boolean STRICT_SABLE_IN_CREATIVE = true;

    private static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, DimBlendBlocks.MODID);

    public static final DeferredBlock<TrainPressuredTubeBlock> TUBE =
            DimBlendBlocks.BLOCKS.register("train_pressured_tube",
                    () -> new TrainPressuredTubeBlock(tubeProperties()));

    public static final DeferredItem<TrainPressuredTubeItem> TUBE_ITEM =
            DimBlendBlocks.ITEMS.register("train_pressured_tube",
                    () -> new TrainPressuredTubeItem(TUBE.get(), new Item.Properties()));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<TrainPressuredTubeBlockEntity>> TUBE_BE =
            BLOCK_ENTITIES.register("train_pressured_tube",
                    () -> BlockEntityType.Builder.of(TrainPressuredTubeBlockEntity::new, TUBE.get()).build(null));

    private static net.minecraft.world.level.block.state.BlockBehaviour.Properties tubeProperties() {
        return net.minecraft.world.level.block.state.BlockBehaviour.Properties.of()
                .mapColor(MapColor.COLOR_BLACK)
                .sound(SoundType.NETHERITE_BLOCK)
                .strength(2.0f, 6.0f)
                .noOcclusion();
    }

    public static void register(IEventBus modEventBus) {
        BLOCK_ENTITIES.register(modEventBus);
        modEventBus.addListener(TrainTubes::onRegisterCapabilities);
        TrainTubeLinksPayload.register(modEventBus);

        NeoForge.EVENT_BUS.addListener(TrainTubeNetworkManager::onLevelTickPost);
        NeoForge.EVENT_BUS.addListener(TrainTubeLinkInteraction::onLevelTickPost);
        NeoForge.EVENT_BUS.addListener(TrainTubeLinkInteraction::onPlayerLoggedOut);
        NeoForge.EVENT_BUS.addListener(TrainTubePlacementGuard::onEntityPlace);
        NeoForge.EVENT_BUS.addListener(TrainTubePlacementGuard::onServerTickPost);
        NeoForge.EVENT_BUS.addListener(TrainTubeLinksPayload::onPlayerLoggedIn);
        NeoForge.EVENT_BUS.addListener(TrainTubeLinksPayload::onPlayerChangedDimension);
    }

    private static void onRegisterCapabilities(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(Capabilities.FluidHandler.BLOCK, TUBE_BE.get(),
                (be, side) -> be.fluidHandlerFor(side));
    }

    /**
     * 该方块状态是否是我们认的高压水泵，且它的朝向轴与连接方向同轴。
     *
     * <p>按 {@link BlockStateProperties#FACING} 判定（{@code createfirefightingadd} 的
     * {@code HighPressurePumpBlock} 继承 Create 的 {@code PumpBlock}，用的是同一个属性实例）。</p>
     */
    public static boolean isHighPressurePump(BlockState state, Direction.Axis axis) {
        if (state == null || !state.hasProperty(BlockStateProperties.FACING)) {
            return false;
        }
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        return HIGH_PRESSURE_PUMP.equals(id) && state.getValue(BlockStateProperties.FACING).getAxis() == axis;
    }

    public static boolean isTube(BlockState state) {
        return state != null && state.is(TUBE.get());
    }

    private TrainTubes() {
    }
}
