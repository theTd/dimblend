package dimblend.experience.compat.connected.mobdisplay;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * 生物展示催化剂"放进盒子立刻看见生物"的<b>同步契约</b>回归测试（2026-11-26 根因）。
 *
 * <p>根因链：原版 {@code BlockEntity#getUpdatePacket()} 默认 {@code return null}，
 * {@code ChunkHolder#broadcastBlockEntity} 见 null 就直接跳过发包，于是
 * {@code setStoredBox → setChanged() → level.sendBlockUpdated(...)} 这条链路对客户端完全静默：
 * 客户端 {@code storedBox} 一直是空的，{@code clientTick()} 把 {@code displayEntity} 置 null，
 * 方块里就看不到生物（断线重进/区块重载走存档加载才看得到，正是用户说的"其余正常"）。</p>
 *
 * <p>单测跑不了方块实体（要注册表 + 服务端世界），所以这里做源码契约检查：
 * {@link MobDisplayCatalystBlockEntity} 必须同时声明 {@code getUpdateTag} 与
 * {@code getUpdatePacket}，且后者真的造包（{@code ClientboundBlockEntityDataPacket.create(this)}）。
 * 检查前先剥掉注释，避免"只在注释里写了 {@code getUpdatePacket} 就算过"。</p>
 */
class MobDisplayCatalystSyncContractTest {

    @Test
    void blockEntityDeclaresBothUpdateTagAndUpdatePacket() throws IOException {
        String code = stripComments(readBlockEntitySource());

        assertTrue(code.contains("CompoundTag getUpdateTag(HolderLookup.Provider"),
                "方块实体必须覆盖 getUpdateTag 才能把 StoredBox 序列化进同步包");
        assertTrue(code.contains("Packet<ClientGamePacketListener> getUpdatePacket()"),
                "方块实体必须覆盖 getUpdatePacket：默认实现返回 null，永远不会发包");
        assertTrue(code.contains("ClientboundBlockEntityDataPacket.create(this)"),
                "getUpdatePacket 必须真的造包（create(this) 会用 getUpdateTag(level.registryAccess()) 填 tag）");
    }

    @Test
    void setStoredBoxStillMarksDirtyAndBroadcasts() throws IOException {
        String code = stripComments(readBlockEntitySource());

        assertTrue(code.contains("setChanged()"), "setStoredBox 必须 setChanged()，否则连区块存档都不会写");
        assertTrue(code.contains("sendBlockUpdated"), "setStoredBox 必须广播方块更新，否则 ChunkHolder 不会调 getUpdatePacket");
    }

    private static String readBlockEntitySource() throws IOException {
        Path moduleRoot = Path.of("").toAbsolutePath();
        Path direct = moduleRoot.resolve(
                "src/main/java/dimblend/experience/compat/connected/mobdisplay/MobDisplayCatalystBlockEntity.java");
        if (Files.exists(direct)) {
            return Files.readString(direct, StandardCharsets.UTF_8);
        }
        Path nested = moduleRoot.resolve(
                "dimblend-experience/src/main/java/dimblend/experience/compat/connected/mobdisplay/"
                        + "MobDisplayCatalystBlockEntity.java");
        assertTrue(Files.exists(nested), "Could not locate MobDisplayCatalystBlockEntity.java from " + moduleRoot);
        return Files.readString(nested, StandardCharsets.UTF_8);
    }

    /** 只剥行注释与块注释（本文件无需处理字符串字面量里的注释符号）。 */
    private static String stripComments(String source) {
        StringBuilder out = new StringBuilder(source.length());
        int i = 0;
        while (i < source.length()) {
            char c = source.charAt(i);
            char next = i + 1 < source.length() ? source.charAt(i + 1) : '\0';
            if (c == '/' && next == '/') {
                i += 2;
                while (i < source.length() && source.charAt(i) != '\n') {
                    i++;
                }
            } else if (c == '/' && next == '*') {
                i += 2;
                while (i < source.length()) {
                    if (source.charAt(i) == '*' && i + 1 < source.length() && source.charAt(i + 1) == '/') {
                        i += 2;
                        break;
                    }
                    i++;
                }
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }
}
