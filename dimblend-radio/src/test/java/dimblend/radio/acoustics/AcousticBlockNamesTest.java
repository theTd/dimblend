package dimblend.radio.acoustics;

import org.junit.jupiter.api.Test;
import static dimblend.radio.acoustics.AcousticBlockNames.UNKNOWN;
import static dimblend.radio.acoustics.AcousticBlockNames.infer;
import static dimblend.radio.acoustics.AcousticMaterials.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Names below are real block paths from the TrainTripWorld mod pack. */
class AcousticBlockNamesTest {
    private static void expect(int material, String... paths) {
        for (String path : paths) assertEquals(material, infer(path), path);
    }

    @Test void materialWordsDecide() {
        expect(METAL, "industrial_iron_block", "electrum_block", "golem_steel_block", "netherite_bars", "waxed_copper_floor");
        expect(WOOL, "white_velvet_block", "light_gray_table_cloth", "purple_yeti_fur", "glowing_moss_block");
        expect(GLASS, "arid_glass", "cut_amethyst", "crystal_chest");
        expect(STONE, "dacite", "cut_dolomite_stairs", "magenta_asphalt", "orange_cement", "zanite_ore");
        expect(SOIL, "echo_soil", "arid_sand", "lush_dirt", "aether_grass_block", "packed_pale_mud");
        expect(ICE, "haze_ice", "packed_black_ice");
        expect(SNOW, "ashen_snow");
        expect(FOLIAGE, "currant_leaves", "leaf_pile");
        expect(WOOD, "maple_planks", "stripped_laurel_log", "chip_wood_slab", "oak_blinds");
    }

    @Test void theLastMaterialWordWinsBecauseTheHeadNounComesLast() {
        expect(GLASS, "ornate_iron_glass_pane", "framed_glass_door");
        expect(STONE, "deepslate_tin_ore", "polished_cut_blackstone_stairs", "dripstone_millstone");
        expect(METAL, "copper_sheet_metal", "light_gray_stone_metal", "raw_zinc_block");
        expect(WOOD, "golden_oak_wood");
        expect(SOIL, "golden_grass");
        expect(STONE, "dead_golden_coral_block");
        expect(FOLIAGE, "crystal_leaves", "ironwood_leaves");
        expect(ICE, "haze_ice_bricks", "reinforced_ice_pane");
    }

    @Test void compoundsEndingInAMaterialCount() {
        expect(METAL, "white_slashed_locometal", "green_brass_wrapped_locometal");
        expect(STONE, "cobbled_voidstone_slab", "smooth_gloomslate_slab", "holystone_pressure_plate");
        expect(WOOD, "ironwood_planks", "jinglestem_pressure_plate", "cradlewood");
    }

    @Test void formWordsOnlyWithoutAMaterialWord() {
        expect(STONE, "radianite_brick_stairs", "carmine_shingle_wall", "flare_tile_stairs");
        expect(METAL, "distillation_tank", "huge_diesel_engine", "tan_large_metal_girder", "orange_postbox");
        expect(WOOL, "purple_sofa", "red_seat");
        expect(GLASS, "flowglaze_pane", "canopy_window");
        expect(WOOD, "morado_bookshelf");
        expect(STONE, "lime_concrete_encased_fluid_pipe", "quartz_picture_frame");
    }

    @Test void firedSoilBricksAreStone() {
        expect(STONE, "pale_mud_bricks", "nightfall_mud_brick_slab", "small_packed_mud_brick_stairs");
    }

    @Test void unknownNamesStayUnknown() {
        expect(UNKNOWN, "doll_85", "verdant_container", "pink_bore_block", "florus_pressure_plate", "");
    }
}
