package dimblend.experience.gametest;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.mojang.authlib.GameProfile;

import dimblend.experience.Config;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.monster.Blaze;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * A12 GameTests. The rotating-dimension gate is injected through the config key
 * {@code rotatingDimensionId} (GameTests can only run in the overworld; the explicit
 * {@code minecraft:overworld} id is the documented test channel). The dimension id and
 * the density are restored in every finalizer.
 *
 * <p>Each test has its own {@code batch} so it runs alone: the GameTest default batch
 * would run other tests concurrently, and these tests change global config values for
 * their whole duration.
 */
@GameTestHolder("dimblend_experience")
@PrefixGameTestTemplate(false)
public final class MonsterDensityGameTests {

    private static final String TEMPLATE = "density_chamber";
    private static final String NAMESPACE = "dimblend_experience";
    private static final BlockPos SPAWNER = new BlockPos(3, 2, 3);
    private static final BlockPos PLAYER = new BlockPos(3, 2, 9);

    @GameTest(template = TEMPLATE, templateNamespace = NAMESPACE, timeoutTicks = 240,
            batch = "monster_density_zero")
    public static void zeroDensityStopsSpawnerBlockSpawns(GameTestHelper helper) {
        verifySpawnerBlock(helper, 0.0D, false);
    }

    @GameTest(template = TEMPLATE, templateNamespace = NAMESPACE, timeoutTicks = 240,
            batch = "monster_density_full")
    public static void fullDensityKeepsSpawnerBlockSpawns(GameTestHelper helper) {
        verifySpawnerBlock(helper, 1.0D, true);
    }

    @GameTest(template = TEMPLATE, templateNamespace = NAMESPACE, timeoutTicks = 80,
            batch = "monster_density_census")
    public static void censusCommandReportsTypesAndSpawnSources(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        String oldDimension = Config.ROTATING_DIMENSION_ID.get();
        double oldDensity = Config.ROTATING_MONSTER_DENSITY.get();
        List<Mob> spawned = new ArrayList<>();
        try {
            // Spawn types are recorded by finalizeSpawn; keep the density guard out of
            // the way so the two samples definitely enter the level.
            Config.ROTATING_MONSTER_DENSITY.set(1.0D);
            Mob zombie = EntityType.ZOMBIE.spawn(level, helper.absolutePos(new BlockPos(2, 2, 2)),
                    MobSpawnType.NATURAL);
            Mob skeleton = EntityType.SKELETON.spawn(level, helper.absolutePos(new BlockPos(4, 2, 4)),
                    MobSpawnType.SPAWNER);
            helper.assertTrue(zombie != null, "zombie must spawn");
            helper.assertTrue(skeleton != null, "skeleton must spawn");
            spawned.add(zombie);
            spawned.add(skeleton);

            List<String> messages = new ArrayList<>();
            ServerPlayer player = new ServerPlayer(level.getServer(), level,
                    new GameProfile(UUID.randomUUID(), "dbx-census"), ClientInformation.createDefault()) {
                @Override
                public void sendSystemMessage(Component message) {
                    messages.add(message.getString());
                }

                @Override
                public void sendSystemMessage(Component message, boolean overlay) {
                    messages.add(message.getString());
                }
            };
            Vec3 center = helper.absolutePos(SPAWNER).getCenter();
            player.setPos(center.x, center.y, center.z);
            CommandSourceStack source = player.createCommandSourceStack().withPermission(2);
            level.getServer().getCommands().performPrefixedCommand(source, "dbx mobcensus 8");
            helper.assertTrue(messages.stream().anyMatch(line -> line.contains("Monster census")),
                    "census header missing: " + messages);
            helper.assertTrue(messages.stream().anyMatch(line -> line.contains("minecraft:zombie") && line.contains("NATURAL")),
                    "zombie NATURAL row missing: " + messages);
            helper.assertTrue(messages.stream().anyMatch(line -> line.contains("minecraft:skeleton") && line.contains("SPAWNER")),
                    "skeleton SPAWNER row missing: " + messages);
            helper.assertTrue(messages.stream().anyMatch(line -> line.startsWith("Totals:")),
                    "census totals missing: " + messages);
        } finally {
            for (Mob mob : spawned) {
                mob.discard();
            }
            Config.ROTATING_DIMENSION_ID.set(oldDimension);
            Config.ROTATING_MONSTER_DENSITY.set(oldDensity);
        }
        helper.succeed();
    }

    private static void verifySpawnerBlock(GameTestHelper helper, double density, boolean expectSpawn) {
        ServerLevel level = helper.getLevel();
        String oldDimension = Config.ROTATING_DIMENSION_ID.get();
        double oldDensity = Config.ROTATING_MONSTER_DENSITY.get();
        List<ServerPlayer> players = new ArrayList<>();
        try {
            Config.ROTATING_DIMENSION_ID.set("minecraft:overworld");
            Config.ROTATING_MONSTER_DENSITY.set(density);
            buildChamber(helper);
            configureSpawner(helper, level);
            players.add(spawnerTestPlayer(helper));

            helper.runAfterDelay(100L, () -> {
                try {
                    int blazes = level.getEntitiesOfClass(Blaze.class,
                            new AABB(helper.absolutePos(SPAWNER)).inflate(12.0D)).size();
                    helper.assertTrue(expectSpawn ? blazes > 0 : blazes == 0,
                            "density " + density + " expected " + (expectSpawn ? ">=1" : "0")
                                    + " blaze(s), got " + blazes);
                } finally {
                    level.players().removeAll(players);
                    players.clear();
                    Config.ROTATING_DIMENSION_ID.set(oldDimension);
                    Config.ROTATING_MONSTER_DENSITY.set(oldDensity);
                }
                helper.succeed();
            });
        } catch (RuntimeException failure) {
            level.players().removeAll(players);
            players.clear();
            Config.ROTATING_DIMENSION_ID.set(oldDimension);
            Config.ROTATING_MONSTER_DENSITY.set(oldDensity);
            throw failure;
        }
    }

    /**
     * The spawner needs a player in {@code ServerLevel#players()}. Give it a no-op
     * packet listener: Create kinetic block entities broadcast particles to every
     * player in the level, and a null connection would crash the server tick.
     */
    private static ServerPlayer spawnerTestPlayer(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerPlayer player = new ServerPlayer(level.getServer(), level,
                new GameProfile(UUID.randomUUID(), "dbx-density"), ClientInformation.createDefault());
        new ServerGamePacketListenerImpl(level.getServer(), new Connection(PacketFlow.SERVERBOUND), player,
                CommonListenerCookie.createInitial(player.getGameProfile(), false)) {
            @Override
            public void send(Packet<?> packet) {
            }
        };
        player.gameMode.changeGameModeForPlayer(GameType.SURVIVAL);
        player.setPos(helper.absolutePos(PLAYER).getCenter());
        level.players().add(player);
        return player;
    }

    /** 7x5x7 all-air template turned into a light-sealed stone box with a floating spawner. */
    private static void buildChamber(GameTestHelper helper) {
        for (int x = 0; x < 7; x++) {
            for (int z = 0; z < 7; z++) {
                helper.setBlock(x, 0, z, Blocks.STONE);
                helper.setBlock(x, 4, z, Blocks.STONE);
            }
        }
        for (int y = 1; y <= 3; y++) {
            for (int x = 0; x < 7; x++) {
                helper.setBlock(x, y, 0, Blocks.STONE);
                helper.setBlock(x, y, 6, Blocks.STONE);
            }
            for (int z = 1; z <= 5; z++) {
                helper.setBlock(0, y, z, Blocks.STONE);
                helper.setBlock(6, y, z, Blocks.STONE);
            }
        }
        helper.setBlock(SPAWNER, Blocks.SPAWNER);
    }

    private static void configureSpawner(GameTestHelper helper, ServerLevel level) {
        BlockEntity blockEntity = helper.getBlockEntity(SPAWNER);
        helper.assertTrue(blockEntity instanceof SpawnerBlockEntity, "spawner block entity missing");
        SpawnerBlockEntity spawner = (SpawnerBlockEntity) blockEntity;
        spawner.setEntityId(EntityType.BLAZE, level.getRandom());
        CompoundTag tag = new CompoundTag();
        tag.putShort("Delay", (short) 0);
        tag.putShort("MinSpawnDelay", (short) 1);
        tag.putShort("MaxSpawnDelay", (short) 2);
        tag.putShort("SpawnCount", (short) 1);
        tag.putShort("MaxNearbyEntities", (short) 1);
        tag.putShort("RequiredPlayerRange", (short) 16);
        tag.putShort("SpawnRange", (short) 2);
        spawner.getSpawner().load(level, helper.absolutePos(SPAWNER), tag);
        spawner.setChanged();
    }

    private MonsterDensityGameTests() {
    }
}
