package dimblend.dragonchase.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import dimblend.DimBlendRegistries;
import dimblend.dragonchase.chase.ChaseDirector;
import dimblend.dragonchase.chase.ChaseMath;
import dimblend.dragonchase.chase.DragonChaseSavedData;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import org.jetbrains.annotations.Nullable;

/**
 * {@code /dragonchase} — operator tooling to walk every branch of requirements.md §3.2-§3.6 in game
 * (permission level 2). Each action is the same director code the real trigger runs, only without
 * its precondition, so a tested branch is the shipped branch.
 *
 * <p>All feedback is translatable under {@code command.dimblend_dragonchase.*}.
 */
public final class DragonChaseCommand {
    public static void register(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(Commands.literal("dragonchase")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("status").executes(DragonChaseCommand::status))
                .then(Commands.literal("prologue").executes(DragonChaseCommand::prologue))
                .then(Commands.literal("nest").executes(DragonChaseCommand::nest))
                .then(Commands.literal("damage")
                        .then(Commands.argument("amount", FloatArgumentType.floatArg(0.0F))
                                .executes(DragonChaseCommand::damage)))
                .then(Commands.literal("fireball").executes(DragonChaseCommand::fireball))
                .then(Commands.literal("breath").executes(DragonChaseCommand::breath))
                .then(Commands.literal("kill").executes(DragonChaseCommand::kill))
                .then(Commands.literal("fail").executes(DragonChaseCommand::fail))
                .then(Commands.literal("reset").executes(DragonChaseCommand::reset))
                .then(Commands.literal("generation")
                        .then(Commands.argument("value", IntegerArgumentType.integer(1))
                                .executes(DragonChaseCommand::generation))));
    }

    private static int status(CommandContext<CommandSourceStack> context) {
        ServerLevel level = rotating(context.getSource());
        if (level == null) {
            return noLevel(context.getSource());
        }
        DragonChaseSavedData data = DragonChaseSavedData.get(level);
        String nest = data.nestId != null
                ? Integer.toString(data.nestCenterX)
                : data.pendingNestX != null ? data.pendingNestX.toString() : "-";
        say(context.getSource(), "status", data.phase.name(), data.generation, nest, dragonHealth(level, data));
        return 1;
    }

    private static int prologue(CommandContext<CommandSourceStack> context) {
        ServerLevel level = rotating(context.getSource());
        if (level == null) {
            return noLevel(context.getSource());
        }
        Vec3 near = context.getSource().getPosition();
        return ChaseDirector.debugPrologue(level, near.x, near.z)
                ? success(context.getSource(), "prologue")
                : failure(context.getSource(), "no_train");
    }

    private static int nest(CommandContext<CommandSourceStack> context) {
        ServerLevel level = rotating(context.getSource());
        if (level == null) {
            return noLevel(context.getSource());
        }
        Vec3 near = context.getSource().getPosition();
        return ChaseDirector.debugNest(level, near.x, near.z)
                ? success(context.getSource(), "nest")
                : failure(context.getSource(), "no_train");
    }

    private static int damage(CommandContext<CommandSourceStack> context) {
        ServerLevel level = rotating(context.getSource());
        if (level == null) {
            return noLevel(context.getSource());
        }
        DragonChaseSavedData data = DragonChaseSavedData.get(level);
        Entity entity = data.dragonId == null ? null : level.getEntity(data.dragonId);
        if (!(entity instanceof LivingEntity living) || !living.isAlive()) {
            return failure(context.getSource(), "no_dragon");
        }
        float amount = FloatArgumentType.getFloat(context, "amount");
        // W3's dragon routes this through its own hurt path, so the per-second cap and onDamageTaken
        // behave exactly as they do for a player hit.
        living.hurt(level.damageSources().magic(), amount);
        say(context.getSource(), "damage", ChaseMath.tenths(amount));
        return 1;
    }

    private static int fireball(CommandContext<CommandSourceStack> context) {
        ServerLevel level = rotating(context.getSource());
        if (level == null) {
            return noLevel(context.getSource());
        }
        return ChaseDirector.debugFireball(level)
                ? success(context.getSource(), "fireball")
                : failure(context.getSource(), "no_chase");
    }

    private static int breath(CommandContext<CommandSourceStack> context) {
        ServerLevel level = rotating(context.getSource());
        if (level == null) {
            return noLevel(context.getSource());
        }
        return ChaseDirector.debugBreath(level)
                ? success(context.getSource(), "breath")
                : failure(context.getSource(), "no_chase");
    }

    private static int kill(CommandContext<CommandSourceStack> context) {
        ServerLevel level = rotating(context.getSource());
        if (level == null) {
            return noLevel(context.getSource());
        }
        return ChaseDirector.debugKill(level)
                ? success(context.getSource(), "kill")
                : failure(context.getSource(), "no_chase");
    }

    private static int fail(CommandContext<CommandSourceStack> context) {
        ServerLevel level = rotating(context.getSource());
        if (level == null) {
            return noLevel(context.getSource());
        }
        return ChaseDirector.debugFail(level)
                ? success(context.getSource(), "fail")
                : failure(context.getSource(), "no_chase");
    }

    private static int reset(CommandContext<CommandSourceStack> context) {
        ServerLevel level = rotating(context.getSource());
        if (level == null) {
            return noLevel(context.getSource());
        }
        ChaseDirector.debugReset(level);
        return success(context.getSource(), "reset");
    }

    private static int generation(CommandContext<CommandSourceStack> context) {
        ServerLevel level = rotating(context.getSource());
        if (level == null) {
            return noLevel(context.getSource());
        }
        int value = IntegerArgumentType.getInteger(context, "value");
        ChaseDirector.debugSetGeneration(level, value);
        say(context.getSource(), "generation", value);
        return 1;
    }

    private static String dragonHealth(ServerLevel level, DragonChaseSavedData data) {
        Entity entity = data.dragonId == null ? null : level.getEntity(data.dragonId);
        if (entity instanceof LivingEntity living) {
            return ChaseMath.tenths(living.getHealth()) + "/" + ChaseMath.tenths(living.getMaxHealth());
        }
        return "-";
    }

    @Nullable
    private static ServerLevel rotating(CommandSourceStack source) {
        return source.getServer().getLevel(DimBlendRegistries.ROTATING_LEVEL);
    }

    private static int noLevel(CommandSourceStack source) {
        return failure(source, "no_level");
    }

    private static int success(CommandSourceStack source, String key) {
        say(source, key);
        return 1;
    }

    private static int failure(CommandSourceStack source, String key) {
        say(source, key);
        return 0;
    }

    private static void say(CommandSourceStack source, String key, Object... arguments) {
        source.sendSuccess(() -> Component.translatable("command.dimblend_dragonchase." + key, arguments), false);
    }

    private DragonChaseCommand() {
    }
}
