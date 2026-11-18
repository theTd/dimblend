package dimblend.experience.compat.sophisticated;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.Optional;
import java.util.function.Supplier;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.IItemHandlerModifiable;
import org.junit.jupiter.api.Test;

/**
 * {@link SophisticatedWrapperBridge} 的核心契约：所有解析都在<b>调用时</b>读取容器当前的 wrapper。
 *
 * <p>上游 bug 的形状是构造期把 {@code upgradeWrapper::getInventory} 之类的供应商绑死在旧
 * wrapper 实例上（合成 1.4.38、切石机 1.4.38/1.5.38），模块槽同步替换 wrapper 后仍读写旧库存。
 * 这里用假类复刻三个目标类的公开方法形状（{@code UpgradeContainerBase#getUpgradeWrapper}、
 * wrapper 的 {@code getInventory}/{@code getInputInventory}/{@code getRecipeId}/
 * {@code setRecipeId}、{@code getRecipeContainer}）以及私有 {@code inputInventory} 字段，
 * 断言"替换 wrapper 之后解析结果立刻变新"。</p>
 *
 * <p>为什么不用真的 Sophisticated 类：目标模组不在本模块编译期 classpath，且纯 JUnit 里
 * {@code ItemStack} 的 {@code <clinit>} 要求先 Bootstrap 注册表（见
 * {@code VillagerTradeSwapTest} 的说明）。假 handler 只实现接口、从不返回真实物品，
 * 因此不会触碰那套注册表。mixin 本身在 JUnit 里无法应用，这里只测可测的桥接逻辑。</p>
 */
public class SophisticatedWrapperBridgeTest {

    private static final ResourceLocation OLD_RECIPE =
            ResourceLocation.fromNamespaceAndPath("minecraft", "stone");
    private static final ResourceLocation NEW_RECIPE =
            ResourceLocation.fromNamespaceAndPath("minecraft", "cobblestone");
    private static final ResourceLocation WRITTEN_RECIPE =
            ResourceLocation.fromNamespaceAndPath("minecraft", "dirt");

    @Test
    void resolveInventoryFollowsWrapperReplacement() {
        FakeItemHandler oldInventory = new FakeItemHandler(9);
        FakeItemHandler newInventory = new FakeItemHandler(9);
        FakeUpgradeContainer container = new FakeUpgradeContainer(new FakeCraftingWrapper(oldInventory));

        assertSame(oldInventory, SophisticatedWrapperBridge.resolveInventory(container));

        container.replaceWrapper(new FakeCraftingWrapper(newInventory));

        assertSame(newInventory, SophisticatedWrapperBridge.resolveInventory(container));
        assertNotSame(oldInventory, SophisticatedWrapperBridge.resolveInventory(container));
    }

    /**
     * 反证：构造期捕获的供应商确实会停在旧库存上，而桥接不会——这就是修复前后的差别。
     */
    @Test
    void constructorCapturedSupplierGoesStaleWhileBridgeStaysCurrent() {
        FakeItemHandler oldInventory = new FakeItemHandler(9);
        FakeItemHandler newInventory = new FakeItemHandler(9);
        FakeUpgradeContainer container = new FakeUpgradeContainer(new FakeCraftingWrapper(oldInventory));

        // 上游构造器的形状：方法引用在创建时求值，绑死当时的 wrapper 实例。
        Supplier<IItemHandler> capturedAtConstruction = container.craftingWrapper()::getInventory;

        container.replaceWrapper(new FakeCraftingWrapper(newInventory));

        assertSame(oldInventory, capturedAtConstruction.get(), "捕获的供应商仍返回旧库存");
        assertSame(newInventory, SophisticatedWrapperBridge.resolveInventory(container), "桥接解析到新库存");
    }

    @Test
    void blockConverterInputInventoryAndRecipeIdFollowWrapperReplacement() {
        FakeBlockConverterWrapper first =
                new FakeBlockConverterWrapper(new FakeItemHandler(1), OLD_RECIPE);
        FakeBlockConverterUpgradeContainer container =
                new FakeBlockConverterUpgradeContainer(first, new FakeRecipeContainer(new FakeCraftingMatrix()));
        assertSame(first.inputInventory(),
                SophisticatedWrapperBridge.resolveBlockConverterInputInventory(container));
        assertEquals(Optional.of(OLD_RECIPE),
                SophisticatedWrapperBridge.resolveBlockConverterRecipeId(container));

        FakeBlockConverterWrapper second =
                new FakeBlockConverterWrapper(new FakeItemHandler(1), NEW_RECIPE);
        container.replaceWrapper(second);

        assertSame(second.inputInventory(),
                SophisticatedWrapperBridge.resolveBlockConverterInputInventory(container));
        assertNotSame(first.inputInventory(),
                SophisticatedWrapperBridge.resolveBlockConverterInputInventory(container));
        assertEquals(Optional.of(NEW_RECIPE),
                SophisticatedWrapperBridge.resolveBlockConverterRecipeId(container));
    }

    @Test
    void writeBlockConverterRecipeIdOnlyTouchesCurrentWrapper() {
        FakeBlockConverterWrapper first =
                new FakeBlockConverterWrapper(new FakeItemHandler(1), OLD_RECIPE);
        FakeBlockConverterUpgradeContainer container =
                new FakeBlockConverterUpgradeContainer(first, new FakeRecipeContainer(new FakeCraftingMatrix()));
        FakeBlockConverterWrapper second =
                new FakeBlockConverterWrapper(new FakeItemHandler(1), NEW_RECIPE);
        container.replaceWrapper(second);

        SophisticatedWrapperBridge.writeBlockConverterRecipeId(container, WRITTEN_RECIPE);

        assertEquals(Optional.of(WRITTEN_RECIPE), second.getRecipeId());
        assertEquals(Optional.of(OLD_RECIPE), first.getRecipeId(), "旧 wrapper 不应被写入");
    }

    @Test
    void blockConverterInputInventoryFieldIsReadThroughClassHierarchy() {
        FakeCraftingMatrix inputInventory = new FakeCraftingMatrix();
        FakeRecipeContainer recipeContainer = new FakeRecipeContainer(inputInventory);

        assertSame(inputInventory,
                SophisticatedWrapperBridge.getBlockConverterInputInventoryField(recipeContainer));
    }

    @Test
    void recipeContainerIsReachableFromUpgradeContainer() {
        FakeRecipeContainer recipeContainer = new FakeRecipeContainer(new FakeCraftingMatrix());
        FakeBlockConverterUpgradeContainer container =
                new FakeBlockConverterUpgradeContainer(null, recipeContainer);

        assertSame(recipeContainer, SophisticatedWrapperBridge.getRecipeContainer(container));
    }

    @Test
    void missingWrapperDegradesInsteadOfResolvingAnything() {
        FakeUpgradeContainer empty = new FakeUpgradeContainer(null);
        FakeBlockConverterUpgradeContainer emptyConverter =
                new FakeBlockConverterUpgradeContainer(null, null);

        assertNull(SophisticatedWrapperBridge.resolveInventory(empty));
        assertNull(SophisticatedWrapperBridge.resolveBlockConverterInputInventory(emptyConverter));
        assertEquals(Optional.empty(), SophisticatedWrapperBridge.resolveBlockConverterRecipeId(emptyConverter));
        SophisticatedWrapperBridge.writeBlockConverterRecipeId(emptyConverter, NEW_RECIPE);
        assertNull(SophisticatedWrapperBridge.getRecipeContainer(null));
        assertNull(SophisticatedWrapperBridge.getBlockConverterInputInventoryField(null));
    }

    // ---- 假类：只复刻被桥接读取的成员形状，不引用任何 Sophisticated 类型 ----

    public static final class FakeItemHandler implements IItemHandlerModifiable {

        private final int slots;

        FakeItemHandler(int slots) {
            this.slots = slots;
        }

        @Override
        public void setStackInSlot(int slot, ItemStack stack) {
        }

        @Override
        public int getSlots() {
            return slots;
        }

        @Override
        public ItemStack getStackInSlot(int slot) {
            return null;
        }

        @Override
        public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
            return null;
        }

        @Override
        public ItemStack extractItem(int slot, int amount, boolean simulate) {
            return null;
        }

        @Override
        public int getSlotLimit(int slot) {
            return 64;
        }

        @Override
        public boolean isItemValid(int slot, ItemStack stack) {
            return true;
        }
    }

    /**
     * 复刻 {@code CraftingItemHandler} 的公开形状：它是 {@link Container}（不是
     * {@code IItemHandlerModifiable}），所以 {@code BlockConverterRecipeContainer#inputInventory}
     * 的字段类型只能用反射读、返回时才转成 {@code Container}。
     */
    public static final class FakeCraftingMatrix implements Container {

        @Override
        public int getContainerSize() {
            return 0;
        }

        @Override
        public boolean isEmpty() {
            return true;
        }

        @Override
        public ItemStack getItem(int slot) {
            return null;
        }

        @Override
        public ItemStack removeItem(int slot, int amount) {
            return null;
        }

        @Override
        public ItemStack removeItemNoUpdate(int slot) {
            return null;
        }

        @Override
        public void setItem(int slot, ItemStack stack) {
        }

        @Override
        public void setChanged() {
        }

        @Override
        public void clearContent() {
        }

        @Override
        public boolean stillValid(Player player) {
            return true;
        }
    }

    public static final class FakeCraftingWrapper {

        private final FakeItemHandler inventory;

        FakeCraftingWrapper(FakeItemHandler inventory) {
            this.inventory = inventory;
        }

        public FakeItemHandler getInventory() {
            return inventory;
        }
    }

    public static final class FakeBlockConverterWrapper {

        private final FakeItemHandler inputInventory;
        private ResourceLocation recipeId;

        FakeBlockConverterWrapper(FakeItemHandler inputInventory, ResourceLocation recipeId) {
            this.inputInventory = inputInventory;
            this.recipeId = recipeId;
        }

        public FakeItemHandler getInputInventory() {
            return inputInventory;
        }

        public FakeItemHandler inputInventory() {
            return inputInventory;
        }

        public Optional<ResourceLocation> getRecipeId() {
            return Optional.ofNullable(recipeId);
        }

        public void setRecipeId(ResourceLocation recipeId) {
            this.recipeId = recipeId;
        }
    }

    /** 复刻 {@code UpgradeContainerBase}：wrapper 字段可被 {@code setUpgradeWrapper} 替换。 */
    public static final class FakeUpgradeContainer {

        private Object wrapper;

        FakeUpgradeContainer(Object wrapper) {
            this.wrapper = wrapper;
        }

        public Object getUpgradeWrapper() {
            return wrapper;
        }

        void replaceWrapper(Object replacement) {
            this.wrapper = replacement;
        }

        FakeCraftingWrapper craftingWrapper() {
            return (FakeCraftingWrapper) wrapper;
        }
    }

    /** 复刻 {@code BlockConverterRecipeContainer} 声明私有 {@code inputInventory} 的层级。 */
    public static class FakeRecipeContainerBase {

        private final FakeCraftingMatrix inputInventory;

        FakeRecipeContainerBase(FakeCraftingMatrix inputInventory) {
            this.inputInventory = inputInventory;
        }
    }

    public static final class FakeRecipeContainer extends FakeRecipeContainerBase {

        FakeRecipeContainer(FakeCraftingMatrix inputInventory) {
            super(inputInventory);
        }
    }

    /** 复刻 {@code BlockConverterUpgradeContainer}：wrapper + 配方容器。 */
    public static final class FakeBlockConverterUpgradeContainer {

        private Object wrapper;
        private final Object recipeContainer;

        FakeBlockConverterUpgradeContainer(Object wrapper, Object recipeContainer) {
            this.wrapper = wrapper;
            this.recipeContainer = recipeContainer;
        }

        public Object getUpgradeWrapper() {
            return wrapper;
        }

        public Object getRecipeContainer() {
            return recipeContainer;
        }

        void replaceWrapper(Object replacement) {
            this.wrapper = replacement;
        }
    }
}
