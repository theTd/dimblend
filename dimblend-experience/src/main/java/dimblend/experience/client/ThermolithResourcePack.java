package dimblend.experience.client;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackSelectionConfig;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.PathPackResources;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackSource;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.event.AddPackFindersEvent;
import net.neoforged.neoforgespi.language.IModFileInfo;

/**
 * 把 {@code thermolith_override_pack} 作为内置资源包塞到最高优先级：
 * 我们的 {@code assets/cold_sweat/blockstates/thermolith.json} 覆盖 Cold Sweat 自己那份，
 * 让 {@code dimblend_mode} × {@code dimblend_suppressed} 的 48 个变体（4 朝向 × 2 通电 × 3 模式 × 2 抑制）能解析到冰/熔岩贴图。
 *
 * <p>为什么要一个独立资源包而不是直接往 {@code assets/cold_sweat/} 里放文件：同一个
 * 命名空间/路径在多个包之间只取优先级最高的那份，模组自带的资源包（NeoForge 的
 * "mod resources"）优先级固定；用 {@link PackSelectionConfig#fixedPosition()} = true 的
 * 内置包钉在 {@link Pack.Position#TOP}，才能与模组加载顺序无关地稳定压过 Cold Sweat 的 jar。</p>
 *
 * <p>NeoForge 21.1 的 {@code AddPackFindersEvent#addPackFinders(ResourceLocation, ...)} 内部
 * 也做同样的事，但它把 {@code fixedPosition} 写死为 false（玩家可在资源包界面拖动），
 * 所以这里自己构造 {@link Pack}。</p>
 */
public final class ThermolithResourcePack {

    private static final String MOD_ID = "dimblend_experience";
    /** jar 内子目录名。 */
    public static final String PACK_PATH = "thermolith_override_pack";
    /** 资源包界面的名字（lang 文件提供）。 */
    public static final String TITLE_KEY = "pack.dimblend_experience.thermolith_override";

    /** 由客户端初始化（{@code DimBlendClient} 构造期）挂到 mod 事件总线上。 */
    public static void onAddPackFinders(AddPackFindersEvent event) {
        if (event.getPackType() != PackType.CLIENT_RESOURCES) {
            return;
        }
        if (!ModList.get().isLoaded("cold_sweat")) {
            return;
        }

        IModFileInfo modFile = ModList.get().getModFileById(MOD_ID);
        if (modFile == null) {
            return;
        }

        Path root;
        try {
            root = modFile.getFile().findResource(PACK_PATH);
        } catch (RuntimeException missing) {
            // 旧 jar 里没有这个目录：静默跳过（贴图退回原版恒温石，不崩）。
            return;
        }
        if (root == null || !Files.isDirectory(root)) {
            return;
        }

        PackLocationInfo location = new PackLocationInfo(
                MOD_ID + ":" + PACK_PATH,
                Component.translatable(TITLE_KEY),
                PackSource.BUILT_IN,
                Optional.empty());
        Pack pack = Pack.readMetaAndCreate(
                location,
                new PathPackResources.PathResourcesSupplier(root),
                PackType.CLIENT_RESOURCES,
                new PackSelectionConfig(true, Pack.Position.TOP, true));
        if (pack == null) {
            return;
        }
        event.addRepositorySource(packConsumer -> packConsumer.accept(pack));
    }

    private ThermolithResourcePack() {
    }
}
