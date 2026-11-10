package dimblend.experience.gametest;

import dimblend.experience.Config;
import dimblend.experience.exploration.BackpackTotemProtection;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.EventHooks;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Register only with solcarrot and the real kaleidoscope_grilling present; run with
 * -PwithSupplementDeath. No test registers a replacement powder or calls begin/restore.
 * All tests execute the woven hurt -> die -> restoreFrom -> Clone/respawn event paths.
 */
@PrefixGameTestTemplate(false)
public final class BackpackTotemGameTests {
    private static final ResourceLocation POWDER = ResourceLocation.parse("kaleidoscope_grilling:totem_powder");
    private static final int XP_LEVEL = 23;
    private static final float XP_PROGRESS = 0.375F;
    private static final int XP_TOTAL = 1234;

    @GameTest(template = "empty", templateNamespace = "dimblend_supplement_death", timeoutTicks = 100)
    public static void everyMainInventorySlotProtectsOneActualDeath(GameTestHelper helper) {
        try (var rules = new Rules(helper, false, true)) {
            for (int slot = 0; slot < 36; slot++) {
                var player = player(helper, rules);
                // The selected hand must be empty, including when testing hotbar slots.
                player.getInventory().selected = slot == 0 ? 1 : 0;
                player.getInventory().items.set(slot, new ItemStack(Items.TOTEM_OF_UNDYING));
                try (var drops = new Drops(player)) {
                    die(helper, player, false);
                    helper.assertTrue(BackpackTotemProtection.active(player), "slot " + slot + " must protect death");
                    helper.assertTrue(drops.count(powder(helper)) == 1 && drops.count(Items.TOTEM_OF_UNDYING) == 0,
                            "the one consumed totem must enter normal death drops as real powder, slot " + slot);
                    assertNoOrbs(helper, player);
                    var replacement = clonePlayer(rules, player, false);
                    assertExperience(helper, replacement);
                    assertCleared(helper, player, replacement);
                }
            }
            helper.succeed();
        }
    }

    @GameTest(template = "empty", templateNamespace = "dimblend_supplement_death", timeoutTicks = 100)
    public static void keepInventoryConsumesOnceEvenIfDeathIsReentered(GameTestHelper helper) {
        try (var rules = new Rules(helper, true, true)) {
            var player = player(helper, rules);
            player.getInventory().items.set(9, new ItemStack(Items.TOTEM_OF_UNDYING));
            player.getInventory().items.set(35, new ItemStack(Items.TOTEM_OF_UNDYING));
            try (var drops = new Drops(player)) {
                die(helper, player, false);
                player.die(player.damageSources().genericKill());
                helper.assertTrue(player.getInventory().countItem(Items.TOTEM_OF_UNDYING) == 1
                                && player.getInventory().countItem(powder(helper)) == 1,
                        "a repeated die call must not consume the second totem or create another powder");
                helper.assertTrue(drops.items.isEmpty(), "keepInventory keeps powder with the other inventory items");
                assertNoOrbs(helper, player);
                var replacement = clonePlayer(rules, player, false);
                assertExperience(helper, replacement);
                helper.assertTrue(replacement.getInventory().countItem(Items.TOTEM_OF_UNDYING) == 1
                                && replacement.getInventory().countItem(powder(helper)) == 1,
                        "restoreFrom must copy the already exchanged inventory exactly once");
                assertCleared(helper, player, replacement);
            }
            helper.succeed();
        }
    }

    @GameTest(template = "empty", templateNamespace = "dimblend_supplement_death", timeoutTicks = 100)
    public static void fullInventoryStackedTotemPowderObeysCancelledDeathDrops(GameTestHelper helper) {
        try (var rules = new Rules(helper, false, true)) {
            var player = player(helper, rules);
            for (int slot = 0; slot < 36; slot++) {
                player.getInventory().items.set(slot, new ItemStack(Items.STONE, 64));
            }
            player.getInventory().offhand.set(0, new ItemStack(Items.STONE, 64));
            var stacked = new ItemStack(Items.TOTEM_OF_UNDYING, 2);
            stacked.set(DataComponents.MAX_STACK_SIZE, 64); // Legal modded stack; vanilla totems normally stack to one.
            player.getInventory().items.set(9, stacked);
            try (var drops = new Drops(player)) {
                die(helper, player, false);
                helper.assertTrue(drops.count(powder(helper)) == 1 && drops.count(Items.TOTEM_OF_UNDYING) == 1,
                        "a full inventory must consume only one stacked totem and capture its powder as a death drop");
                helper.assertTrue(player.serverLevel().getEntitiesOfClass(ItemEntity.class,
                        player.getBoundingBox().inflate(3)).isEmpty(),
                        "cancelled death drops must not leak a powder through the separate ItemToss path");
                var replacement = clonePlayer(rules, player, false);
                assertExperience(helper, replacement);
                assertCleared(helper, player, replacement);
            }
            helper.succeed();
        }
    }

    @GameTest(template = "empty", templateNamespace = "dimblend_supplement_death", timeoutTicks = 100)
    public static void creativeFullInventoryStackedTotemPowderObeysDeathDropRules(GameTestHelper helper) {
        for (boolean keepInventory : new boolean[] {false, true}) {
            try (var rules = new Rules(helper, keepInventory, true)) {
                var player = player(helper, rules);
                player.gameMode.changeGameModeForPlayer(GameType.CREATIVE);
                for (int slot = 0; slot < 36; slot++) {
                    player.getInventory().items.set(slot, new ItemStack(Items.STONE, 64));
                }
                player.getInventory().offhand.set(0, new ItemStack(Items.STONE, 64));
                var stacked = new ItemStack(Items.TOTEM_OF_UNDYING, 2);
                stacked.set(DataComponents.MAX_STACK_SIZE, 64);
                player.getInventory().items.set(9, stacked);
                try (var drops = new Drops(player)) {
                    die(helper, player, true); // /kill bypasses creative invulnerability and held revival.
                    player.die(player.damageSources().genericKill());
                    helper.assertTrue(player.isCreative() && player.getAbilities().instabuild,
                            "death protection must never toggle creative abilities to insert powder");
                    helper.assertTrue(drops.count(powder(helper)) == 1,
                            "creative full inventory must capture exactly one overflow powder, keep=" + keepInventory);
                    helper.assertTrue(player.getInventory().countItem(Items.TOTEM_OF_UNDYING)
                                    + drops.count(Items.TOTEM_OF_UNDYING) == 1,
                            "creative /kill and reentry must consume exactly one stacked totem");
                    helper.assertTrue(player.getInventory().countItem(powder(helper)) == 0,
                            "a full retained inventory has no capacity for the overflow powder");
                    helper.assertTrue(player.serverLevel().getEntitiesOfClass(ItemEntity.class,
                                    player.getBoundingBox().inflate(3)).isEmpty(),
                            "cancelled creative death drops must not leak powder through an early toss");
                    assertNoOrbs(helper, player);
                    var replacement = clonePlayer(rules, player, false);
                    assertExperience(helper, replacement);
                    helper.assertTrue(replacement.isCreative()
                                    && replacement.getInventory().countItem(Items.TOTEM_OF_UNDYING) == (keepInventory ? 1 : 0)
                                    && replacement.getInventory().countItem(powder(helper)) == 0,
                            "clone must retain creative mode and only the inventory permitted by keepInventory");
                    assertCleared(helper, player, replacement);
                }
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "dimblend_supplement_death", timeoutTicks = 100)
    public static void creativeStackedTotemPowderUsesAvailableInventorySpace(GameTestHelper helper) {
        try (var rules = new Rules(helper, true, true)) {
            // An empty main slot, a matching main stack, and a matching offhand stack.
            for (int destination : new int[] {10, 11, 40}) {
                var player = player(helper, rules);
                player.gameMode.changeGameModeForPlayer(GameType.CREATIVE);
                for (int slot = 0; slot < 36; slot++) {
                    player.getInventory().items.set(slot, new ItemStack(Items.STONE, 64));
                }
                player.getInventory().offhand.set(0, new ItemStack(Items.STONE, 64));
                var stacked = new ItemStack(Items.TOTEM_OF_UNDYING, 2);
                stacked.set(DataComponents.MAX_STACK_SIZE, 64);
                player.getInventory().items.set(9, stacked);
                player.getInventory().setItem(destination,
                        destination == 10 ? ItemStack.EMPTY : new ItemStack(powder(helper)));
                int expectedPowder = destination == 10 ? 1 : 2;
                try (var drops = new Drops(player)) {
                    die(helper, player, true);
                    helper.assertTrue(player.getInventory().getItem(destination).is(powder(helper))
                                    && player.getInventory().getItem(destination).getCount() == expectedPowder
                                    && player.getInventory().countItem(Items.TOTEM_OF_UNDYING) == 1,
                            "creative death must store one powder in the available destination " + destination);
                    helper.assertTrue(drops.items.isEmpty(),
                            "keepInventory must not drop powder when it fits in the inventory");
                    var replacement = clonePlayer(rules, player, false);
                    helper.assertTrue(replacement.isCreative()
                                    && replacement.getInventory().getItem(destination).getCount() == expectedPowder,
                            "the inserted powder and creative mode must survive the real clone path");
                    assertExperience(helper, replacement);
                    assertCleared(helper, player, replacement);
                }
            }
            helper.succeed();
        }
    }

    @GameTest(template = "empty", templateNamespace = "dimblend_supplement_death", timeoutTicks = 100)
    public static void cancelledDeathDoesNotSpendATotem(GameTestHelper helper) {
        try (var rules = new Rules(helper, false, true)) {
            var player = player(helper, rules);
            player.getInventory().items.set(9, new ItemStack(Items.TOTEM_OF_UNDYING));
            boolean[] deathObserved = {false};
            Consumer<LivingDeathEvent> cancel = event -> {
                if (event.getEntity() == player) {
                    deathObserved[0] = true;
                    player.setHealth(player.getMaxHealth());
                    event.setCanceled(true);
                }
            };
            NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, false, LivingDeathEvent.class, cancel);
            try (var drops = new Drops(player)) {
                player.hurt(player.damageSources().generic(), 1000);
                helper.assertTrue(deathObserved[0], "ordinary lethal damage must reach the cancellable death event");
                helper.assertTrue(player.isAlive() && !BackpackTotemProtection.active(player),
                        "a late death cancellation must leave no protection marker");
                helper.assertTrue(player.getInventory().countItem(Items.TOTEM_OF_UNDYING) == 1
                                && player.getInventory().countItem(powder(helper)) == 0 && drops.items.isEmpty(),
                        "cancelled death must not exchange or drop anything");
                assertExperience(helper, player);
            } finally {
                NeoForge.EVENT_BUS.unregister(cancel);
            }
            helper.succeed();
        }
    }

    @GameTest(template = "empty", templateNamespace = "dimblend_supplement_death", timeoutTicks = 100)
    public static void heldVanillaTotemRevivesWithoutBackpackExchange(GameTestHelper helper) {
        try (var rules = new Rules(helper, false, true)) {
            for (boolean offhand : new boolean[] {false, true}) {
                var player = player(helper, rules);
                player.getInventory().items.set(9, new ItemStack(Items.TOTEM_OF_UNDYING));
                if (offhand) player.getInventory().offhand.set(0, new ItemStack(Items.TOTEM_OF_UNDYING));
                else player.getInventory().items.set(0, new ItemStack(Items.TOTEM_OF_UNDYING));
                try (var drops = new Drops(player)) {
                    player.hurt(player.damageSources().generic(), 1000);
                    helper.assertTrue(player.isAlive() && !BackpackTotemProtection.active(player),
                            "the vanilla hand totem revives before die and must not activate backpack protection");
                    helper.assertTrue((offhand ? player.getOffhandItem() : player.getMainHandItem()).isEmpty()
                                    && player.getHealth() == 1.0F,
                            "lethal damage must really consume the held totem and revive at one HP");
                    helper.assertTrue(player.getInventory().items.get(9).is(Items.TOTEM_OF_UNDYING)
                                    && player.getInventory().countItem(powder(helper)) == 0 && drops.items.isEmpty(),
                            "the spare inventory totem must remain untouched after vanilla revival");
                    assertExperience(helper, player);
                }
            }
            helper.succeed();
        }
    }

    @GameTest(template = "empty", templateNamespace = "dimblend_supplement_death", timeoutTicks = 100)
    public static void armorOffhandAndNestedTotemsDoNotProtect(GameTestHelper helper) {
        try (var rules = new Rules(helper, false, true)) {
            var player = player(helper, rules);
            var box = new ItemStack(Items.SHULKER_BOX);
            box.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(new ItemStack(Items.TOTEM_OF_UNDYING))));
            player.getInventory().items.set(9, box);
            player.getInventory().armor.set(0, new ItemStack(Items.TOTEM_OF_UNDYING));
            player.getInventory().offhand.set(0, new ItemStack(Items.TOTEM_OF_UNDYING));
            try (var drops = new Drops(player)) {
                // genericKill bypasses vanilla held resurrection; only the inventory scan is under test.
                die(helper, player, true);
                helper.assertTrue(!BackpackTotemProtection.active(player) && drops.count(powder(helper)) == 0,
                        "armor, offhand and container contents must not be scanned");
                helper.assertTrue(drops.count(Items.TOTEM_OF_UNDYING) == 2 && drops.count(Items.SHULKER_BOX) == 1,
                        "excluded totems must follow normal item drops without being exchanged");
                var replacement = clonePlayer(rules, player, false);
                helper.assertTrue(replacement.experienceLevel == 0 && replacement.totalExperience == 0
                                && replacement.experienceProgress == 0,
                        "the ordinary rotating death rule must still clear unprotected XP");
            }
            helper.succeed();
        }
    }

    @GameTest(template = "empty", templateNamespace = "dimblend_supplement_death", timeoutTicks = 100)
    public static void snapshotWinsAfterMovementCorpseChangesAndLateCloneListeners(GameTestHelper helper) {
        try (var rules = new Rules(helper, false, false)) {
            var player = player(helper, rules);
            player.getInventory().items.set(35, new ItemStack(Items.TOTEM_OF_UNDYING));
            boolean[] observedMarker = {false};
            Consumer<PlayerEvent.Clone> late = event -> {
                if (event.getOriginal() != player) return;
                observedMarker[0] = BackpackTotemProtection.active(player);
                event.getEntity().experienceLevel = 1;
                event.getEntity().experienceProgress = 0;
                event.getEntity().totalExperience = 1;
                // Simulate a compat listener copying every persistent-data key.
                event.getEntity().getPersistentData().merge(player.getPersistentData().copy());
            };
            NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, false, PlayerEvent.Clone.class, late);
            try (var drops = new Drops(player)) {
                die(helper, player, false);
                assertNoOrbs(helper, player); // Outside rotating: protection itself must cancel XP orbs.
                player.experienceLevel = 99;
                player.experienceProgress = 0.9F;
                player.totalExperience = 9999;
                player.setPos(player.position().add(4096, 0, 4096));
                var replacement = clonePlayer(rules, player, false);
                helper.assertTrue(observedMarker[0], "the old marker must survive through the last Clone listeners");
                assertExperience(helper, replacement);
                assertCleared(helper, player, replacement);
            } finally {
                NeoForge.EVENT_BUS.unregister(late);
            }
            helper.succeed();
        }
    }

    @GameTest(template = "empty", templateNamespace = "dimblend_supplement_death", timeoutTicks = 100)
    public static void nonDeathCloneDoesNotRestoreADeathSnapshot(GameTestHelper helper) {
        try (var rules = new Rules(helper, true, false)) {
            var player = player(helper, rules);
            player.getInventory().items.set(9, new ItemStack(Items.TOTEM_OF_UNDYING));
            try (var drops = new Drops(player)) {
                die(helper, player, false);
                player.experienceLevel = 7;
                player.experienceProgress = 0.5F;
                player.totalExperience = 70;
                var replacement = clonePlayer(rules, player, true);
                helper.assertTrue(replacement.experienceLevel == 7 && replacement.experienceProgress == 0.5F
                                && replacement.totalExperience == 70,
                        "keepEverything/non-death cloning must keep its own vanilla XP semantics");
                assertCleared(helper, player, replacement);
            }
            helper.succeed();
        }
    }

    @GameTest(template = "empty", templateNamespace = "dimblend_supplement_death", timeoutTicks = 100)
    public static void solRecordAndExtraHealthSurviveBothResetSettings(GameTestHelper helper) {
        try (var rules = new Rules(helper, false, true); var sol = new SolSettings()) {
            for (boolean reset : new boolean[] {false, true}) {
                sol.set("shouldResetOnDeath", reset);
                var player = player(helper, rules);
                seedFood(player);
                float health = player.getMaxHealth();
                helper.assertTrue(health > 20, "fixture must really earn S.O.L extra HP");
                player.getInventory().items.set(9, new ItemStack(Items.TOTEM_OF_UNDYING));
                try (var drops = new Drops(player)) {
                    die(helper, player, false);
                    var replacement = clonePlayer(rules, player, false);
                    helper.assertTrue(hasFood(replacement, Items.APPLE) && hasFood(replacement, Items.BREAD)
                                    && replacement.getMaxHealth() == health,
                            "S.O.L food and extra HP must survive protected death with reset=" + reset);
                    assertExperience(helper, replacement);
                    assertCleared(helper, player, replacement);
                }
                helper.assertTrue(SolSettings.value("shouldResetOnDeath").get().equals(reset),
                        "the production hook must not change the global S.O.L reset config");
            }
            helper.succeed();
        }
    }

    @GameTest(template = "empty", templateNamespace = "dimblend_supplement_death", timeoutTicks = 100)
    public static void nextUnprotectedDeathResetsSolAndXpIncludingKeepInventory(GameTestHelper helper) {
        try (var sol = new SolSettings()) {
            sol.set("shouldResetOnDeath", true);
            for (boolean keep : new boolean[] {false, true}) {
                try (var rules = new Rules(helper, keep, true)) {
                    var player = player(helper, rules);
                    seedFood(player);
                    player.getInventory().items.set(9, new ItemStack(Items.TOTEM_OF_UNDYING));
                    ServerPlayer replacement;
                    try (var drops = new Drops(player)) {
                        die(helper, player, false);
                        replacement = clonePlayer(rules, player, false);
                    }
                    helper.assertTrue(hasFood(replacement, Items.APPLE) && replacement.getMaxHealth() > 20,
                            "first death must be protected before exercising the second");
                    try (var drops = new Drops(replacement)) {
                        die(helper, replacement, false);
                        var next = clonePlayer(rules, replacement, false);
                        helper.assertTrue(!hasFood(next, Items.APPLE) && !hasFood(next, Items.BREAD)
                                        && next.getMaxHealth() == 20,
                                "the next death without a totem must restore normal S.O.L reset, keep=" + keep);
                        helper.assertTrue(next.experienceLevel == 0 && next.experienceProgress == 0 && next.totalExperience == 0,
                                "the normal rotating XP rule must resume, even with keepInventory");
                        assertCleared(helper, replacement, next);
                    }
                }
            }
            helper.succeed();
        }
    }

    /** Register this alternative class only for the S.O.L-only run, without Grilling. */
    @PrefixGameTestTemplate(false)
    public static final class WithoutPowder {
        @GameTest(template = "empty", templateNamespace = "dimblend_supplement_death", timeoutTicks = 100)
        public static void missingOptionalPowderLeavesTotemAndOrdinaryDeathRulesAlone(GameTestHelper helper) {
            helper.assertTrue(!BuiltInRegistries.ITEM.containsKey(POWDER),
                    "negative fixture must really omit Grilling, never remove or replace its registry entry");
            try (var rules = new Rules(helper, true, true); var sol = new SolSettings()) {
                sol.set("shouldResetOnDeath", true);
                var player = rules.newPlayer();
                player.experienceLevel = XP_LEVEL;
                player.experienceProgress = XP_PROGRESS;
                player.totalExperience = XP_TOTAL;
                seedFood(player);
                player.getInventory().items.set(9, new ItemStack(Items.TOTEM_OF_UNDYING));
                try (var drops = new Drops(player)) {
                    die(helper, player, false);
                    helper.assertTrue(!BackpackTotemProtection.active(player)
                                    && player.getInventory().countItem(Items.TOTEM_OF_UNDYING) == 1,
                            "missing powder must not consume a totem or activate partial protection");
                    var replacement = clonePlayer(rules, player, false);
                    helper.assertTrue(replacement.experienceLevel == 0 && replacement.experienceProgress == 0
                                    && replacement.totalExperience == 0 && !hasFood(replacement, Items.APPLE)
                                    && replacement.getMaxHealth() == 20,
                            "the ordinary XP/S.O.L death rules must remain active when powder is absent");
                    helper.assertTrue(replacement.getInventory().countItem(Items.TOTEM_OF_UNDYING) == 1,
                            "keepInventory must keep the unchanged totem on the replacement");
                    assertCleared(helper, player, replacement);
                }
                helper.succeed();
            }
        }
    }

    private static ServerPlayer player(GameTestHelper helper, Rules rules) {
        powder(helper); // Fail if integration forgot the real optional mod; never register a stand-in.
        var player = rules.newPlayer();
        player.experienceLevel = XP_LEVEL;
        player.experienceProgress = XP_PROGRESS;
        player.totalExperience = XP_TOTAL;
        return player;
    }

    private static void die(GameTestHelper helper, ServerPlayer player, boolean bypassTotem) {
        boolean hurt = player.hurt(bypassTotem ? player.damageSources().genericKill() : player.damageSources().generic(), 1000);
        helper.assertTrue(hurt, "the lethal damage must be accepted rather than blocked by fixture invulnerability");
        helper.assertTrue(player.isDeadOrDying(), "backpack protection preserves progress, never revives the player");
    }

    private static ServerPlayer clonePlayer(Rules rules, ServerPlayer original, boolean keepEverything) {
        var replacement = rules.newPlayer();
        replacement.restoreFrom(original, keepEverything);
        EventHooks.firePlayerRespawnEvent(replacement, keepEverything);
        return replacement;
    }

    private static Item powder(GameTestHelper helper) {
        helper.assertTrue(BuiltInRegistries.ITEM.containsKey(POWDER), "real kaleidoscope_grilling:totem_powder is required");
        return BuiltInRegistries.ITEM.get(POWDER);
    }

    private static void assertExperience(GameTestHelper helper, ServerPlayer player) {
        helper.assertTrue(player.experienceLevel == XP_LEVEL && player.experienceProgress == XP_PROGRESS
                        && player.totalExperience == XP_TOTAL,
                "level, fractional progress and total XP must match the accepted-death snapshot exactly");
    }

    private static void assertCleared(GameTestHelper helper, ServerPlayer original, ServerPlayer replacement) {
        helper.assertTrue(!BackpackTotemProtection.active(original) && !BackpackTotemProtection.active(replacement),
                "a completed clone must not leave protection on either entity");
    }

    private static void assertNoOrbs(GameTestHelper helper, ServerPlayer player) {
        helper.assertTrue(player.serverLevel().getEntitiesOfClass(ExperienceOrb.class,
                player.getBoundingBox().inflate(2)).isEmpty(), "protected XP must not also spawn as collectible orbs");
    }

    /** Observe/cancel the real LivingDrops event, proving powder participates in ordinary drop rules. */
    private static final class Drops implements AutoCloseable {
        private final List<ItemStack> items = new ArrayList<>();
        private final Consumer<LivingDropsEvent> capture;

        private Drops(ServerPlayer player) {
            capture = event -> {
                if (event.getEntity() != player) return;
                for (ItemEntity item : event.getDrops()) items.add(item.getItem().copy());
                event.setCanceled(true);
            };
            NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, true, LivingDropsEvent.class, capture);
        }

        private int count(Item item) {
            return items.stream().filter(stack -> stack.is(item)).mapToInt(ItemStack::getCount).sum();
        }

        @Override public void close() { NeoForge.EVENT_BUS.unregister(capture); }
    }

    /** Every test restores shared world/config values, even on assertion failure. */
    private static final class Rules implements AutoCloseable {
        private final GameTestHelper helper;
        private final boolean keep;
        private final boolean messages;
        private final String dimension;
        private final double ratio;
        private final List<ServerPlayer> players = new ArrayList<>();

        private Rules(GameTestHelper helper, boolean keepInventory, boolean rotating) {
            this.helper = helper;
            var rules = helper.getLevel().getGameRules();
            keep = rules.getBoolean(GameRules.RULE_KEEPINVENTORY);
            messages = rules.getBoolean(GameRules.RULE_SHOWDEATHMESSAGES);
            dimension = Config.ROTATING_DIMENSION_ID.get();
            ratio = Config.DEATH_EXP_CLEAR_RATIO.get();
            rules.getRule(GameRules.RULE_KEEPINVENTORY).set(keepInventory, helper.getLevel().getServer());
            rules.getRule(GameRules.RULE_SHOWDEATHMESSAGES).set(false, helper.getLevel().getServer());
            Config.ROTATING_DIMENSION_ID.set(rotating ? "minecraft:overworld" : "dimblend:rotating");
            Config.DEATH_EXP_CLEAR_RATIO.set(1.0);
        }

        private ServerPlayer newPlayer() {
            var player = BedRestGameTests.player(helper);
            players.add(player);
            var position = player.position();
            float health = player.getHealth();
            int food = player.getFoodData().getFoodLevel();
            float saturation = player.getFoodData().getSaturationLevel();
            // NeoForge 21.1.249 ServerPlayer starts with 60 ticks of login immunity.
            // tick(), not doTick(), decrements that timer. Do not advance the world or
            // living-entity movement/hunger: these players are not added to the level.
            for (int tick = 0; tick < 60; tick++) player.tick();
            helper.assertTrue(player.isAlive() && player.getHealth() == health
                            && player.position().equals(position) && player.getFoodData().getFoodLevel() == food
                            && player.getFoodData().getSaturationLevel() == saturation
                            && player.getInventory().isEmpty(),
                    "expiring login immunity must not move, damage, feed or populate the test player");
            return player;
        }

        @Override public void close() {
            // Also dispose replacements and failed fixtures; no fake player survives its rule scope.
            for (var player : players) player.discard();
            players.clear();
            var rules = helper.getLevel().getGameRules();
            rules.getRule(GameRules.RULE_KEEPINVENTORY).set(keep, helper.getLevel().getServer());
            rules.getRule(GameRules.RULE_SHOWDEATHMESSAGES).set(messages, helper.getLevel().getServer());
            Config.ROTATING_DIMENSION_ID.set(dimension);
            Config.DEATH_EXP_CLEAR_RATIO.set(ratio);
        }
    }

    // Reflection is confined to test fixtures so compileJava does not require optional S.O.L.
    // Signatures were checked against solcarrot-1.21.1-1.16.6.jar; mismatches fail loudly.
    private static Object solCall(String owner, String method, Object target, Class<?>[] parameters, Object... args) {
        try {
            return Class.forName("com.cazsius.solcarrot." + owner).getMethod(method, parameters).invoke(target, args);
        } catch (InvocationTargetException failure) {
            throw new AssertionError("S.O.L fixture call failed: " + owner + "." + method, failure.getCause());
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("S.O.L 1.16.6 API mismatch: " + owner + "." + method, failure);
        }
    }

    private static Object foodList(Player player) {
        return solCall("tracking.FoodList", "get", null, new Class<?>[] {Player.class}, player);
    }

    private static void seedFood(Player player) {
        Object food = foodList(player);
        for (Item item : new Item[] {Items.APPLE, Items.BREAD}) {
            solCall("tracking.FoodList", "addFood", food, new Class<?>[] {ItemStack.class}, new ItemStack(item));
        }
        solCall("tracking.MaxHealthHandler", "updateFoodHPModifier", null, new Class<?>[] {Player.class}, player);
    }

    private static boolean hasFood(Player player, Item item) {
        return (boolean) solCall("tracking.FoodList", "hasEaten", foodList(player), new Class<?>[] {Item.class}, item);
    }

    private static final class SolSettings implements AutoCloseable {
        private final Map<String, Object> saved = new LinkedHashMap<>();

        private SolSettings() {
            try {
                set("baseHearts", 10);
                set("heartsPerMilestone", 2);
                set("milestones", List.of(1, 2));
                set("minimumFoodValue", 0);
                set("blacklist", List.of());
                set("whitelist", List.of());
            } catch (RuntimeException | Error failure) {
                close();
                throw failure;
            }
        }

        @SuppressWarnings("unchecked")
        private static ModConfigSpec.ConfigValue<Object> value(String name) {
            try {
                Object server = Class.forName("com.cazsius.solcarrot.SOLCarrotConfig").getField("SERVER").get(null);
                return (ModConfigSpec.ConfigValue<Object>) server.getClass().getField(name).get(server);
            } catch (ReflectiveOperationException failure) {
                throw new AssertionError("S.O.L 1.16.6 config API mismatch: " + name, failure);
            }
        }

        private void set(String name, Object setting) {
            saved.computeIfAbsent(name, key -> value(key).get());
            value(name).set(setting);
        }

        @Override public void close() { saved.forEach((name, setting) -> value(name).set(setting)); }
    }

    private BackpackTotemGameTests() { }
}
