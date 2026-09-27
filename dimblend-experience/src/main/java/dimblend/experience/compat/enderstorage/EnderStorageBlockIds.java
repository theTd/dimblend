package dimblend.experience.compat.enderstorage;

/**
 * G5 受限方块注册名（纯函数，不引用任何游戏类，可单元测试）。
 * 注册名以 EnderStorage 2.13.0.191 jar 的 blockstates 为准：ender_chest / ender_tank。
 * 独立成类是因为 {@link EnderStorageStructureGuard} 加载时字节码校验会解析
 * 游戏类型，而单测运行时类路径没有 Minecraft。
 */
final class EnderStorageBlockIds {

    static final String MODID = "enderstorage";

    /**
     * 是否受限方块：仅 enderstorage 命名空间的 ender_chest / ender_tank；
     * 原版 minecraft:ender_chest、末影袋等不中。
     */
    static boolean isGuarded(String namespace, String path) {
        return MODID.equals(namespace) && ("ender_chest".equals(path) || "ender_tank".equals(path));
    }

    private EnderStorageBlockIds() {
    }
}
