package dimblend.experience.chassis;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ChassisInteractionTest {
    @Test
    void onlyMainHandEmptyOrWrenchOpensWhenApplicable() {
        assertEquals(ChassisInteraction.Action.OPEN, ChassisInteraction.decide(true, false, true, true));
        assertEquals(ChassisInteraction.Action.PASS, ChassisInteraction.decide(true, true, true, true));
        assertEquals(ChassisInteraction.Action.PASS, ChassisInteraction.decide(true, false, false, true));
        assertEquals(ChassisInteraction.Action.PASS, ChassisInteraction.decide(true, false, true, false));
    }

    @Test
    void offHandNeverOpens() {
        for (boolean sneaking : new boolean[]{false, true}) {
            for (boolean menuTrigger : new boolean[]{false, true}) {
                for (boolean applicable : new boolean[]{false, true}) {
                    assertEquals(ChassisInteraction.Action.PASS,
                            ChassisInteraction.decide(false, sneaking, menuTrigger, applicable));
                }
            }
        }
    }

    @Test
    void everyOtherCombinationPasses() {
        for (boolean mainHand : new boolean[]{false, true}) {
            for (boolean sneaking : new boolean[]{false, true}) {
                for (boolean menuTrigger : new boolean[]{false, true}) {
                    for (boolean applicable : new boolean[]{false, true}) {
                        boolean open = mainHand && !sneaking && menuTrigger && applicable;
                        assertEquals(open ? ChassisInteraction.Action.OPEN : ChassisInteraction.Action.PASS,
                                ChassisInteraction.decide(mainHand, sneaking, menuTrigger, applicable));
                    }
                }
            }
        }
    }
}
