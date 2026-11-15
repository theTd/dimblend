package dimblend.compat;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.EntityTypeTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;

/** Optional Voidscape integration for the Infuser's world-wide undead effect. */
public final class VoidscapeUndeadInfusion {
    public static final String MARK = "dimblend:void_infused_undead";
    /** Keeps remote undead strictly below Insanity.MAX_INFUSION (600) so Voidscape never deals 1024 lethal damage. */
    public static final float MAX_SAFE_REMOTE_INFUSION = 590.0F;
    private static final String FX_TICK = "dimblend:last_undead_infuse_fx";
    private static final ResourceLocation ESSENCE =
            ResourceLocation.fromNamespaceAndPath("voidscape", "ethereal_essence");
    private static final ThreadLocal<Boolean> REMOTE_PROCESSED = ThreadLocal.withInitial(() -> false);
    private static volatile Handles cachedHandles;
    private static volatile boolean handlesResolved;

    private record Handles(
            Field processTickField,
            Field dataAttachmentsField,
            Field insanityField,
            Method supplierGet,
            Method getInfusion,
            Method addInfusion,
            Method setInfusion,
            Constructor<?> spellCloudOptionsCtor) {}

    private VoidscapeUndeadInfusion() {}

    public static boolean shouldDropBonusEssence(float roll) {
        return roll < 0.10F;
    }

    public static float clampRemoteInfusion(float infusion) {
        return Math.min(infusion, MAX_SAFE_REMOTE_INFUSION);
    }

    public static void onInfuserTickHead(Level level, BlockPos pos, BlockEntity be) {
        REMOTE_PROCESSED.set(false);
        if (!(level instanceof ServerLevel server) || level.hasNeighborSignal(pos)) {
            return;
        }
        Handles handles = handles();
        if (handles == null || readProcessTick(handles, be) != 1) {
            return;
        }
        AABB local = new AABB(pos).inflate(6D);
        level.getEntities(
                        (Entity) null,
                        local,
                        entity -> entity instanceof LivingEntity living
                                && living.isAlive()
                                && living.getType().is(EntityTypeTags.UNDEAD))
                .forEach(entity -> entity.getPersistentData().putBoolean(MARK, true));
        REMOTE_PROCESSED.set(infuseRemoteUndead(handles, server, local));
    }

    public static boolean mergeProcessResult(AtomicBoolean localProcess) {
        boolean remote = REMOTE_PROCESSED.get();
        REMOTE_PROCESSED.remove();
        return localProcess.get() || remote;
    }

    private static int readProcessTick(Handles handles, BlockEntity be) {
        try {
            return handles.processTickField().getInt(be);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return -1;
        }
    }

    private static boolean infuseRemoteUndead(Handles handles, ServerLevel source, AABB local) {
        boolean processed = false;
        for (ServerLevel level : source.getServer().getAllLevels()) {
            for (Entity entity : level.getAllEntities()) {
                if (!(entity instanceof LivingEntity living)
                        || !living.isAlive()
                        || !living.getType().is(EntityTypeTags.UNDEAD)) {
                    continue;
                }
                if (level == source && local.intersects(living.getBoundingBox())) {
                    continue;
                }
                if (applyInfusion(handles, living)) {
                    living.getPersistentData().putBoolean(MARK, true);
                    emitParticles(handles, level, living);
                    processed = true;
                }
            }
        }
        return processed;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static boolean applyInfusion(Handles handles, LivingEntity living) {
        try {
            Object holder = handles.dataAttachmentsField().get(null);
            if (holder == null) {
                return false;
            }
            Object supplier = handles.insanityField().get(holder);
            AttachmentType attachment = (AttachmentType) handles.supplierGet().invoke(supplier);
            Object data = living.getData(attachment);
            float infusion = ((Number) handles.getInfusion().invoke(data)).floatValue();
            if (infusion < 200.0F) {
                handles.addInfusion().invoke(data, 250.0F, living);
                float updated = ((Number) handles.getInfusion().invoke(data)).floatValue();
                float safe = clampRemoteInfusion(updated);
                if (safe < updated) {
                    handles.setInfusion().invoke(data, safe);
                }
            }
            return true;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return false;
        }
    }

    private static void emitParticles(Handles handles, ServerLevel level, LivingEntity living) {
        long now = level.getGameTime();
        long previous = living.getPersistentData().getLong(FX_TICK);
        if (now - previous < 20L) {
            return;
        }
        living.getPersistentData().putLong(FX_TICK, now);
        try {
            ParticleOptions options = (ParticleOptions) handles.spellCloudOptionsCtor().newInstance(0x7700FF);
            level.sendParticles(
                    options,
                    living.getX(),
                    living.getY() + living.getBbHeight() * 0.5D,
                    living.getZ(),
                    8,
                    living.getBbWidth() * 0.4D,
                    living.getBbHeight() * 0.4D,
                    living.getBbWidth() * 0.4D,
                    0.0D);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // Optional particle class is unavailable when Voidscape is absent.
        }
    }

    private static Handles handles() {
        if (handlesResolved) {
            return cachedHandles;
        }
        synchronized (VoidscapeUndeadInfusion.class) {
            if (handlesResolved) {
                return cachedHandles;
            }
            try {
                Class<?> infuserClass = Class.forName("tamaized.voidscape.block.entity.InfuserBlockEntity");
                Field processTickField = infuserClass.getDeclaredField("processTick");
                processTickField.setAccessible(true);
                Field dataAttachmentsField = infuserClass.getDeclaredField("dataAttachments");
                dataAttachmentsField.setAccessible(true);

                Class<?> attachmentsClass = Class.forName("tamaized.voidscape.registry.ModDataAttachments");
                Field insanityField = attachmentsClass.getDeclaredField("INSANITY");
                insanityField.setAccessible(true);
                Method supplierGet = insanityField.getType().getMethod("get");

                Class<?> insanityClass = Class.forName("tamaized.voidscape.data.Insanity");
                Method getInfusion = insanityClass.getMethod("getInfusion");
                Method addInfusion = insanityClass.getMethod("addInfusion", float.class, LivingEntity.class);
                Method setInfusion = insanityClass.getMethod("setInfusion", float.class);

                Class<?> optionsClass = Class.forName("tamaized.voidscape.particle.ParticleTypeSpellCloud$Options");
                Constructor<?> spellCloudOptionsCtor = optionsClass.getDeclaredConstructor(int.class);
                spellCloudOptionsCtor.setAccessible(true);

                cachedHandles = new Handles(
                        processTickField,
                        dataAttachmentsField,
                        insanityField,
                        supplierGet,
                        getInfusion,
                        addInfusion,
                        setInfusion,
                        spellCloudOptionsCtor);
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                cachedHandles = null;
            }
            handlesResolved = true;
            return cachedHandles;
        }
    }

    public static void onLivingDrops(LivingDropsEvent event) {
        LivingEntity entity = event.getEntity();
        if (entity.level().isClientSide()
                || !entity.getType().is(EntityTypeTags.UNDEAD)
                || !entity.getPersistentData().getBoolean(MARK)
                || !shouldDropBonusEssence(entity.getRandom().nextFloat())) {
            return;
        }
        BuiltInRegistries.ITEM.getOptional(ESSENCE).ifPresent(item -> event.getDrops().add(
                new ItemEntity(entity.level(), entity.getX(), entity.getY(), entity.getZ(), new ItemStack(item, 1))));
    }
}
