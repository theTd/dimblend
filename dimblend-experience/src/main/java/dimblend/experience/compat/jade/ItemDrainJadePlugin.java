package dimblend.experience.compat.jade;

import com.simibubi.create.content.fluids.drain.ItemDrainBlock;
import com.simibubi.create.content.fluids.drain.ItemDrainBlockEntity;

import snownee.jade.api.IWailaClientRegistration;
import snownee.jade.api.IWailaCommonRegistration;
import snownee.jade.api.IWailaPlugin;
import snownee.jade.api.WailaPlugin;

/**
 * 分液池灌溉/催熟状态的 Jade 集成（与护目镜同口径，见
 * {@code dimblend.experience.compat.create.ItemDrainHud}）。
 *
 * <p>{@code @WailaPlugin("create")}：value 为前置 modid——NeoForge 下 Create
 * 缺席时 Jade 根本不加载本类（注解扫描期过滤），本类对 Create 类型的直接引用
 * 不会引发 NoClassDefFoundError；Jade 自身缺席时本类同样无人加载
 * （compileOnly 依赖安全）。</p>
 */
@WailaPlugin("create")
public class ItemDrainJadePlugin implements IWailaPlugin {

    @Override
    public void register(IWailaCommonRegistration registration) {
        registration.registerBlockDataProvider(ItemDrainJadeProvider.INSTANCE, ItemDrainBlockEntity.class);
    }

    @Override
    public void registerClient(IWailaClientRegistration registration) {
        registration.registerBlockComponent(ItemDrainJadeProvider.INSTANCE, ItemDrainBlock.class);
    }
}
