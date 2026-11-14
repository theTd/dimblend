package dimblend.dragonchase.chase;

import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * The two vanilla advancements the chase hands out (requirements.md §3.2, §3.6; decision
 * end-advancement keeps "The End?" where it is, so {@code end/root} is deliberately absent here).
 *
 * <p>Criterion names are read from the shipped advancement files:
 * {@code data/minecraft/advancement/end/kill_dragon.json} has the single criterion
 * {@code killed_dragon}; {@code .../end/respawn_dragon.json} has {@code summoned_dragon}. Each file
 * has exactly one requirement group, so awarding that criterion completes the advancement.
 *
 * <p>Awarding a child does not need {@code end/root} granted: the client shows ancestors as
 * completed once a descendant is done, and {@code PlayerAdvancements#award} only writes the holder
 * it is given (vanilla {@code PlayerAdvancements#award}).
 */
final class ChaseAdvancements {
    static final ResourceLocation KILL_DRAGON = ResourceLocation.withDefaultNamespace("end/kill_dragon");
    static final String KILL_DRAGON_CRITERION = "killed_dragon";
    static final ResourceLocation RESPAWN_DRAGON = ResourceLocation.withDefaultNamespace("end/respawn_dragon");
    static final String RESPAWN_DRAGON_CRITERION = "summoned_dragon";

    /** Awards to every player of this level within {@code radius} blocks (horizontal) of the point. */
    static void grantWithin(ServerLevel level, double centerX, double centerZ, ResourceLocation advancement,
            String criterion) {
        AdvancementHolder holder = level.getServer().getAdvancements().get(advancement);
        if (holder == null) {
            return;
        }
        for (ServerPlayer player : level.players()) {
            if (ChaseMath.withinRadius(player.getX(), player.getZ(), centerX, centerZ, ChaseMath.ADVANCEMENT_RADIUS)) {
                player.getAdvancements().award(holder, criterion);
            }
        }
    }

    private ChaseAdvancements() {
    }
}
