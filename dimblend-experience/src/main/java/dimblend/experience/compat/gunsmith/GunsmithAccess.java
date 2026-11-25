package dimblend.experience.compat.gunsmith;

import java.lang.reflect.Method;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * TaCZ / gundb 的<b>运行期反射访问层</b>：这两个模组都不在本模块编译 classpath 上，
 * 所以所有类型、方法与数据组件均按类名懒解析并缓存；任何一步失败均静默降级，绝不抛异常。
 */
public final class GunsmithAccess {

    private static final String GUN_INTERFACE = "com.tacz.guns.api.item.IGun";
    private static final String ATTACHMENT_INTERFACE = "com.tacz.guns.api.item.IAttachment";
    private static final String ATTACHMENT_ITEM = "com.tacz.guns.item.AttachmentItem";
    private static final String ATTACHMENT_TYPE_ENUM = "com.tacz.guns.api.item.attachment.AttachmentType";
    private static final String ATTACHMENT_PROPERTY_MANAGER = "com.tacz.guns.resource.modifier.AttachmentPropertyManager";
    private static final String GUN_OPERATOR = "com.tacz.guns.api.entity.IGunOperator";
    private static final String COMMON_GUN_INDEX = "com.tacz.guns.resource.index.CommonGunIndex";
    private static final String CLIENT_GUN_INDEX = "com.tacz.guns.client.resource.index.ClientGunIndex";
    private static final String GUN_DISPLAY_INSTANCE = "com.tacz.guns.client.resource.GunDisplayInstance";
    private static final String TIMELESS_API = "com.tacz.guns.api.TimelessAPI";
    private static final String SCRIPT_API = "com.tacz.guns.item.ModernKineticGunScriptAPI";
    private static final String GUNDB_COMPONENTS = "mod.cdv.gdb.resource.ModDataComponents";

    /** 扩展弹匣的 {@code AttachmentType} 常量名。 */
    private static final String EXTENDED_MAG = "EXTENDED_MAG";

    private static boolean resolved;
    private static boolean clientResolved;

    private static Class<?> gunInterface;
    private static Class<?> attachmentInterface;
    private static Class<?> attachmentItemClass;
    private static Object[] attachmentTypes = new Object[0];
    private static Object extendedMagType;

    private static Method getIGunOrNull;
    private static Method getGunId;
    private static Method getAttachment;
    private static Method unloadAttachment;
    private static Method dropAllAmmo;
    private static Method postChangeEvent;
    private static Method fromLivingEntity;
    private static Method getSynReloadState;
    private static Method getStateType;
    private static Method stateTypeIsReloading;

    private static Method getCommonGunIndex;
    private static Method commonIndexGetType;

    private static Method scriptApiGetShooter;
    private static Method scriptApiGetItemStack;

    private static Method getClientGunIndex;
    private static Method clientIndexGetType;
    private static Method getGunDisplay;
    private static Method displayGetSounds;

    private static DataComponentType<Object> jammedComponent;

    // ---- 公共解析（双端安全，绝不触碰仅客户端类） -------------------------------

    private static void resolve() {
        if (resolved) {
            return;
        }
        resolved = true;

        gunInterface = type(GUN_INTERFACE);
        attachmentInterface = type(ATTACHMENT_INTERFACE);
        attachmentItemClass = type(ATTACHMENT_ITEM);

        Class<?> attachmentTypeClass = type(ATTACHMENT_TYPE_ENUM);
        if (attachmentTypeClass != null) {
            Object[] constants = attachmentTypeClass.getEnumConstants();
            if (constants != null) {
                attachmentTypes = constants;
                for (Object constant : constants) {
                    if (EXTENDED_MAG.equalsIgnoreCase(String.valueOf(constant))) {
                        extendedMagType = constant;
                        break;
                    }
                }
            }
        }

        if (gunInterface != null) {
            getIGunOrNull = method(gunInterface, "getIGunOrNull", ItemStack.class);
            getGunId = method(gunInterface, "getGunId", ItemStack.class);
            if (attachmentTypeClass != null) {
                getAttachment = method(gunInterface, "getAttachment",
                        HolderLookup.Provider.class, ItemStack.class, attachmentTypeClass);
                unloadAttachment = method(gunInterface, "unloadAttachment",
                        HolderLookup.Provider.class, ItemStack.class, attachmentTypeClass);
            }
            dropAllAmmo = method(gunInterface, "dropAllAmmo", Player.class, ItemStack.class);
        }

        Class<?> propertyManager = type(ATTACHMENT_PROPERTY_MANAGER);
        if (propertyManager != null) {
            postChangeEvent = method(propertyManager, "postChangeEvent", LivingEntity.class, ItemStack.class);
        }

        Class<?> gunOperator = type(GUN_OPERATOR);
        if (gunOperator != null) {
            fromLivingEntity = method(gunOperator, "fromLivingEntity", LivingEntity.class);
            getSynReloadState = method(gunOperator, "getSynReloadState");
            Class<?> reloadState = type("com.tacz.guns.api.entity.ReloadState");
            if (reloadState != null) {
                getStateType = method(reloadState, "getStateType");
                Class<?> stateType = type("com.tacz.guns.api.entity.ReloadState$StateType");
                if (stateType != null) {
                    stateTypeIsReloading = method(stateType, "isReloading");
                }
            }
        }

        Class<?> timeless = type(TIMELESS_API);
        if (timeless != null) {
            getCommonGunIndex = method(timeless, "getCommonGunIndex", ResourceLocation.class);
        }
        Class<?> commonIndex = type(COMMON_GUN_INDEX);
        if (commonIndex != null) {
            commonIndexGetType = method(commonIndex, "getType");
        }

        Class<?> scriptApi = type(SCRIPT_API);
        if (scriptApi != null) {
            scriptApiGetShooter = method(scriptApi, "getShooter");
            scriptApiGetItemStack = method(scriptApi, "getItemStack");
        }

        Class<?> components = type(GUNDB_COMPONENTS);
        if (components != null) {
            Object holder = field(components, "JAMMED");
            Object value = holder instanceof Supplier<?> supplier ? supplier.get() : holder;
            if (value instanceof DataComponentType<?> component) {
                jammedComponent = castComponent(component);
            }
        }
    }

    /** 仅在客户端路径按需解析客户端类，避免专用服务器加载客户端符号。 */
    private static void resolveClient() {
        if (clientResolved) {
            return;
        }
        clientResolved = true;
        Class<?> timeless = type(TIMELESS_API);
        if (timeless != null) {
            getClientGunIndex = method(timeless, "getClientGunIndex", ResourceLocation.class);
            getGunDisplay = method(timeless, "getGunDisplay", ItemStack.class);
        }
        Class<?> clientIndex = type(CLIENT_GUN_INDEX);
        if (clientIndex != null) {
            clientIndexGetType = method(clientIndex, "getType");
        }
        Class<?> displayClass = type(GUN_DISPLAY_INSTANCE);
        if (displayClass != null) {
            displayGetSounds = method(displayClass, "getSounds", String.class);
        }
    }

    // ---- 判定 ------------------------------------------------------------

    /** 是否是 TaCZ 配件（{@code IAttachment} 或 {@code AttachmentItem}）。 */
    public static boolean isAttachmentItem(ItemStack stack) {
        resolve();
        if (stack.isEmpty()) {
            return false;
        }
        Object item = stack.getItem();
        return (attachmentInterface != null && attachmentInterface.isInstance(item))
                || (attachmentItemClass != null && attachmentItemClass.isInstance(item));
    }

    /** 是否是 TaCZ 枪械（{@code IGun} 实现）。 */
    public static boolean isGun(ItemStack stack) {
        resolve();
        return !stack.isEmpty() && iGun(stack) != null;
    }

    /** 全部 {@code AttachmentType} 枚举常量；TaCZ 缺席时为空数组。 */
    public static Object[] attachmentTypes() {
        resolve();
        return attachmentTypes;
    }

    /** {@code AttachmentType.EXTENDED_MAG} 枚举常量；TaCZ 缺席时为 {@code null}。 */
    public static Object extendedMagType() {
        resolve();
        return extendedMagType;
    }

    /** 该枚举常量是否是扩展弹匣。 */
    public static boolean isExtendedMag(Object attachmentType) {
        return attachmentType != null && EXTENDED_MAG.equalsIgnoreCase(String.valueOf(attachmentType));
    }

    /** 枪械的注册 id；不是枪或读取失败时为 {@code null}。 */
    public static ResourceLocation gunId(ItemStack gun) {
        resolve();
        Object igun = iGun(gun);
        if (igun == null || getGunId == null) {
            return null;
        }
        try {
            Object id = getGunId.invoke(igun, gun);
            return id instanceof ResourceLocation resourceLocation ? resourceLocation : null;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return null;
        }
    }

    /** 取某槽位的配件堆（空槽返回 {@code ItemStack.EMPTY}）。 */
    public static ItemStack attachment(ItemStack gun, Object attachmentType, HolderLookup.Provider lookup) {
        resolve();
        Object igun = iGun(gun);
        if (igun == null || getAttachment == null || attachmentType == null || lookup == null) {
            return ItemStack.EMPTY;
        }
        try {
            Object stack = getAttachment.invoke(igun, lookup, gun, attachmentType);
            return stack instanceof ItemStack itemStack ? itemStack : ItemStack.EMPTY;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return ItemStack.EMPTY;
        }
    }

    /** 卸下某槽位的配件（只改枪的 NBT，不返还物品）。 */
    public static boolean unloadAttachment(ItemStack gun, Object attachmentType, HolderLookup.Provider lookup) {
        resolve();
        Object igun = iGun(gun);
        if (igun == null || unloadAttachment == null || attachmentType == null || lookup == null) {
            return false;
        }
        try {
            unloadAttachment.invoke(igun, lookup, gun, attachmentType);
            return true;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return false;
        }
    }

    /** 扩展弹匣损坏时按 TaCZ 原生逻辑退回匣内子弹。 */
    public static boolean dropAllAmmo(Player player, ItemStack gun) {
        resolve();
        Object igun = iGun(gun);
        if (igun == null || dropAllAmmo == null || player == null) {
            return false;
        }
        try {
            dropAllAmmo.invoke(igun, player, gun);
            return true;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return false;
        }
    }

    /** 让 TaCZ 立刻重算该枪的附件缓存。 */
    public static boolean postChangeEvent(LivingEntity shooter, ItemStack gun) {
        resolve();
        if (postChangeEvent == null || shooter == null) {
            return false;
        }
        try {
            postChangeEvent.invoke(null, shooter, gun);
            return true;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return false;
        }
    }

    /** 从 {@code ModernKineticGunScriptAPI} 反射读取射手。 */
    public static LivingEntity scriptApiShooter(Object api) {
        resolve();
        if (api == null || scriptApiGetShooter == null) {
            return null;
        }
        try {
            Object value = scriptApiGetShooter.invoke(api);
            return value instanceof LivingEntity living ? living : null;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return null;
        }
    }

    /** 从 {@code ModernKineticGunScriptAPI} 反射读取枪械 ItemStack。 */
    public static ItemStack scriptApiItemStack(Object api) {
        resolve();
        if (api == null || scriptApiGetItemStack == null) {
            return ItemStack.EMPTY;
        }
        try {
            Object value = scriptApiGetItemStack.invoke(api);
            return value instanceof ItemStack stack ? stack : ItemStack.EMPTY;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return ItemStack.EMPTY;
        }
    }

    /** gundb 的 {@code ModDataComponents.JAMMED} 是否为 true。 */
    public static boolean isJammed(ItemStack stack) {
        resolve();
        if (jammedComponent == null || stack.isEmpty()) {
            return false;
        }
        try {
            Object value = stack.getOrDefault(jammedComponent, Boolean.FALSE);
            return value instanceof Boolean bool && bool;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    /** 该实体是否正在换弹。 */
    public static boolean isReloading(LivingEntity entity) {
        resolve();
        if (entity == null || fromLivingEntity == null || getSynReloadState == null
                || getStateType == null || stateTypeIsReloading == null) {
            return false;
        }
        try {
            Object operator = fromLivingEntity.invoke(null, entity);
            if (operator == null) {
                return false;
            }
            Object reloadState = getSynReloadState.invoke(operator);
            if (reloadState == null) {
                return false;
            }
            Object stateType = getStateType.invoke(reloadState);
            Object reloading = stateTypeIsReloading.invoke(stateType);
            return reloading instanceof Boolean bool && bool;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return false;
        }
    }

    /**
     * 枪型字符串（TaCZ 索引里的 {@code type}，如 {@code "pistol"}/{@code "smg"}/{@code "rifle"}）。
     *
     * <p>优先查双端通用的 {@code CommonGunIndex}；仅在双端索引缺席时回退查 {@code ClientGunIndex}。</p>
     */
    public static String gunType(ItemStack gun) {
        resolve();
        ResourceLocation id = gunId(gun);
        if (id == null) {
            return "";
        }
        String commonType = indexType(getCommonGunIndex, commonIndexGetType, id);
        if (!commonType.isEmpty()) {
            return commonType;
        }
        resolveClient();
        return indexType(getClientGunIndex, clientIndexGetType, id);
    }

    /**
     * 仅客户端调用：判断 {@code soundName} 是否是这把枪的 {@code "unjam"} 专属排障音效
     * （对应 gundb {@code InspectKeybindHook} 的路径 1 排障音效）。
     */
    public static boolean isUnjamSound(ItemStack gun, ResourceLocation soundName) {
        if (gun.isEmpty() || soundName == null) {
            return false;
        }
        resolveClient();
        if (getGunDisplay == null || displayGetSounds == null) {
            return false;
        }
        try {
            Object optional = getGunDisplay.invoke(null, gun);
            if (!(optional instanceof Optional<?> value) || value.isEmpty()) {
                return false;
            }
            Object unjamSound = displayGetSounds.invoke(value.get(), "unjam");
            return unjamSound instanceof ResourceLocation expected && Objects.equals(soundName, expected);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return false;
        }
    }

    private static String indexType(Method getIndex, Method getType, ResourceLocation id) {
        if (getIndex == null || getType == null) {
            return "";
        }
        try {
            Object optional = getIndex.invoke(null, id);
            if (!(optional instanceof Optional<?> value) || value.isEmpty()) {
                return "";
            }
            Object type = getType.invoke(value.get());
            return type instanceof String string ? string.toLowerCase(Locale.ROOT) : "";
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return "";
        }
    }

    /**
     * 实体主手那把枪的隐藏经验等级（0..50）。
     */
    public static int levelOfHeldGun(Entity entity, ResourceLocation expectedGunId) {
        if (!(entity instanceof LivingEntity living)) {
            return 0;
        }
        ItemStack held = living.getMainHandItem();
        if (held.isEmpty() || !isGun(held)) {
            return 0;
        }
        ResourceLocation heldId = gunId(held);
        if (expectedGunId != null && heldId != null && !expectedGunId.equals(heldId)) {
            return 0;
        }
        return GunsmithData.getLevel(held);
    }

    // ---- 内部 ------------------------------------------------------------

    private static Object iGun(ItemStack stack) {
        if (getIGunOrNull == null || stack.isEmpty()) {
            return null;
        }
        try {
            return getIGunOrNull.invoke(null, stack);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static DataComponentType<Object> castComponent(DataComponentType<?> component) {
        return (DataComponentType<Object>) component;
    }

    private static Class<?> type(String className) {
        try {
            return Class.forName(className);
        } catch (ClassNotFoundException | LinkageError | RuntimeException ignored) {
            return null;
        }
    }

    private static Method method(Class<?> owner, String name, Class<?>... parameterTypes) {
        try {
            return owner.getMethod(name, parameterTypes);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    private static Object field(Class<?> owner, String name) {
        try {
            return owner.getField(name).get(null);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    private GunsmithAccess() {
    }
}
