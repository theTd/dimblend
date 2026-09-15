package dimblend.gametest;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.decoration.copycat.CopycatBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Runtime weave check for copycat → obsidian hardness. JUnit only covers ID
 * matching; these assertions hit {@code BlockState.getDestroyProgress},
 * explosion resistance, and {@code canEntityDestroy} after mixins apply.
 */
@GameTestHolder("dimblend")
@PrefixGameTestTemplate(false)
public final class CopycatObsidianHardnessGameTests {

    private CopycatObsidianHardnessGameTests() {
    }

    @GameTest(template = "copycat_obsidian", templateNamespace = "dimblend", timeoutTicks = 200)
    public static void copycatPanelMatchesObsidian(GameTestHelper helper) {
        BlockPos rel = new BlockPos(1, 1, 1);
        helper.setBlock(rel, AllBlocks.COPYCAT_PANEL.get().defaultBlockState());
        BlockPos abs = helper.absolutePos(rel);
        BlockState copycat = helper.getBlockState(rel);
        BlockState obsidian = Blocks.OBSIDIAN.defaultBlockState();

        Player diamond = helper.makeMockPlayer(GameType.SURVIVAL);
        diamond.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.DIAMOND_PICKAXE));
        Player fist = helper.makeMockPlayer(GameType.SURVIVAL);

        assertSameDestroyProgress(helper, "empty+diamond", copycat, obsidian, diamond, abs);
        assertSameDestroyProgress(helper, "empty+fist", copycat, obsidian, fist, abs);
        float diamondProgress = copycat.getDestroyProgress(diamond, helper.getLevel(), abs);
        float fistProgress = copycat.getDestroyProgress(fist, helper.getLevel(), abs);
        helper.assertTrue(
                fistProgress < diamondProgress,
                "fist progress " + fistProgress + " should be slower than diamond " + diamondProgress);

        BlockEntity be = helper.getBlockEntity(rel);
        helper.assertTrue(be instanceof CopycatBlockEntity, "copycat panel must have CopycatBlockEntity");
        ((CopycatBlockEntity) be).setMaterial(Blocks.OAK_PLANKS.defaultBlockState());
        copycat = helper.getBlockState(rel);
        assertSameDestroyProgress(helper, "oak+diamond", copycat, obsidian, diamond, abs);

        Explosion explosion = new Explosion(
                helper.getLevel(),
                null,
                abs.getX() + 0.5,
                abs.getY() + 0.5,
                abs.getZ() + 0.5,
                4.0F,
                false,
                Explosion.BlockInteraction.DESTROY);
        float copycatResistance = copycat.getExplosionResistance(helper.getLevel(), abs, explosion);
        float obsidianResistance = obsidian.getExplosionResistance(helper.getLevel(), abs, explosion);
        helper.assertTrue(
                copycatResistance == obsidianResistance,
                "explosion resistance copycat=" + copycatResistance + " obsidian=" + obsidianResistance);

        EnderDragon dragon = helper.spawnWithNoFreeWill(EntityType.ENDER_DRAGON, rel);
        boolean copycatDragon = copycat.canEntityDestroy(helper.getLevel(), abs, dragon);
        boolean obsidianDragon = obsidian.canEntityDestroy(helper.getLevel(), abs, dragon);
        helper.assertTrue(copycatDragon == obsidianDragon, "dragon canEntityDestroy mismatch");
        helper.assertTrue(!copycatDragon, "obsidian is dragon-immune");
        dragon.discard();

        WitherBoss wither = helper.spawnWithNoFreeWill(EntityType.WITHER, rel);
        boolean copycatWither = copycat.canEntityDestroy(helper.getLevel(), abs, wither);
        boolean obsidianWither = obsidian.canEntityDestroy(helper.getLevel(), abs, wither);
        helper.assertTrue(copycatWither == obsidianWither, "wither canEntityDestroy mismatch");
        wither.discard();

        helper.succeed();
    }

    private static void assertSameDestroyProgress(
            GameTestHelper helper,
            String label,
            BlockState copycat,
            BlockState obsidian,
            Player player,
            BlockPos abs) {
        float actual = copycat.getDestroyProgress(player, helper.getLevel(), abs);
        float expected = obsidian.getDestroyProgress(player, helper.getLevel(), abs);
        helper.assertTrue(
                actual == expected,
                label + " destroy progress copycat=" + actual + " obsidian=" + expected);
    }
}
