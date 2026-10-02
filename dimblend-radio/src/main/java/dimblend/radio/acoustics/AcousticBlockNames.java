package dimblend.radio.acoustics;

import java.util.HashMap;
import java.util.Map;

/**
 * Guesses a modded block's {@link AcousticMaterials} index from its registry path, for blocks
 * whose tags and sound type say nothing better than stone (mods often keep the default stone
 * sound, or register their own sound types).
 * <p>
 * The path is split at underscores. A material word ({@code steel}, {@code velvet}, {@code glass},
 * {@code limestone}) decides; when several appear, the last one wins, since names put the head
 * noun last: {@code iron_framed_glass} is glass, {@code deepslate_tin_ore} is stone. Compounds
 * ending in a material ({@code locometal}, {@code voidstone}, {@code ironwood}) count as that
 * material. Only without any material word does a form word ({@code bricks}, {@code casing},
 * {@code table}, {@code sofa}) imply its usual material. Fired bricks of soil are stone.
 */
public final class AcousticBlockNames {
    /** No material could be inferred. */
    public static final int UNKNOWN = -1;

    private static final Map<String, Integer> MATERIAL_WORDS = new HashMap<>();
    private static final Map<String, Integer> FORM_WORDS = new HashMap<>();
    /** Compound endings, checked in order for words not in {@link #MATERIAL_WORDS}. */
    private static final String[] SUFFIXES = {"stone", "slate", "rock", "metal", "glass", "wood", "stem"};
    private static final int[] SUFFIX_MATERIALS = {AcousticMaterials.STONE, AcousticMaterials.STONE,
            AcousticMaterials.STONE, AcousticMaterials.METAL, AcousticMaterials.GLASS, AcousticMaterials.WOOD,
            AcousticMaterials.WOOD};

    static {
        words(MATERIAL_WORDS, AcousticMaterials.WOOL, "wool woolen cloth fabric felt cotton linen silk velvet fur fleece"
                + " hay straw thatch moss sponge foam padded padding quilt quilted plush rug mattress curtain curtains"
                + " tapestry cushion cactus");
        words(MATERIAL_WORDS, AcousticMaterials.FOLIAGE, "leaves leaf foliage hedge shrub");
        words(MATERIAL_WORDS, AcousticMaterials.SOIL, "dirt soil mud muddy sand gravel clay loam peat silt grass turf"
                + " sod compost mulch farmland podzol mycelium humus earth loess");
        words(MATERIAL_WORDS, AcousticMaterials.WOOD, "wood wooden timber lumber bark plank planks log logs stem stems"
                + " hyphae oak spruce birch jungle acacia mangrove bamboo maple aspen jacaranda willow fir cypress"
                + " redwood palm pine mahogany sakura baobab eucalyptus walnut chestnut hickory kapok teak cedar elm"
                + " beech larch alder poplar sequoia magnolia skyroot");
        words(MATERIAL_WORDS, AcousticMaterials.STONE, "stone cobblestone cobble cobbled rock granite diorite andesite"
                + " basalt blackstone deepslate tuff calcite marble limestone slate shale sandstone travertine dolomite"
                + " gabbro dacite breccia scoria obsidian concrete cement asphalt plaster terracotta ceramic porcelain"
                + " ore netherrack quartz flint boulder masonry stucco coral");
        words(MATERIAL_WORDS, AcousticMaterials.GLASS, "glass crystal amethyst");
        words(MATERIAL_WORDS, AcousticMaterials.METAL, "metal metallic iron steel copper gold golden brass bronze tin"
                + " zinc aluminum aluminium nickel silver titanium tungsten platinum cobalt netherite chrome chromium"
                + " electrum invar");
        words(MATERIAL_WORDS, AcousticMaterials.ICE, "ice");
        words(MATERIAL_WORDS, AcousticMaterials.SNOW, "snow");

        words(FORM_WORDS, AcousticMaterials.STONE, "brick bricks tile tiles shingle shingles");
        words(FORM_WORDS, AcousticMaterials.GLASS, "pane panes window windows");
        words(FORM_WORDS, AcousticMaterials.WOOD, "bookshelf barrel crate table chair stool bench desk cabinet shelf"
                + " drawer");
        words(FORM_WORDS, AcousticMaterials.WOOL, "carpet sofa couch pillow bed seat");
        words(FORM_WORDS, AcousticMaterials.METAL, "machine casing girder plating sheet boiler engine tank pipe"
                + " postbox toolbox smokestack chute");
    }

    private static void words(Map<String, Integer> table, int material, String words) {
        for (String word : words.split(" ")) {
            if (table.put(word, material) != null) throw new IllegalStateException("Duplicate acoustic word " + word);
        }
    }

    /** @return the inferred material, or {@link #UNKNOWN} */
    public static int infer(String path) {
        String[] words = path.split("_");
        int material = UNKNOWN;
        for (int i = words.length - 1; i >= 0 && material == UNKNOWN; i--) material = materialWord(words[i]);
        boolean bricks = false;
        int form = UNKNOWN;
        for (int i = words.length - 1; i >= 0; i--) {
            Integer implied = FORM_WORDS.get(words[i]);
            if (implied == null) continue;
            if (form == UNKNOWN) form = implied;
            bricks |= implied == AcousticMaterials.STONE;
        }
        if (material == AcousticMaterials.SOIL && bricks) return AcousticMaterials.STONE;
        return material != UNKNOWN ? material : form;
    }

    private static int materialWord(String word) {
        Integer material = MATERIAL_WORDS.get(word);
        if (material != null) return material;
        for (int i = 0; i < SUFFIXES.length; i++) {
            // A longer compound only: "stem" alone is listed, "jinglestem" ends in it.
            if (word.length() > SUFFIXES[i].length() + 1 && word.endsWith(SUFFIXES[i])) return SUFFIX_MATERIALS[i];
        }
        return UNKNOWN;
    }

    private AcousticBlockNames() { }
}
