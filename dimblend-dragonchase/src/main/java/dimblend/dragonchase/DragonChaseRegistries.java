package dimblend.dragonchase;

import dimblend.dragonchase.entity.ChaseDragon;
import dimblend.dragonchase.entity.ChaseFireball;
import dimblend.dragonchase.entity.CrystalBeams;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.jetbrains.annotations.Nullable;

/**
 * Entity types and network payloads of the module (requirements.md §3.2-§3.4).
 *
 * <p>Both entity types are {@link MobCategory#MISC}: the dragon must not consume the monster mob
 * cap ({@code NaturalSpawner} only counts categories other than MISC), and the fireball is a
 * projectile. Registration lives here so {@code DimBlendDragonChase} stays pure wiring.
 */
public final class DragonChaseRegistries {
    private static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
            DeferredRegister.create(Registries.ENTITY_TYPE, DimBlendDragonChase.MODID);

    /**
     * The chase / prologue dragon. Vanilla-size hitbox ({@code EntityType.java:319-322}) and
     * {@code fireImmune} for §3.4, but a 16-chunk tracking range (256 blocks) instead of vanilla's
     * 10: the chase gap between the dragon above the nest and the players behind it is 128-160+
     * blocks, so 10 chunks would make the dragon pop out of view mid-chase.
     */
    public static final DeferredHolder<EntityType<?>, EntityType<ChaseDragon>> CHASE_DRAGON =
            ENTITY_TYPES.register("chase_dragon", id -> EntityType.Builder.of(ChaseDragon::new, MobCategory.MISC)
                    .fireImmune()
                    .sized(16.0F, 8.0F)
                    .passengerAttachments(3.0F)
                    .clientTrackingRange(16)
                    .build(id.toString()));

    /**
     * The scripted breath shot; rendered by the vanilla dragon-fireball renderer. 16 chunks and a
     * 2-tick update interval so the shot stays visible and smooth across the 128+ block gap the
     * vanilla 4-chunk/10-tick settings cannot cover.
     */
    public static final DeferredHolder<EntityType<?>, EntityType<ChaseFireball>> DRAGON_FIREBALL =
            ENTITY_TYPES.register("dragon_fireball", id -> EntityType.Builder.<ChaseFireball>of(ChaseFireball::new, MobCategory.MISC)
                    .sized(1.0F, 1.0F)
                    .clientTrackingRange(16)
                    .updateInterval(2)
                    .build(id.toString()));

    public static void register(IEventBus modEventBus) {
        ENTITY_TYPES.register(modEventBus);
        modEventBus.addListener(DragonChaseRegistries::registerEntityAttributes);
        modEventBus.addListener(DragonChaseRegistries::registerPayloads);
    }

    private static void registerEntityAttributes(EntityAttributeCreationEvent event) {
        // DefaultAttributes.validate() demands a supplier for every living entity type.
        event.put(CHASE_DRAGON.get(), ChaseDragon.createAttributes().build());
    }

    private static void registerPayloads(RegisterPayloadHandlersEvent event) {
        event.registrar("1").playToClient(CrystalBeams.TYPE, CrystalBeams.STREAM_CODEC, CrystalBeams::handle);
    }

    /** {@code null} while the deferred holder is not bound yet (during early entity construction). */
    @Nullable
    public static EntityType<ChaseDragon> chaseDragonType() {
        return CHASE_DRAGON.isBound() ? CHASE_DRAGON.get() : null;
    }

    private DragonChaseRegistries() {
    }
}
