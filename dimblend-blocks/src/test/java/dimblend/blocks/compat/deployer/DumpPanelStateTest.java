package dimblend.blocks.compat.deployer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import net.minecraft.nbt.CompoundTag;

/**
 * {@code easyWrite} 的键契约。
 *
 * <p>这条测试存在的原因很具体：Deployer 的 {@code AbstractPanelBehaviour#write} 在调完
 * {@code FactoryPanelBehaviour#write}（写完整份 {@code nbt[slotName]}）之后，会<b>新建</b>一个只含
 * 面板自有字段的标签、交给 {@code easyWrite} 补几笔、再 {@code nbt.put(slotName, 新标签)}。
 * 也就是说父类刚写好的 {@code Filter}/{@code FilterAmount}/{@code UpTo}/{@code RecipeAddress} 会被整份丢弃。
 * 这里断言 {@link DumpPanelState#putScalars} 把这个「必须补回去的字段清单」写全，
 * 免得以后再漏一个键——漏哪个，就对应哪个功能在存档/重连后失效。</p>
 */
class DumpPanelStateTest {

    @Test
    void putScalarsFillsEveryKeyCreateReadsBack() {
        CompoundTag tag = new CompoundTag();
        UUID network = UUID.randomUUID();
        DumpPanelState.putScalars(tag, 42, true, "factory/main", 3, 7, network);

        assertTrue(tag.contains(DumpPanelState.FILTER_AMOUNT));
        assertEquals(42, tag.getInt(DumpPanelState.FILTER_AMOUNT));
        assertTrue(tag.getBoolean(DumpPanelState.UP_TO));
        assertEquals("factory/main", tag.getString(DumpPanelState.RECIPE_ADDRESS));
        assertEquals(3, tag.getInt(DumpPanelState.RECIPE_OUTPUT));
        assertEquals(7, tag.getInt(DumpPanelState.PROMISE_CLEARING_INTERVAL));
        assertTrue(tag.hasUUID(DumpPanelState.FREQ));
        assertEquals(network, tag.getUUID(DumpPanelState.FREQ));
    }

    @Test
    void noNetworkMeansNoFreqKey() {
        // Create 的 read 只在 hasUUID(Freq) 时才覆盖网络字段；putUUID(null) 本身会抛 NPE
        CompoundTag tag = new CompoundTag();
        DumpPanelState.putScalars(tag, 0, false, "", 1, 10, null);
        assertFalse(tag.hasUUID(DumpPanelState.FREQ));
        assertEquals(0, tag.getInt(DumpPanelState.FILTER_AMOUNT));
        assertFalse(tag.getBoolean(DumpPanelState.UP_TO));
        assertEquals("", tag.getString(DumpPanelState.RECIPE_ADDRESS));
        assertEquals(1, tag.getInt(DumpPanelState.RECIPE_OUTPUT));
        assertEquals(10, tag.getInt(DumpPanelState.PROMISE_CLEARING_INTERVAL));
    }

    @Test
    void nullAddressIsWrittenAsEmptyString() {
        CompoundTag tag = new CompoundTag();
        DumpPanelState.putScalars(tag, 5, false, null, 1, 10, null);
        assertEquals("", tag.getString(DumpPanelState.RECIPE_ADDRESS));
    }

    @Test
    void requiredKeyListIsExactlyTheSetEasyWriteMustWrite() {
        // Filter 由调用方单独写（需要一个 ItemStack 才能编码），其余六个都在 putScalars 里
        assertEquals(List.of(
                DumpPanelState.FILTER,
                DumpPanelState.FILTER_AMOUNT,
                DumpPanelState.UP_TO,
                DumpPanelState.RECIPE_ADDRESS,
                DumpPanelState.RECIPE_OUTPUT,
                DumpPanelState.PROMISE_CLEARING_INTERVAL,
                DumpPanelState.FREQ), DumpPanelState.REQUIRED_KEYS);
        assertEquals(7, DumpPanelState.REQUIRED_KEYS.size());

        CompoundTag tag = new CompoundTag();
        UUID network = UUID.randomUUID();
        // Filter 占位写一个空标签，模拟调用方的那一笔
        tag.put(DumpPanelState.FILTER, new CompoundTag());
        DumpPanelState.putScalars(tag, 1, false, "a", 1, 10, network);
        for (String key : DumpPanelState.REQUIRED_KEYS) {
            assertTrue(tag.contains(key), "easyWrite 漏了 " + key);
        }
    }
}
