package dimblend.experience.gametest;

import com.hlysine.create_connected.content.linkedtransmitter.LinkedTransmitterFrequencySlot;
import com.hlysine.create_connected.registries.CCBlocks;
import com.mojang.authlib.GameProfile;
import com.simibubi.create.content.redstone.link.LinkBehaviour;
import com.simibubi.create.content.redstone.link.LinkHandler;
import com.simibubi.create.foundation.block.ItemUseOverrides;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FaceAttachedHorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@PrefixGameTestTemplate(false)
public final class LinkedTransmitterGameTests {
    private static final String NS = "dimblend_compat_batch";
    private static final BlockPos MID = new BlockPos(1, 1, 1);

    @GameTest(template = "empty", templateNamespace = NS, timeoutTicks = 40)
    public static void frequencyUsesPacketHitOnNoCollisionLever(GameTestHelper helper) {
        placeLever(helper);
        BlockState state = helper.getBlockState(MID);
        ServerPlayer player = player(helper, new ItemStack(Items.PAPER), false);

        click(helper, player, MID, slot(helper.getLevel(), MID, state, true));
        assertFrequency(helper, "FrequencyFirst", "minecraft:paper");
        click(helper, player, MID, slot(helper.getLevel(), MID, state, false));
        assertFrequency(helper, "FrequencyLast", "minecraft:paper");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = NS, timeoutTicks = 40)
    public static void baseInteractionKeepsEarlyResult(GameTestHelper helper) {
        placeLever(helper);
        ServerPlayer player = player(helper, new ItemStack(Items.PAPER), false);
        PlayerInteractEvent.RightClickBlock event = click(helper, player, MID, new Vec3(0.5, 0.25, 0.5));
        helper.assertTrue(event.isCanceled(), "linked base use must keep the early consumed result");
        helper.assertTrue(helper.getBlockState(MID).getValue(BlockStateProperties.POWERED),
                "linked lever base must still toggle");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = NS, timeoutTicks = 40)
    public static void waxAndAxeKeepTheirPriority(GameTestHelper helper) {
        placeLever(helper);
        BlockState state = helper.getBlockState(MID);
        Vec3 slot = slot(helper.getLevel(), MID, state, true);
        click(helper, player(helper, new ItemStack(Items.HONEYCOMB), false), MID, slot);
        helper.assertTrue(helper.getBlockState(MID).getValue(BlockStateProperties.LOCKED),
                "honeycomb must wax the transmitter");
        assertFrequency(helper, "FrequencyFirst", "");
        assertFrequency(helper, "FrequencyLast", "");

        click(helper, player(helper, new ItemStack(Items.IRON_AXE), false), MID, slot);
        helper.assertTrue(!helper.getBlockState(MID).getValue(BlockStateProperties.LOCKED),
                "axe must remove the wax lock");

        click(helper, player(helper, new ItemStack(Items.HONEYCOMB), false), MID, slot);
        ServerPlayer paper = player(helper, new ItemStack(Items.PAPER), false);
        PlayerInteractEvent.RightClickBlock lockedClick = click(helper, paper, MID, slot);
        helper.assertTrue(lockedClick.isCanceled(), "a waxed transmitter must consume attachment clicks");
        helper.assertTrue(helper.getBlockState(MID).getValue(BlockStateProperties.LOCKED),
                "wax lock must remain in place");
        assertFrequency(helper, "FrequencyFirst", "");
        assertFrequency(helper, "FrequencyLast", "");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = NS, timeoutTicks = 40)
    public static void sneakingBaseStillConsumesWithoutToggling(GameTestHelper helper) {
        placeLever(helper);
        ServerPlayer player = player(helper, new ItemStack(Blocks.END_STONE), true);
        PlayerInteractEvent.RightClickBlock event = click(helper, player, MID, new Vec3(0.5, 0.25, 0.5));
        helper.assertTrue(event.isCanceled(), "sneak base click must retain CONSUME");
        helper.assertTrue(!helper.getBlockState(MID).getValue(BlockStateProperties.POWERED),
                "sneak base click must not toggle the lever");
        helper.assertTrue(player.getMainHandItem().getCount() == 1,
                "early CONSUME must not consume the held block");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = NS, timeoutTicks = 40)
    public static void fakePlayerKeepsNativePath(GameTestHelper helper) {
        placeLever(helper);
        FakePlayer player = new FakePlayer(helper.getLevel(), new GameProfile(UUID.randomUUID(), "linked-fake"));
        BlockPos absolute = helper.absolutePos(MID);
        player.setPos(absolute.getX() + 0.5, absolute.getY() + 2, absolute.getZ() + 0.5);
        player.setYRot(0.0F);
        player.setXRot(0.0F);
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.PAPER));
        helper.assertTrue(!dimblend.experience.compat.connected.LinkedTransmitterInteraction.supports(
                helper.getBlockState(MID), player), "FakePlayer must stay on the original interaction path");
        click(helper, player, MID, slot(helper.getLevel(), MID, helper.getBlockState(MID), true));
        assertFrequency(helper, "FrequencyFirst", "");
        helper.succeed();
    }

    private static void placeLever(GameTestHelper helper) {
        helper.setBlock(MID.below(), Blocks.END_STONE.defaultBlockState());
        helper.setBlock(MID, CCBlocks.LINKED_LEVER.getDefaultState()
                .setValue(FaceAttachedHorizontalDirectionalBlock.FACING, Direction.NORTH)
                .setValue(FaceAttachedHorizontalDirectionalBlock.FACE, AttachFace.FLOOR));
    }

    private static Vec3 slot(ServerLevel level, BlockPos pos, BlockState state, boolean first) {
        Vec3 offset = new LinkedTransmitterFrequencySlot(first).getLocalOffset(level, pos, state);
        return new Vec3(offset.x, 0.0625D, offset.z);
    }

    private static ServerPlayer player(GameTestHelper helper, ItemStack held, boolean sneaking) {
        ServerLevel level = helper.getLevel();
        ServerPlayer player = new ServerPlayer(level.getServer(), level,
                new GameProfile(UUID.randomUUID(), "linked-test"),
                net.minecraft.server.level.ClientInformation.createDefault());
        BlockPos absolute = helper.absolutePos(MID);
        player.setPos(absolute.getX() + 0.5, absolute.getY() + 2, absolute.getZ() + 0.5);
        player.setItemInHand(InteractionHand.MAIN_HAND, held);
        player.setShiftKeyDown(sneaking);
        return player;
    }

    private static PlayerInteractEvent.RightClickBlock click(GameTestHelper helper, ServerPlayer player,
            BlockPos pos, Vec3 localHit) {
        BlockPos absolute = helper.absolutePos(pos);
        BlockHitResult hit = new BlockHitResult(Vec3.atLowerCornerOf(absolute).add(localHit), Direction.UP, absolute, false);
        PlayerInteractEvent.RightClickBlock event = new PlayerInteractEvent.RightClickBlock(
                player, InteractionHand.MAIN_HAND, absolute, hit);
        NeoForge.EVENT_BUS.post(event);
        return event;
    }

    private static void assertFrequency(GameTestHelper helper, String key, String expectedId) {
        String id = helper.getBlockEntity(MID).saveWithFullMetadata(helper.getLevel().registryAccess())
                .getCompound(key).getString("id");
        helper.assertTrue(expectedId.equals(id), key + " must be " + expectedId + ", got " + id);
        LinkBehaviour behaviour = BlockEntityBehaviour.get(helper.getLevel(), helper.absolutePos(MID), LinkBehaviour.TYPE);
        helper.assertTrue(behaviour != null, "linked transmitter must retain LinkBehaviour");
    }
}
