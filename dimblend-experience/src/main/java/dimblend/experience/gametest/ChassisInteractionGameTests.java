package dimblend.experience.gametest;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.AllItems;
import dimblend.experience.DimBlend;
import dimblend.experience.chassis.ChassisEvents;
import dimblend.experience.chassis.ChassisScreenPayload;
import dimblend.experience.chassis.ChassisSelection;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * 驾驶台右键归属的 GameTest：主手空手/普通扳手开菜单，潜行、副手、其他物品、够不到、
 * 以及脚下不是天然主线时都必须放行，不能吞点击。
 */
@PrefixGameTestTemplate(false)
@EventBusSubscriber(modid = DimBlend.MODID, bus = EventBusSubscriber.Bus.MOD)
public final class ChassisInteractionGameTests {
    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        if (ChassisEvents.available()) event.register(ChassisInteractionGameTests.class);
    }

    @GameTest(template = "empty", templateNamespace = "dimblend_chassis", timeoutTicks = 120)
    public static void mainHandRulesAndOffHandPass(GameTestHelper helper) {
        ServerLevel level = rotating(helper);
        BlockPos center = new BlockPos(448, 65, 0);
        loadSite(level, center);
        level.setBlockAndUpdate(center, AllBlocks.TRAIN_CONTROLS.getDefaultState());

        ServerPlayer player = player(level, center);
        AtomicReference<ChassisScreenPayload> reply = new AtomicReference<>();
        attachConnection(player, reply);

        PlayerInteractEvent.RightClickBlock empty = click(player, center, InteractionHand.MAIN_HAND);
        ChassisEvents.onUse(empty);
        helper.assertTrue(empty.isCanceled() && reply.get() != null, "empty main hand opens the chassis menu");
        ChassisSelection.clear();

        PlayerInteractEvent.RightClickBlock offhand = click(player, center, InteractionHand.OFF_HAND);
        ChassisEvents.onUse(offhand);
        helper.assertTrue(!offhand.isCanceled(), "the off hand never opens the menu");

        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.STONE));
        PlayerInteractEvent.RightClickBlock stone = click(player, center, InteractionHand.MAIN_HAND);
        ChassisEvents.onUse(stone);
        helper.assertTrue(!stone.isCanceled(), "other items pass through");

        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        player.setShiftKeyDown(true);
        PlayerInteractEvent.RightClickBlock sneakEmpty = click(player, center, InteractionHand.MAIN_HAND);
        ChassisEvents.onUse(sneakEmpty);
        helper.assertTrue(!sneakEmpty.isCanceled(), "sneaking with an empty hand passes");

        player.setShiftKeyDown(false);
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(AllItems.WRENCH.get()));
        reply.set(null);
        PlayerInteractEvent.RightClickBlock wrench = click(player, center, InteractionHand.MAIN_HAND);
        ChassisEvents.onUse(wrench);
        helper.assertTrue(wrench.isCanceled() && reply.get() != null,
                "a normal wrench right-click opens the menu");
        ChassisSelection.clear();

        player.setShiftKeyDown(true);
        PlayerInteractEvent.RightClickBlock wrenchSneak = click(player, center, InteractionHand.MAIN_HAND);
        ChassisEvents.onUse(wrenchSneak);
        helper.assertTrue(!wrenchSneak.isCanceled(), "sneaking with the wrench passes for disassembly");

        player.setShiftKeyDown(false);
        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        player.setPos(center.getX() + 10.0, center.getY() + 1.0, center.getZ() + 0.5);
        PlayerInteractEvent.RightClickBlock far = click(player, center, InteractionHand.MAIN_HAND);
        ChassisEvents.onUse(far);
        helper.assertTrue(!far.isCanceled(), "a controller out of reach passes without a menu");

        player.setPos(center.getX() + 0.5, center.getY() + 1.0, center.getZ() + 0.5);
        BlockPos offTrack = center.offset(0, 0, 7);
        level.setBlockAndUpdate(offTrack, AllBlocks.TRAIN_CONTROLS.getDefaultState());
        PlayerInteractEvent.RightClickBlock noTrack = click(player, offTrack, InteractionHand.MAIN_HAND);
        ChassisEvents.onUse(noTrack);
        helper.assertTrue(!noTrack.isCanceled(),
                "a controller without generated track below passes without a menu");
        level.removeBlock(offTrack, false);

        ChassisSelection.clear();
        level.removeBlock(center, false);
        helper.succeed();
    }

    private static ServerLevel rotating(GameTestHelper helper) {
        ServerLevel level = helper.getLevel().getServer().getLevel(ResourceKey.create(Registries.DIMENSION,
                ResourceLocation.parse("dimblend:rotating")));
        helper.assertTrue(level != null, "rotating dimension must be loaded");
        return level;
    }

    private static void loadSite(ServerLevel level, BlockPos center) {
        for (int x = (center.getX() - 25) >> 4; x <= (center.getX() + 25) >> 4; x++) {
            level.getChunk(x, -1);
            level.getChunk(x, 0);
        }
        if (dimblend.api.CorridorRails.isGeneratedTrack(level, center.below())) {
            com.simibubi.create.content.trains.track.TrackPropagator.onRailAdded(level, center.below(),
                    level.getBlockState(center.below()));
        }
    }

    private static ServerPlayer player(ServerLevel level, BlockPos center) {
        ServerPlayer player = new ServerPlayer(level.getServer(), level,
                new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "chassis-interaction-test"),
                net.minecraft.server.level.ClientInformation.createDefault());
        player.setPos(center.getX() + 0.5, center.getY() + 1.0, center.getZ() + 0.5);
        return player;
    }

    private static void attachConnection(ServerPlayer player, AtomicReference<ChassisScreenPayload> reply) {
        new ServerGamePacketListenerImpl(player.serverLevel().getServer(),
                new Connection(PacketFlow.SERVERBOUND), player,
                CommonListenerCookie.createInitial(player.getGameProfile(), false)) {
            @Override
            public void send(Packet<?> packet) {
                if (packet instanceof ClientboundCustomPayloadPacket custom
                        && custom.payload() instanceof ChassisScreenPayload response) {
                    reply.set(response);
                }
            }
        };
    }

    private static PlayerInteractEvent.RightClickBlock click(ServerPlayer player, BlockPos pos, InteractionHand hand) {
        return new PlayerInteractEvent.RightClickBlock(player, hand, pos,
                new BlockHitResult(pos.getCenter(), Direction.UP, pos, false));
    }

    private ChassisInteractionGameTests() {
    }
}
