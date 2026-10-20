package dimblend.experience.gametest;

import com.crystaelix.simurail.content.SimurailBlocks;
import com.crystaelix.simurail.content.bogey.PhysicsBogeyBlock;
import com.crystaelix.simurail.content.bogey.PhysicsBogeyMenu;
import com.simibubi.create.AllItems;
import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dimblend.experience.Config;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Native server interaction tests; run with -PwithChassis and its physics dependencies. */
@PrefixGameTestTemplate(false)
public final class SimurailBogeyInteractionGameTests {
    private static final BlockPos MID = new BlockPos(1, 1, 1);

    @GameTest(template = "empty", templateNamespace = "dimblend_chassis", timeoutTicks = 100)
    public static void survivalOpensNativeSettingsForBothBogeyOrientations(GameTestHelper helper) {
        boolean protect = Config.SIMURAIL_PROTECT.get();
        try {
            Config.SIMURAIL_PROTECT.set(true);
            ServerPlayer player = player(helper, GameType.SURVIVAL);
            BlockPos pos = helper.absolutePos(MID);
            for (boolean inverted : new boolean[] {false, true}) {
                helper.setBlock(MID, bogey(inverted));
                for (InteractionHand hand : InteractionHand.values()) {
                    for (boolean secondary : new boolean[] {false, true}) {
                        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
                        player.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);
                        player.setShiftKeyDown(secondary);
                        use(player, pos, hand);
                        helper.assertTrue(player.containerMenu instanceof PhysicsBogeyMenu,
                                "survival must open native settings: inverted=" + inverted + ", hand=" + hand
                                        + ", secondary=" + secondary);
                        player.closeContainer();
                    }
                }
                player.setShiftKeyDown(false);
                player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.STICK));
                use(player, pos, InteractionHand.MAIN_HAND);
                helper.assertTrue(player.containerMenu instanceof PhysicsBogeyMenu,
                        "ordinary held items must still open native settings");
                player.closeContainer();
            }
            helper.succeed();
        } finally {
            Config.SIMURAIL_PROTECT.set(protect);
        }
    }

    @GameTest(template = "empty", templateNamespace = "dimblend_chassis", timeoutTicks = 100)
    public static void survivalSettingsAlsoOpenOnAnAssembledVehicle(GameTestHelper helper) {
        boolean protect = Config.SIMURAIL_PROTECT.get();
        var level = helper.getLevel();
        var world = helper.absolutePos(MID);
        helper.setBlock(MID, bogey(false));
        var vehicle = SubLevelAssemblyHelper.assembleBlocks(level, world, List.of(world), new BoundingBox3i(world, world));
        helper.assertTrue(vehicle != null, "bogey fixture must assemble into a Sable vehicle");
        try {
            Config.SIMURAIL_PROTECT.set(true);
            BlockPos plot = BlockPos.containing(vehicle.logicalPose().transformPositionInverse(Vec3.atCenterOf(world)));
            helper.assertTrue(level.getBlockState(plot).is(SimurailBlocks.PHYSICS_BOGEY.get()),
                    "vehicle bogey must be loaded in its plot");
            ServerPlayer player = player(helper, GameType.SURVIVAL);
            use(player, plot, InteractionHand.MAIN_HAND);
            helper.assertTrue(player.containerMenu instanceof PhysicsBogeyMenu,
                    "survival must open settings for the vehicle's plot-space bogey");
            player.closeContainer();
            helper.succeed();
        } finally {
            Config.SIMURAIL_PROTECT.set(protect);
            vehicle.markRemoved();
        }
    }

    @GameTest(template = "empty", templateNamespace = "dimblend_chassis", timeoutTicks = 100)
    public static void openingSettingsDoesNotAllowWrenchPickupOrSurvivalBreaking(GameTestHelper helper) {
        boolean protect = Config.SIMURAIL_PROTECT.get();
        try {
            Config.SIMURAIL_PROTECT.set(true);
            helper.setBlock(MID, bogey(false));
            BlockPos pos = helper.absolutePos(MID);
            ServerPlayer player = player(helper, GameType.SURVIVAL);
            for (InteractionHand hand : InteractionHand.values()) {
                player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
                player.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);
                player.setItemInHand(hand, AllItems.WRENCH.asStack());
                for (boolean secondary : new boolean[] {false, true}) {
                    player.setShiftKeyDown(secondary);
                    use(player, pos, hand);
                    helper.assertTrue(levelState(player, pos).equals(bogey(false)),
                            "ordinary and sneak wrenching must leave the bogey in place and unchanged");
                    helper.assertTrue(!(player.containerMenu instanceof PhysicsBogeyMenu),
                            "wrench must keep its native non-menu behavior");
                }
            }
            helper.assertTrue(!player.gameMode.destroyBlock(pos), "survival breaking must still be rejected");
            helper.assertTrue(levelState(player, pos).is(SimurailBlocks.PHYSICS_BOGEY.get()),
                    "protected bogey must survive the break attempt");
            helper.succeed();
        } finally {
            Config.SIMURAIL_PROTECT.set(protect);
        }
    }

    @GameTest(template = "empty", templateNamespace = "dimblend_chassis", timeoutTicks = 100)
    public static void adventureSpectatorAndCouplerRestrictionsRemainInForce(GameTestHelper helper) {
        boolean protect = Config.SIMURAIL_PROTECT.get();
        boolean coupler = Config.ALLOW_SURVIVAL_COUPLER_INTERACTION.get();
        try {
            Config.SIMURAIL_PROTECT.set(true);
            helper.setBlock(MID, bogey(false));
            BlockPos pos = helper.absolutePos(MID);
            for (GameType mode : new GameType[] {GameType.ADVENTURE, GameType.SPECTATOR, GameType.CREATIVE}) {
                var player = player(helper, mode);
                var click = new PlayerInteractEvent.RightClickBlock(player, InteractionHand.MAIN_HAND, pos, hit(pos));
                NeoForge.EVENT_BUS.post(click);
                helper.assertTrue(click.isCanceled() == (mode != GameType.CREATIVE),
                        "only creative and survival may configure bogeys, mode=" + mode);
            }
            helper.setBlock(MID, SimurailBlocks.AUTOMATIC_COUPLER.getDefaultState());
            var player = player(helper, GameType.SURVIVAL);
            for (boolean allowed : new boolean[] {false, true}) {
                Config.ALLOW_SURVIVAL_COUPLER_INTERACTION.set(allowed);
                var click = new PlayerInteractEvent.RightClickBlock(player, InteractionHand.MAIN_HAND, pos, hit(pos));
                NeoForge.EVENT_BUS.post(click);
                helper.assertTrue(click.isCanceled() != allowed, "couplers must retain their independent setting");
            }
            helper.succeed();
        } finally {
            Config.SIMURAIL_PROTECT.set(protect);
            Config.ALLOW_SURVIVAL_COUPLER_INTERACTION.set(coupler);
        }
    }

    private static BlockState bogey(boolean inverted) {
        return SimurailBlocks.PHYSICS_BOGEY.getDefaultState().setValue(PhysicsBogeyBlock.INVERTED, inverted);
    }

    private static BlockState levelState(ServerPlayer player, BlockPos pos) {
        return player.level().getBlockState(pos);
    }

    private static void use(ServerPlayer player, BlockPos pos, InteractionHand hand) {
        player.gameMode.useItemOn(player, player.level(), player.getItemInHand(hand), hand, hit(pos));
    }

    private static BlockHitResult hit(BlockPos pos) {
        return new BlockHitResult(pos.getCenter(), Direction.UP, pos, false);
    }

    private static ServerPlayer player(GameTestHelper helper, GameType mode) {
        var level = helper.getLevel();
        var player = new ServerPlayer(level.getServer(), level,
                new com.mojang.authlib.GameProfile(UUID.randomUUID(), "bogey-test"), ClientInformation.createDefault());
        new ServerGamePacketListenerImpl(level.getServer(), new Connection(PacketFlow.SERVERBOUND), player,
                CommonListenerCookie.createInitial(player.getGameProfile(), false)) {
            @Override public void send(Packet<?> packet) { }
        };
        player.gameMode.changeGameModeForPlayer(mode);
        player.setPos(Vec3.atCenterOf(helper.absolutePos(MID.above(2))));
        return player;
    }

    private SimurailBogeyInteractionGameTests() { }
}
