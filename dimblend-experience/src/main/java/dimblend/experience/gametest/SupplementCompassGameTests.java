package dimblend.experience.gametest;

import dimblend.experience.compat.compass.OriginCompass;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.CompassItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.LodestoneTracker;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Register explicitly with core dimblend + Simulated present; no skipped-success path. */
@PrefixGameTestTemplate(false)
public final class SupplementCompassGameTests {
    @GameTest(template = "empty", templateNamespace = "dimblend_chassis", timeoutTicks = 40,
            batch = "supplement_compass_spawn")
    public static void ordinaryCompassAndNavigationTableRespectDimensionsAndBindings(GameTestHelper helper)
            throws ReflectiveOperationException {
        var server = helper.getLevel().getServer();
        ServerLevel rotating = server.getLevel(ResourceKey.create(Registries.DIMENSION,
                ResourceLocation.fromNamespaceAndPath("dimblend", "rotating")));
        helper.assertTrue(rotating != null, "fixture requires the real dimblend:rotating dimension");
        ServerLevel overworld = server.overworld();
        ServerLevel nether = server.getLevel(Level.NETHER);
        helper.assertTrue(nether != null, "fixture requires the vanilla Nether");

        // DerivedLevelData shares this spawn. Change only the data, avoiding spawn tickets/chunk loads.
        var data = server.getWorldData().overworldData();
        BlockPos previousSpawn = data.getSpawnPos();
        float previousAngle = data.getSpawnAngle();
        try {
            data.setSpawn(new BlockPos(123, 80, -321), 0);
            helper.assertTrue(!rotating.getSharedSpawnPos().equals(BlockPos.ZERO),
                    "non-origin shared spawn is necessary to catch the original regression");
            helper.assertTrue(OriginCompass.target(rotating).equals(CompassItem.getSpawnPosition(rotating)),
                    "the actual static CompassItem method must point at the rotating origin");
            assertVanillaSpawn(helper, overworld);
            assertVanillaSpawn(helper, nether);

            ItemStack ordinary = new ItemStack(Items.COMPASS);
            helper.assertTrue(navigationTarget(rotating, ordinary).equals(BlockPos.ZERO.getCenter()),
                    "Simulated's actual getTarget must use the same origin for an ordinary compass");
            helper.assertTrue(navigationTarget(overworld, ordinary).equals(overworld.getSharedSpawnPos().getCenter()),
                    "navigation table outside rotating must retain its original shared-spawn target");
            helper.assertTrue(navigationTarget(nether, ordinary).equals(nether.getSharedSpawnPos().getCenter()),
                    "the navigation table's Nether behavior must remain unchanged");

            var binding = new LodestoneTracker(Optional.of(GlobalPos.of(rotating.dimension(),
                    new BlockPos(40, 90, -50))), false);
            ItemStack lodestone = new ItemStack(Items.COMPASS);
            lodestone.set(DataComponents.LODESTONE_TRACKER, binding);
            helper.assertTrue(navigationTarget(rotating, lodestone).equals(rotating.getSharedSpawnPos().getCenter()),
                    "vanilla-bound compasses must retain Simulated 1.3.0's original fallback");
            helper.assertTrue(binding.equals(lodestone.get(DataComponents.LODESTONE_TRACKER)),
                    "target lookup must not change the lodestone component");

            ItemStack sublevelBound = new ItemStack(Items.COMPASS);
            UUID missingBinding = UUID.randomUUID();
            DataComponentType<UUID> tracker = sublevelTracker();
            sublevelBound.set(tracker, missingBinding);
            helper.assertTrue(navigationTarget(rotating, sublevelBound).equals(rotating.getSharedSpawnPos().getCenter()),
                    "unresolved Simulated bindings must keep the upstream fallback instead of becoming ordinary");
            helper.assertTrue(missingBinding.equals(sublevelBound.get(tracker)), "sublevel binding must be retained");

            helper.assertTrue(OriginCompass.navigationSpawn(rotating, new ItemStack(Items.RECOVERY_COMPASS),
                    rotating.getSharedSpawnPos()).equals(rotating.getSharedSpawnPos()),
                    "the shared helper must not redirect recovery compasses");
        } finally {
            data.setSpawn(previousSpawn, previousAngle);
        }
        helper.succeed();
    }

    private static void assertVanillaSpawn(GameTestHelper helper, ServerLevel level) {
        GlobalPos actual = CompassItem.getSpawnPosition(level);
        if (level.dimensionType().natural()) {
            helper.assertTrue(GlobalPos.of(level.dimension(), level.getSharedSpawnPos()).equals(actual),
                    "natural dimensions outside rotating must keep vanilla spawn targeting");
        } else {
            helper.assertTrue(actual == null, "non-natural dimensions outside rotating must keep vanilla null targeting");
        }
    }

    private static Vec3 navigationTarget(ServerLevel level, ItemStack stack) throws ReflectiveOperationException {
        var block = BuiltInRegistries.BLOCK.get(ResourceLocation.fromNamespaceAndPath("simulated", "navigation_table"));
        if (!(block instanceof EntityBlock entityBlock)) {
            throw new IllegalStateException("fixture requires simulated:navigation_table");
        }
        BlockEntity table = entityBlock.newBlockEntity(BlockPos.ZERO, block.defaultBlockState());
        if (table == null) {
            throw new IllegalStateException("navigation table fixture did not create its block entity");
        }
        table.setLevel(level);
        Class<?> targetType = Class.forName("dev.simulated_team.simulated.content.navigation_targets.CompassNavigationTarget");
        Class<?> tableType = Class.forName("dev.simulated_team.simulated.content.blocks.nav_table.NavTableBlockEntity");
        return (Vec3) targetType.getMethod("getTarget", tableType, ItemStack.class)
                .invoke(targetType.getConstructor().newInstance(), table, stack);
    }

    @SuppressWarnings("unchecked")
    private static DataComponentType<UUID> sublevelTracker() {
        var tracker = BuiltInRegistries.DATA_COMPONENT_TYPE.get(
                ResourceLocation.fromNamespaceAndPath("simulated", "lodestone_compass_tracker"));
        if (tracker == null) {
            throw new IllegalStateException("fixture requires Simulated's lodestone compass tracker");
        }
        return (DataComponentType<UUID>) tracker;
    }

    private SupplementCompassGameTests() { }
}
