package dimblend.experience.reward;

/**
 * What a reward wagon carries. One wagon carries exactly one of these.
 *
 * <p>The requirement's table gives a different roll per lane; {@link #roll} turns a
 * lane name plus a random draw into one entry. Ore entries carry both the ore block
 * that fills the marked cells and the raw-ore block that replaces a few of them.
 *
 * <p><b>The ore table was redone on 2026-11-04.</b> The first version followed the 2026-11-02
 * wording (surface 30% iron / 70% copper, underground 60% iron / 20% gold / 20% copper). The
 * correction reads:
 * <ul>
 *   <li>surface: 30% shallow copper / 50% shallow zinc / 20% shallow iron;</li>
 *   <li>underground: 50% deep iron / 30% deep zinc / 20% deep copper.</li>
 * </ul>
 * Zinc is Create's, so there is no vanilla id for it: the ore is {@code create:zinc_ore} /
 * {@code create:deepslate_zinc_ore} and the raw block is {@code create:raw_zinc_block}
 * (all three confirmed in the Create 6.0.10 jar). Gold is gone from every lane — nothing rolls it
 * any more, so {@code DEEP_GOLD} was deleted rather than left as a constant no roll can produce.
 *
 * <p><b>Saltpeter has no raw block.</b> Eternal Starlight 0.9.0 only ships
 * {@code saltpeter_block}, so that is what stands in for the raw ore block there. The
 * player was asked and chose this.
 */
public enum RewardWagonContent {
    // Ore contents: the first id fills every marked cell, the second replaces a few.
    SHALLOW_COPPER("minecraft:copper_ore", "minecraft:raw_copper_block"),
    SHALLOW_ZINC("create:zinc_ore", "create:raw_zinc_block"),
    SHALLOW_IRON("minecraft:iron_ore", "minecraft:raw_iron_block"),
    DEEP_IRON("minecraft:deepslate_iron_ore", "minecraft:raw_iron_block"),
    DEEP_ZINC("create:deepslate_zinc_ore", "create:raw_zinc_block"),
    DEEP_COPPER("minecraft:deepslate_copper_ore", "minecraft:raw_copper_block"),
    DEEPSILVER("eternal_starlight:grimstone_deepsilver_ore", "eternal_starlight:raw_deepsilver_block"),
    SALTPETER("eternal_starlight:grimstone_saltpeter_ore", "eternal_starlight:saltpeter_block"),

    // Special contents: exactly one block (or one small cluster) on the content layer.
    TWILIGHT_CHEST(null, null),
    SOUND_TRAP(null, null),
    MOTOR_WAGON(null, null),
    /** No wagon at all; used for the lanes the requirement leaves empty. */
    NONE(null, null);

    private final String oreBlock;
    private final String rawBlock;

    RewardWagonContent(String oreBlock, String rawBlock) {
        this.oreBlock = oreBlock;
        this.rawBlock = rawBlock;
    }

    /** The ore block id, or {@code null} when this content is not an ore. */
    public String oreBlock() {
        return oreBlock;
    }

    /** The raw-ore block id, or {@code null} when this content is not an ore. */
    public String rawBlock() {
        return rawBlock;
    }

    /** True when the ore procedure (markers 1/2/3) applies to this content. */
    public boolean isOre() {
        return oreBlock != null;
    }

    /**
     * Rolls the content for one wagon.
     *
     * @param lane the lane name {@code dimblend} reports for this region
     * @param roll a uniform draw in {@code [0, 1)}
     */
    public static RewardWagonContent roll(String lane, float roll) {
        return switch (lane) {
            // 2026-11-04: 30% shallow copper / 50% shallow zinc / 20% shallow iron.
            case "surface" -> roll < 0.30F ? SHALLOW_COPPER
                    : roll < 0.80F ? SHALLOW_ZINC : SHALLOW_IRON;
            // 2026-11-04: 50% deep iron / 30% deep zinc / 20% deep copper.
            case "underground" -> roll < 0.50F ? DEEP_IRON
                    : roll < 0.80F ? DEEP_ZINC : DEEP_COPPER;
            case "nether" -> DEEP_IRON;
            case "starlight" -> roll < 0.40F ? DEEPSILVER : SALTPETER;
            case "twilight" -> TWILIGHT_CHEST;
            case "deeperdarker" -> SOUND_TRAP;
            case "aether" -> MOTOR_WAGON;
            default -> NONE;
        };
    }

    /** How many wagons this lane gets, as a rule the caller turns into a draw. */
    public enum CountRule {
        /** Never. */
        NONE,
        /** Exactly one, on the spawn-side edge of the window. */
        ONE_AT_SPAWN_END,
        /** Exactly one, anywhere in the window. */
        ONE_RANDOM,
        /** The requirement's 20% none / 50% one / 30% two. */
        ROLL_0_1_2
    }

    /** Which quantity rule applies to a lane. */
    public static CountRule countRule(String lane) {
        return switch (lane) {
            // The nether line only says "exactly one"; with a single window that is a draw
            // anywhere in it. The aether line pins its wagon to the start, which is the window's
            // spawn-side edge.
            case "nether" -> CountRule.ONE_RANDOM;
            case "aether" -> CountRule.ONE_AT_SPAWN_END;
            case "surface", "underground", "twilight", "starlight", "deeperdarker" -> CountRule.ROLL_0_1_2;
            // end, voidscape and every unlisted mod lane: the requirement lists no content,
            // and a wagon with no content would satisfy "only the structure is left" on the
            // first tick and delete itself before anyone could see it.
            default -> CountRule.NONE;
        };
    }
}
