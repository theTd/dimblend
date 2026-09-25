package dimblend.experience.datagen;

import net.neoforged.neoforge.data.event.GatherDataEvent;

/**
 * Datagen 入口：目前仅注册工厂切石机配方。后续新增 provider 时在此集中添加，
 * 保持 {@code DimBlend} 构造函数干净。
 */
public final class DataGenerators {
    private DataGenerators() {}

    public static void gatherData(GatherDataEvent event) {
        if (event.includeServer()) {
            event.createProvider(FactoryStonecuttingProvider::new);
        }
    }
}
