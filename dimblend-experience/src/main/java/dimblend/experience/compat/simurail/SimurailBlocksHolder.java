package dimblend.experience.compat.simurail;

import com.crystaelix.simurail.content.SimurailBlocks;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * E1 Simurail 自带方块清单。
 * 类内硬引用 SimurailBlocks，必须经 SimurailBlockGuard#isSimurailBlock
 * 的 ModList 守卫触达（JVM 惰性解析，缺失时不加载本类）。
 * 清单覆盖运行 jar 0.0.0-a 的方块注册表（PhysicsBogey/AutomaticCoupler）；
 * CenteredGangwayJoint 在该构建中未注册进方块注册表（源码有类无条目），
 * 故不列入——升级 Simurail 后需重核。
 */
final class SimurailBlocksHolder {

    private static final Block[] BLOCKS = {
            SimurailBlocks.PHYSICS_BOGEY.get(),
            SimurailBlocks.AUTOMATIC_COUPLER.get(),
    };

    static boolean isSimurailBlock(BlockState state) {
        for (Block block : BLOCKS) {
            if (state.is(block)) {
                return true;
            }
        }
        return false;
    }

    private SimurailBlocksHolder() {
    }
}