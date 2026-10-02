package dimblend.radio.acoustics.terrain;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SectionBlockFingerprintTest {
    @Test void voxelPositionAndFirstBlockInAnEmptySectionChangeTheSignature() {
        long empty=SectionBlockFingerprint.of((x,y,z)->"air");
        long first=SectionBlockFingerprint.of((x,y,z)->x==1&&y==2&&z==3?"stone":"air");
        long moved=SectionBlockFingerprint.of((x,y,z)->x==3&&y==2&&z==1?"stone":"air");
        assertNotEquals(empty,first);
        assertNotEquals(first,moved);
        assertEquals(first,SectionBlockFingerprint.of((x,y,z)->x==1&&y==2&&z==3?"stone":"air"));
    }
}
