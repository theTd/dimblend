package dimblend.experience.gametest;

import com.simibubi.create.AllItems;
import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dimblend.experience.compat.dndecor.FrontlightSpotlight;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.PlayLevelSoundEvent;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Run with -PwithHeadlights -PheadlightDecorJar=<the installed DnDecor jar>. */
@PrefixGameTestTemplate(false)
public final class FrontlightActivationGameTests {
    private static final BlockPos MID = new BlockPos(1, 1, 1);

    @GameTest(template = "empty", templateNamespace = "dimblend_headlights", timeoutTicks = 100)
    public static void everyLampDefaultsOffAndAmethystActivatesOnlyOnce(GameTestHelper helper) {
        var level = helper.getLevel();
        var player = player(helper, GameType.SURVIVAL);
        var pos = helper.absolutePos(MID);
        int[] sounds = {0};
        Consumer<PlayLevelSoundEvent.AtPosition> capture = event -> {
            if (event.getLevel() == level && event.getSound() != null
                    && event.getSound().value() == SoundEvents.END_PORTAL_FRAME_FILL) sounds[0]++;
        };
        NeoForge.EVENT_BUS.addListener(PlayLevelSoundEvent.AtPosition.class, capture);
        try {
            int lamps = 0;
            for (Block block : BuiltInRegistries.BLOCK) {
                var state = block.defaultBlockState();
                if (!FrontlightSpotlight.isFrontlight(state)) continue;
                lamps++;
                helper.assertTrue(state.hasProperty(FrontlightSpotlight.ENABLED)
                        && !state.getValue(FrontlightSpotlight.ENABLED), "every lamp must default to no spotlight");
                for (InteractionHand hand : InteractionHand.values()) {
                    helper.setBlock(MID, state);
                    player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
                    player.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);
                    player.setItemInHand(hand, new ItemStack(Items.AMETHYST_SHARD, 3));
                    player.setShiftKeyDown(hand == InteractionHand.OFF_HAND);
                    int before = sounds[0];
                    use(player, pos, hand);
                    var activated = level.getBlockState(pos);
                    helper.assertTrue(FrontlightSpotlight.isEnabled(activated), "amethyst must activate either hand");
                    helper.assertTrue(activated.setValue(FrontlightSpotlight.ENABLED, false).equals(state),
                            "activation must preserve facing, light, redstone and decoration states");
                    helper.assertTrue(player.getItemInHand(hand).getCount() == 3, "amethyst is not consumed");
                    helper.assertTrue(sounds[0] == before + 1, "activation plays the portal-frame sound once");
                    use(player, pos, hand);
                    helper.assertTrue(sounds[0] == before + 1 && level.getBlockState(pos).equals(activated),
                            "repeated clicks must neither replay feedback nor disable the spotlight");
                }
            }
            helper.assertTrue(lamps > 1, "test must exercise the real DnDecor lamp variants");
            helper.succeed();
        } finally {
            NeoForge.EVENT_BUS.unregister(capture);
        }
    }

    @GameTest(template = "empty", templateNamespace = "dimblend_headlights", timeoutTicks = 100)
    public static void wrongItemsAndRestrictedPlayersDoNotActivate(GameTestHelper helper) {
        var pos = helper.absolutePos(MID);
        for (GameType mode : GameType.values()) {
            var player = player(helper, mode);
            for (var item : new ItemStack[] {ItemStack.EMPTY, new ItemStack(Items.QUARTZ),
                    new ItemStack(Items.AMETHYST_SHARD)}) {
                helper.setBlock(MID, lamp());
                player.setItemInHand(InteractionHand.MAIN_HAND, item);
                use(player, pos, InteractionHand.MAIN_HAND);
                boolean expected = (mode == GameType.SURVIVAL || mode == GameType.CREATIVE)
                        && item.is(Items.AMETHYST_SHARD);
                helper.assertTrue(FrontlightSpotlight.isEnabled(helper.getLevel().getBlockState(pos)) == expected,
                        "activation must require an amethyst shard and build permission: " + mode + ", " + item);
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "dimblend_headlights", timeoutTicks = 100)
    public static void savesKeepActivationButDropsAndWrenchPickupDoNot(GameTestHelper helper) {
        var level = helper.getLevel();
        var pos = helper.absolutePos(MID);
        var active = lamp().setValue(FrontlightSpotlight.ENABLED, true);
        var saved = NbtUtils.writeBlockState(active);
        helper.assertTrue(NbtUtils.readBlockState(BuiltInRegistries.BLOCK.asLookup(), saved).equals(active),
                "normal block-state save/load must retain activation");
        saved.getCompound("Properties").remove(FrontlightSpotlight.ENABLED.getName());
        helper.assertTrue(!FrontlightSpotlight.isEnabled(NbtUtils.readBlockState(BuiltInRegistries.BLOCK.asLookup(), saved)),
                "old saves without the property must load with spotlights disabled");

        helper.setBlock(MID, active);
        var drops = Block.getDrops(active, level, pos, null);
        helper.assertTrue(drops.size() == 1 && ItemStack.isSameItemSameComponents(drops.getFirst(), new ItemStack(active.getBlock())),
                "breaking must drop an ordinary lamp without activation data");
        var player = player(helper, GameType.SURVIVAL);
        level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
        player.setItemInHand(InteractionHand.MAIN_HAND, drops.getFirst());
        var place = new BlockPlaceContext(new UseOnContext(player, InteractionHand.MAIN_HAND, hit(pos)));
        helper.assertTrue(player.getItemInHand(InteractionHand.MAIN_HAND).useOn(place).consumesAction(), "drop must place");
        helper.assertTrue(!FrontlightSpotlight.isEnabled(level.getBlockState(pos)), "replaced lamp must need activation again");

        helper.setBlock(MID, active);
        player.setShiftKeyDown(true);
        player.setItemInHand(InteractionHand.MAIN_HAND, AllItems.WRENCH.asStack());
        use(player, pos, InteractionHand.MAIN_HAND);
        helper.assertTrue(level.getBlockState(pos).isAir(), "sneak wrench must still pick up the lamp");
        var recovered = player.getInventory().items.stream().filter(stack -> stack.is(active.getBlock().asItem())).findFirst();
        helper.assertTrue(recovered.isPresent() && ItemStack.isSameItemSameComponents(recovered.get(), new ItemStack(active.getBlock())),
                "wrench recovery must not preserve activation either");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "dimblend_headlights", timeoutTicks = 100)
    public static void vehicleAssemblyKeepsActivationAndPlotClicksCanActivate(GameTestHelper helper) {
        var level = helper.getLevel();
        var world = helper.absolutePos(MID);
        helper.setBlock(MID, lamp().setValue(FrontlightSpotlight.ENABLED, true));
        var vehicle = SubLevelAssemblyHelper.assembleBlocks(level, world,
                BlockPos.betweenClosed(world, world), new BoundingBox3i(world, world));
        helper.assertTrue(vehicle != null, "Sable assembly must succeed");
        List<net.minecraft.world.phys.Vec3> sounds = new ArrayList<>();
        Consumer<PlayLevelSoundEvent.AtPosition> capture = event -> {
            if (event.getLevel() == level && event.getSound() != null
                    && event.getSound().value() == SoundEvents.END_PORTAL_FRAME_FILL) sounds.add(event.getPosition());
        };
        NeoForge.EVENT_BUS.addListener(PlayLevelSoundEvent.AtPosition.class, capture);
        try {
            var plot = BlockPos.containing(vehicle.logicalPose().transformPositionInverse(world.getCenter()));
            var assembled = level.getBlockState(plot);
            helper.assertTrue(FrontlightSpotlight.isEnabled(assembled), "assembly must carry the activated block state");
            level.setBlockAndUpdate(plot, assembled.setValue(FrontlightSpotlight.ENABLED, false));
            var player = player(helper, GameType.SURVIVAL);
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.AMETHYST_SHARD));
            use(player, plot, InteractionHand.MAIN_HAND);
            helper.assertTrue(FrontlightSpotlight.isEnabled(level.getBlockState(plot)), "plot-side interaction must activate");
            helper.assertTrue(sounds.size() == 1 && sounds.getFirst().distanceToSqr(world.getCenter()) < 1.0e-6,
                    "vehicle feedback must play at the world position, not at the distant plot");
            helper.succeed();
        } finally {
            NeoForge.EVENT_BUS.unregister(capture);
            vehicle.markRemoved();
        }
    }

    @GameTest(template = "empty", templateNamespace = "dimblend_headlights", timeoutTicks = 100)
    public static void activationBroadcastsOneRedstoneParticleBurst(GameTestHelper helper) {
        var level = helper.getLevel();
        var pos = helper.absolutePos(MID);
        helper.setBlock(MID, lamp());
        List<ClientboundLevelParticlesPacket> particles = new ArrayList<>();
        var player = player(helper, GameType.SURVIVAL, packet -> {
            if (packet instanceof ClientboundLevelParticlesPacket burst) particles.add(burst);
        });
        level.addNewPlayer(player);
        try {
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.AMETHYST_SHARD));
            use(player, pos, InteractionHand.MAIN_HAND);
            helper.assertTrue(particles.size() == 1, "activation must broadcast exactly one particle burst");
            var burst = particles.getFirst();
            helper.assertTrue(burst.getParticle().getType() == ParticleTypes.DUST && burst.getCount() == 24,
                    "feedback must use a redstone-dust burst");
            helper.assertTrue(pos.getCenter().distanceToSqr(burst.getX(), burst.getY(), burst.getZ()) < 1.0e-6,
                    "particle burst must be centered on the lamp");
            use(player, pos, InteractionHand.MAIN_HAND);
            helper.assertTrue(particles.size() == 1, "repeated clicks must not replay particles");
            helper.succeed();
        } finally {
            player.discard();
        }
    }

    private static BlockState lamp() {
        return BuiltInRegistries.BLOCK.get(ResourceLocation.fromNamespaceAndPath("dndecor", "brass_frontlight"))
                .defaultBlockState().setValue(BlockStateProperties.LIT, true);
    }

    private static void use(ServerPlayer player, BlockPos pos, InteractionHand hand) {
        player.gameMode.useItemOn(player, player.level(), player.getItemInHand(hand), hand, hit(pos));
    }

    private static BlockHitResult hit(BlockPos pos) {
        return new BlockHitResult(pos.getCenter(), Direction.UP, pos, false);
    }

    private static ServerPlayer player(GameTestHelper helper, GameType mode) {
        return player(helper, mode, packet -> { });
    }

    private static ServerPlayer player(GameTestHelper helper, GameType mode, Consumer<Packet<?>> packets) {
        var level = helper.getLevel();
        var player = new ServerPlayer(level.getServer(), level,
                new com.mojang.authlib.GameProfile(UUID.randomUUID(), "headlight-test"), ClientInformation.createDefault());
        new ServerGamePacketListenerImpl(level.getServer(), new Connection(PacketFlow.SERVERBOUND), player,
                CommonListenerCookie.createInitial(player.getGameProfile(), false)) {
            @Override public void send(Packet<?> packet) { packets.accept(packet); }
        };
        player.gameMode.changeGameModeForPlayer(mode);
        player.setPos(helper.absolutePos(MID.above(2)).getCenter());
        return player;
    }

    private FrontlightActivationGameTests() { }
}
