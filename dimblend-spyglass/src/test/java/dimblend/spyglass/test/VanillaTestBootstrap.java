package dimblend.spyglass.test;

import java.util.List;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.fml.loading.LoadingModList;

/**
 * Boots the vanilla registries once per test JVM so unit tests can use real block states and
 * {@code ChunkStatus}.
 *
 * <p>{@link Bootstrap#bootStrap()} normally runs inside a launcher, where FML has already set up
 * {@link LoadingModList}. In a plain JUnit JVM FML is absent, so a synthetic empty mod list is
 * installed first; that is enough for {@code FeatureFlags} and nothing else is read from it. The
 * empty {@code assets/minecraft/lang/en_us.json} under {@code src/test/resources} satisfies
 * {@code Language}'s class initializer, which fails on a missing file.
 *
 * <p>NeoForge fills {@code Block.BLOCK_STATE_REGISTRY} from a registry-freeze callback that only
 * runs during mod loading; without it every block state would have id 0. Rebuilding the table here
 * uses the same order NeoForge uses (registry order, then each block's state order), so ids are
 * consistent within the tests.
 */
public final class VanillaTestBootstrap {
    private static boolean initialized;

    private VanillaTestBootstrap() {
    }

    public static synchronized void ensure() {
        if (initialized) {
            return;
        }
        SharedConstants.tryDetectVersion();
        if (LoadingModList.get() == null) {
            LoadingModList.of(List.of(), List.of(), List.of(), List.of(), Map.of());
        }
        Bootstrap.bootStrap();
        if (Block.BLOCK_STATE_REGISTRY.size() == 0) {
            for (Block block : BuiltInRegistries.BLOCK) {
                for (BlockState state : block.getStateDefinition().getPossibleStates()) {
                    Block.BLOCK_STATE_REGISTRY.add(state);
                }
            }
        }
        initialized = true;
    }
}
