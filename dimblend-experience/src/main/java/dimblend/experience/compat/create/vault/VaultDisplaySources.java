package dimblend.experience.compat.create.vault;

import com.simibubi.create.api.behaviour.display.DisplaySource;
import com.simibubi.create.api.registry.CreateRegistries;
import dimblend.experience.DimBlend;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModList;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 显示连接器的"读取存量"来源：注册两个 DisplaySource，并把它们挂到三种保险库方块的 BlockEntityType 上。
 *
 * <p>挂接按<b>注册名</b>查 {@code BuiltInRegistries.BLOCK_ENTITY_TYPE}，不引用 addon 的类，因此
 * {@code create_connected}/{@code dndecor} 缺席时本模块照常启动：</p>
 * <ul>
 *   <li>{@code create:item_vault} — 物品保险库（Create 本体）</li>
 *   <li>{@code create_connected:item_silo} — 纵向物品保险库（create_connected 1.2.2）</li>
 *   <li>{@code dndecor:dyed_container} — 集装箱（Design n' Decor 2.2b 的 container / 染色 / 纯色三族
 *       共用这一个方块实体类型，{@code DyedContainerBE} 直接继承 Create 的 ItemVaultBlockEntity）</li>
 * </ul>
 *
 * <p>三个类型都实现 {@code IMultiBlockEntityContainer.Inventory} 并注册了
 * {@code Capabilities.ItemHandler.BLOCK}（主方块上挂整台结构拼接后的库存），所以贴在任意一段都能读整台。</p>
 */
public final class VaultDisplaySources {

    private static final DeferredRegister<DisplaySource> SOURCES =
            DeferredRegister.create(CreateRegistries.DISPLAY_SOURCE, DimBlend.MODID);

    public static final DeferredHolder<DisplaySource, VaultGroupCountDisplaySource> STORAGE_GROUPS =
            SOURCES.register("vault_storage_groups", VaultGroupCountDisplaySource::new);
    public static final DeferredHolder<DisplaySource, VaultItemCountDisplaySource> STORAGE_ITEMS =
            SOURCES.register("vault_storage_items", VaultItemCountDisplaySource::new);

    public static void register(IEventBus modEventBus) {
        SOURCES.register(modEventBus);
        modEventBus.addListener(VaultDisplaySources::setup);
    }

    private static void setup(FMLCommonSetupEvent event) {
        // Registrate 的方块实体类型在 common setup 阶段已冻结进注册表，这里只做按名查表 + 关联。
        event.enqueueWork(() -> {
            associate("create:item_vault");
            if (ModList.get().isLoaded("create_connected")) {
                associate("create_connected:item_silo");
            }
            if (ModList.get().isLoaded("dndecor")) {
                associate("dndecor:dyed_container");
            }
        });
    }

    private static void associate(String blockEntityTypeId) {
        ResourceLocation id = ResourceLocation.tryParse(blockEntityTypeId);
        BlockEntityType<?> type = id == null ? null : BuiltInRegistries.BLOCK_ENTITY_TYPE.get(id);
        if (type == null) {
            DimBlend.LOGGER.warn("[display-link] 方块实体类型 {} 不存在，跳过存量读取来源挂接", blockEntityTypeId);
            return;
        }
        DisplaySource.BY_BLOCK_ENTITY.add(type, STORAGE_GROUPS.get());
        DisplaySource.BY_BLOCK_ENTITY.add(type, STORAGE_ITEMS.get());
    }

    private VaultDisplaySources() {
    }
}
