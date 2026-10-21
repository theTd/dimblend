package dimblend.experience.gametest;

import com.mojang.authlib.GameProfile;
import dimblend.experience.Config;
import dimblend.experience.exploration.BedRestRules;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.Difficulty;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Run with -PwithSleepRest; exercises the actual woven player/world paths. */
@PrefixGameTestTemplate(false)
public final class BedRestGameTests {
    private static final BlockPos HEAD = new BlockPos(1, 1, 1);

    @GameTest(template = "empty", templateNamespace = "dimblend_sleep", timeoutTicks = 60)
    public static void daytimeAndHostileMobsAllowRestButInvalidBedsDoNot(GameTestHelper helper) {
        var level = helper.getLevel();
        long time = level.getDayTime();
        var player = player(helper);
        var pos = placeBed(helper);
        try {
            level.setDayTime(6000);
            level.updateSkyBrightness();
            useBed(player, pos);
            helper.assertTrue(player.isSleeping(), "daytime bed interaction must start resting");
            tick(player, 110);
            helper.assertTrue(player.isSleeping(), "daytime must not wake a resting player");
            player.stopSleepInBed(true, true);

            level.setDayTime(18000);
            level.updateSkyBrightness();
            var zombie = EntityType.ZOMBIE.create(level);
            helper.assertTrue(zombie != null, "zombie fixture must exist");
            zombie.setPos(pos.getCenter().add(2, 0, 0));
            level.addFreshEntity(zombie);
            try {
                helper.assertTrue(player.startSleepInBed(pos).right().isPresent(),
                        "nearby hostile mobs must not reject resting");
            } finally {
                zombie.discard();
            }
            player.stopSleepInBed(true, true);

            player.setPos(pos.getCenter().add(20, 0, 0));
            helper.assertTrue(player.startSleepInBed(pos).left().orElse(null) == Player.BedSleepingProblem.TOO_FAR_AWAY,
                    "bed distance checks must stay intact");
            player.setPos(pos.getCenter().add(0, 1, 0));
            level.setBlock(pos.above(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
            helper.assertTrue(player.startSleepInBed(pos).left().orElse(null) == Player.BedSleepingProblem.OBSTRUCTED,
                    "obstructed beds must remain invalid");
            level.setBlock(pos.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);

            var nether = level.getServer().getLevel(Level.NETHER);
            helper.assertTrue(nether != null && !BedRestRules.applies(nether) && !BedBlock.canSetSpawn(nether),
                    "explosive dimensions must retain their bed behavior");

            player.startSleepInBed(pos);
            level.removeBlock(pos, false);
            tick(player, 1);
            helper.assertTrue(!player.isSleeping(), "removing the bed must still wake its sleeper");
            helper.succeed();
        } finally {
            if (player.isSleeping()) player.stopSleepInBed(true, true);
            level.setDayTime(time);
            level.updateSkyBrightness();
        }
    }

    @GameTest(template = "empty", templateNamespace = "dimblend_sleep", timeoutTicks = 60)
    public static void peacefulRestWaitsWarmupThenGrantsRegeneration(GameTestHelper helper) {
        var level = helper.getLevel();
        var difficulty = level.getDifficulty();
        var player = player(helper);
        var pos = placeBed(helper);
        try {
            level.getServer().setDifficulty(Difficulty.PEACEFUL, true);
            player.setHealth(10);
            player.startSleepInBed(pos);
            tick(player, 99);
            helper.assertTrue(player.getHealth() == 10 && !player.hasEffect(MobEffects.REGENERATION),
                    "peaceful automatic healing must wait during rest");
            tick(player, 1);
            var granted = player.getEffect(MobEffects.REGENERATION);
            helper.assertTrue(granted != null && granted.getAmplifier() == 1,
                    "rest grants regeneration II after the 100-tick warmup");
            helper.assertTrue(granted.getDuration() > 550 && granted.getDuration() <= 600,
                    "rest regeneration lasts thirty seconds, got " + granted.getDuration());
            tick(player, 1);
            helper.assertTrue(player.getHealth() == 11,
                    "the first regeneration pulse lands on the tick right after the grant");
            tick(player, 25);
            helper.assertTrue(player.getHealth() == 12, "regeneration II pulses every 25 ticks");
            helper.succeed();
        } finally {
            if (player.isSleeping()) player.stopSleepInBed(true, true);
            level.getServer().setDifficulty(difficulty, true);
        }
    }

    @GameTest(template = "empty", templateNamespace = "dimblend_sleep", timeoutTicks = 60)
    public static void restFreezesHungerAndRegeneratesThenAwakeDepletionResumes(GameTestHelper helper) {
        var level = helper.getLevel();
        var player = player(helper);
        var pos = placeBed(helper);
        player.setHealth(10);
        var food = player.getFoodData();
        food.setFoodLevel(20);
        food.setSaturation(5);
        food.setExhaustion(6);
        player.addEffect(new MobEffectInstance(MobEffects.HUNGER, 1000, 1));
        try {
            helper.assertTrue(player.startSleepInBed(pos).right().isPresent(), "rest must start");
            tick(player, 99);
            helper.assertTrue(player.getHealth() == 10 && !player.hasEffect(MobEffects.REGENERATION),
                    "rest grants no regeneration before the warmup completes");
            tick(player, 1);
            helper.assertTrue(player.hasEffect(MobEffects.REGENERATION),
                    "rest grants regeneration II at its 100th tick");
            helper.assertTrue(food.getFoodLevel() == 20 && food.getSaturationLevel() == 5
                            && food.getExhaustionLevel() == 6,
                    "sleep freezes hunger, saturation and exhaustion even with the hunger effect");

            food.setFoodLevel(0);
            food.setSaturation(0);
            tick(player, 100);
            helper.assertTrue(player.getHealth() == 14 && food.getFoodLevel() == 0,
                    "rest regeneration works at zero hunger without starvation damage");

            player.stopSleepInBed(true, true);
            food.setFoodLevel(20);
            food.setSaturation(2);
            tick(player, 1);
            helper.assertTrue(food.getSaturationLevel() < 2,
                    "normal hunger processing resumes after leaving bed");
            player.setHealth(10);
            helper.assertTrue(player.startSleepInBed(pos).right().isPresent(), "player can rest again");
            tick(player, 99);
            var lingering = player.getEffect(MobEffects.REGENERATION);
            helper.assertTrue(lingering == null || lingering.getDuration() < 500,
                    "a new rest must wait another full warmup before re-granting regeneration");
            tick(player, 1);
            var refreshed = player.getEffect(MobEffects.REGENERATION);
            helper.assertTrue(refreshed != null && refreshed.getDuration() > 550,
                    "the new rest re-grants a full thirty seconds at its own 100th tick");
            helper.succeed();
        } finally {
            if (player.isSleeping()) player.stopSleepInBed(true, true);
        }
    }

    @GameTest(template = "empty", templateNamespace = "dimblend_sleep", timeoutTicks = 60)
    public static void heartsWaitForWarmupAndPulseAtTheBedFoot(GameTestHelper helper) {
        var level = helper.getLevel();
        List<ClientboundLevelParticlesPacket> hearts = new ArrayList<>();
        var player = player(helper, packet -> {
            if (packet instanceof ClientboundLevelParticlesPacket particles
                    && particles.getParticle().getType() == ParticleTypes.HEART) hearts.add(particles);
        });
        var pos = placeBed(helper);
        player.setHealth(10);
        level.players().add(player);
        try {
            helper.assertTrue(player.startSleepInBed(pos).right().isPresent(), "rest must start");
            tick(player, 99);
            helper.assertTrue(hearts.isEmpty() && player.getHealth() == 10,
                    "regeneration and heart feedback must both wait the first five seconds");
            tick(player, 1);
            helper.assertTrue(hearts.size() == 1 && hearts.getFirst().getCount() == 3,
                    "the warmed-up rest emits three hearts every second");
            var burst = hearts.getFirst();
            // 床 FACING=EAST：脚在西侧一格；散发中心须落在床尾上方，第一人称才看得到
            var footCenter = helper.absolutePos(HEAD).west().getCenter().add(0.0, 0.6, 0.0);
            helper.assertTrue(footCenter.distanceToSqr(burst.getX(), burst.getY(), burst.getZ()) < 1.0e-6,
                    "feedback must originate above the foot of the bed");
            tick(player, 19);
            helper.assertTrue(hearts.size() == 1, "heart bursts must be spaced one second apart");
            tick(player, 1);
            helper.assertTrue(hearts.size() == 2 && hearts.getLast().getCount() == 3,
                    "rest shows periodic hearts between regeneration pulses");

            player.setHealth(player.getMaxHealth());
            tick(player, 79);
            helper.assertTrue(hearts.size() == 2 + 3, "full health must not reduce particle frequency");
            tick(player, 1);
            helper.assertTrue(hearts.size() == 2 + 4 && hearts.getLast().getCount() == 3,
                    "full health retains three hearts per pulse");
            tick(player, 100);
            helper.assertTrue(hearts.size() == 2 + 4 + 5
                            && player.getHealth() == player.getMaxHealth(),
                    "full-health heart feedback continues without increasing health past its maximum");
            helper.succeed();
        } finally {
            if (player.isSleeping()) player.stopSleepInBed(true, true);
            level.players().remove(player);
            level.updateSleepingPlayerList();
        }
    }

    @GameTest(template = "empty", templateNamespace = "dimblend_sleep", timeoutTicks = 60)
    public static void wakingAndImmediateReentryResetWarmupAndHearts(GameTestHelper helper) {
        var level = helper.getLevel();
        List<ClientboundLevelParticlesPacket> hearts = new ArrayList<>();
        var player = player(helper, packet -> {
            if (packet instanceof ClientboundLevelParticlesPacket particles
                    && particles.getParticle().getType() == ParticleTypes.HEART) hearts.add(particles);
        });
        var pos = placeBed(helper);
        player.setHealth(10);
        level.players().add(player);
        try {
            player.startSleepInBed(pos);
            tick(player, 150);
            helper.assertTrue(hearts.size() == 3 && player.getHealth() == 12,
                    "fixture must reach warmed-up rest with regeneration running");
            player.stopSleepInBed(true, true);
            int previous = hearts.size();
            // Re-enter without an intervening awake tick: the wake hook must clear both timers.
            player.startSleepInBed(pos);
            tick(player, 99);
            helper.assertTrue(hearts.size() == previous,
                    "immediate reentry must wait another complete warmup for hearts");
            tick(player, 1);
            helper.assertTrue(hearts.size() == previous + 1,
                    "the new rest restarts heart feedback at its own 100th tick");
            player.stopSleepInBed(true, true);
            previous = hearts.size();
            tick(player, 20);
            helper.assertTrue(hearts.size() == previous, "awake players must not retain rest particles");

            player.startSleepInBed(pos);
            player.setHealth(0);
            tick(player, 100);
            helper.assertTrue(hearts.size() == previous && player.getHealth() == 0,
                    "rest must neither revive dead players nor emit recovery feedback for them");
            helper.succeed();
        } finally {
            if (player.isSleeping()) player.stopSleepInBed(true, true);
            level.players().remove(player);
            level.updateSleepingPlayerList();
        }
    }

    @GameTest(template = "empty", templateNamespace = "dimblend_sleep", timeoutTicks = 60)
    public static void fullSleepQuorumNeverSkipsTimeOrForcesWakeUp(GameTestHelper helper) {
        var level = helper.getLevel();
        var rules = level.getGameRules();
        boolean daylight = rules.getBoolean(GameRules.RULE_DAYLIGHT);
        int percentage = rules.getInt(GameRules.RULE_PLAYERS_SLEEPING_PERCENTAGE);
        long time = level.getDayTime();
        var player = player(helper);
        var pos = placeBed(helper);
        rules.getRule(GameRules.RULE_DAYLIGHT).set(true, level.getServer());
        rules.getRule(GameRules.RULE_PLAYERS_SLEEPING_PERCENTAGE).set(0, level.getServer());
        level.setDayTime(18000);
        level.updateSkyBrightness();
        level.players().add(player);
        player.startSleepInBed(pos);
        tick(player, 110);
        helper.assertTrue(player.isSleepingLongEnough(), "fixture must reach vanilla deep-sleep quorum");
        level.updateSleepingPlayerList();
        long started = level.getGameTime();
        helper.runAfterDelay(5, () -> {
            try {
                helper.assertTrue(level.getDayTime() == 18000 + level.getGameTime() - started,
                        "all players sleeping must leave the normal day/night clock running");
                helper.assertTrue(player.isSleeping(), "reaching the sleep quorum must not force wake-up");
                rules.getRule(GameRules.RULE_DAYLIGHT).set(false, level.getServer());
                long stopped = level.getDayTime();
                helper.runAfterDelay(5, () -> {
                    try {
                        helper.assertTrue(level.getDayTime() == stopped && player.isSleeping(),
                                "disabled daylight cycle must not repeatedly force sleepers awake");
                        helper.succeed();
                    } finally {
                        cleanupTimeTest(helper, player, daylight, percentage, time);
                    }
                });
            } catch (RuntimeException | Error failure) {
                cleanupTimeTest(helper, player, daylight, percentage, time);
                throw failure;
            }
        });
    }

    @GameTest(template = "empty", templateNamespace = "dimblend_sleep", timeoutTicks = 60)
    public static void rotatingGroundBedRestrictionIsPreserved(GameTestHelper helper) {
        String dimension = Config.ROTATING_DIMENSION_ID.get();
        boolean restriction = Config.STRUCTURE_BED.get();
        var player = player(helper);
        var pos = placeBed(helper);
        try {
            Config.ROTATING_DIMENSION_ID.set("minecraft:overworld");
            Config.STRUCTURE_BED.set(true);
            useBed(player, pos);
            helper.assertTrue(!player.isSleeping(), "rotating ground beds remain unavailable in survival");
            Config.STRUCTURE_BED.set(false);
            useBed(player, pos);
            helper.assertTrue(player.isSleeping(), "turning off the existing bed restriction permits resting");
            helper.succeed();
        } finally {
            if (player.isSleeping()) player.stopSleepInBed(true, true);
            Config.ROTATING_DIMENSION_ID.set(dimension);
            Config.STRUCTURE_BED.set(restriction);
        }
    }

    private static void cleanupTimeTest(GameTestHelper helper, ServerPlayer player,
            boolean daylight, int percentage, long time) {
        var level = helper.getLevel();
        if (player.isSleeping()) player.stopSleepInBed(true, true);
        level.players().remove(player);
        level.updateSleepingPlayerList();
        level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(daylight, level.getServer());
        level.getGameRules().getRule(GameRules.RULE_PLAYERS_SLEEPING_PERCENTAGE).set(percentage, level.getServer());
        level.setDayTime(time);
        level.updateSkyBrightness();
    }

    static BlockPos placeBed(GameTestHelper helper) {
        var level = helper.getLevel();
        var head = helper.absolutePos(HEAD);
        var foot = head.west();
        for (var pos : new BlockPos[] {head, foot}) {
            level.setBlock(pos.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
            level.setBlock(pos.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            level.setBlock(pos.above(2), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
        var state = Blocks.RED_BED.defaultBlockState().setValue(BedBlock.FACING, Direction.EAST);
        level.setBlock(foot, state.setValue(BedBlock.PART, BedPart.FOOT), Block.UPDATE_ALL);
        level.setBlock(head, state.setValue(BedBlock.PART, BedPart.HEAD), Block.UPDATE_ALL);
        return head;
    }

    static ServerPlayer player(GameTestHelper helper) {
        return player(helper, packet -> { });
    }

    private static ServerPlayer player(GameTestHelper helper, Consumer<Packet<?>> packets) {
        var level = helper.getLevel();
        var player = new ServerPlayer(level.getServer(), level,
                new GameProfile(UUID.randomUUID(), "bed-rest-test"), ClientInformation.createDefault());
        new ServerGamePacketListenerImpl(level.getServer(), new Connection(PacketFlow.SERVERBOUND), player,
                CommonListenerCookie.createInitial(player.getGameProfile(), false)) {
            @Override public void send(Packet<?> packet) { packets.accept(packet); }
        };
        player.gameMode.changeGameModeForPlayer(GameType.SURVIVAL);
        player.setPos(helper.absolutePos(HEAD).getCenter().add(0, 1, 0));
        return player;
    }

    static void tick(ServerPlayer player, int ticks) {
        for (int i = 0; i < ticks; i++) player.doTick();
    }

    private static void useBed(ServerPlayer player, BlockPos pos) {
        player.gameMode.useItemOn(player, player.level(), ItemStack.EMPTY, InteractionHand.MAIN_HAND,
                new BlockHitResult(pos.getCenter(), Direction.UP, pos, false));
    }

    private BedRestGameTests() { }
}
