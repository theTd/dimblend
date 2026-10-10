package dimblend.experience.compat.jade;

import com.simibubi.create.content.fluids.drain.ItemDrainBlockEntity;

import dimblend.experience.DimBlend;
import dimblend.experience.compat.create.ItemDrainGrowthBoost;
import dimblend.experience.compat.create.ItemDrainHud;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import snownee.jade.api.BlockAccessor;
import snownee.jade.api.IBlockComponentProvider;
import snownee.jade.api.IServerDataProvider;
import snownee.jade.api.ITooltip;
import snownee.jade.api.config.IPluginConfig;

/**
 * 分液池 HUD 的 Jade provider（服务端数据 + 客户端组件双角色）。
 *
 * <p>数据通道：Jade 只在玩家注视目标时按自身节流同步 server data，无全局流量。
 * 服务端写入当前水量（mb，只认水，口径同 {@code ItemDrainIrrigation}）与催熟剩余
 * tick（{@code countdown*10}，未在计时为 -1）；客户端按 Config 副本组装文案
 * （{@link ItemDrainHud#hudLines}，与护目镜共用）。</p>
 *
 * <p>UID 同时是 Jade 设置里的开关项（{@code config.jade.plugin_dimblend_experience.item_drain_hud}）。
 * 旧版客户端/服务端无此数据时 {@code getServerData} 不含键，直接不出行。</p>
 */
public final class ItemDrainJadeProvider implements IServerDataProvider<BlockAccessor>, IBlockComponentProvider {

    public static final ItemDrainJadeProvider INSTANCE = new ItemDrainJadeProvider();

    private static final ResourceLocation UID = ResourceLocation.fromNamespaceAndPath(DimBlend.MODID,
            "item_drain_hud");
    private static final String TAG_WATER_MB = "WaterMb";
    private static final String TAG_BOOST_TICKS_LEFT = "BoostTicksLeft";

    private ItemDrainJadeProvider() {
    }

    @Override
    public void appendServerData(CompoundTag data, BlockAccessor accessor) {
        ItemDrainBlockEntity be = (ItemDrainBlockEntity) accessor.getBlockEntity();
        int waterMb = 0;
        IFluidHandler handler = accessor.getLevel()
                .getCapability(Capabilities.FluidHandler.BLOCK, be.getBlockPos(), Direction.DOWN);
        if (handler != null && handler.getTanks() >= 1) {
            FluidStack stored = handler.getFluidInTank(0);
            if (stored.is(Fluids.WATER)) {
                waterMb = stored.getAmount();
            }
        }
        data.putInt(TAG_WATER_MB, waterMb);
        int countdown = ((ItemDrainGrowthBoost.HasState) be).dimblend$growthState().countdown;
        data.putInt(TAG_BOOST_TICKS_LEFT, countdown > 0 ? countdown * 10 : -1);
    }

    @Override
    public void appendTooltip(ITooltip tooltip, BlockAccessor accessor, IPluginConfig config) {
        CompoundTag data = accessor.getServerData();
        if (!data.contains(TAG_WATER_MB)) {
            return;
        }
        for (var line : ItemDrainHud.hudLines(data.getInt(TAG_WATER_MB), data.getInt(TAG_BOOST_TICKS_LEFT))) {
            tooltip.add(line);
        }
    }

    @Override
    public ResourceLocation getUid() {
        return UID;
    }
}
