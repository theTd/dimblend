package dimblend.experience.chassis;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ChassisBuildDataTest {
    @Test void restartRetainsSelectedBlueprintOriginAndCompletedRows() {
        var data = new ChassisBuildData();
        var power = new ChassisBuildData.Build(new BlockPos(384, 65, 0), ChassisBlueprint.POWER);
        power.rows = 17;
        data.builds.add(power);
        var carriage = new ChassisBuildData.Build(new BlockPos(448, 65, 0), ChassisBlueprint.CARRIAGE);
        carriage.rows = 45;
        data.builds.add(carriage);
        var restored = ChassisBuildData.load(data.save(new CompoundTag(), null));
        assertEquals(2, restored.builds.size());
        assertEquals(power.center, restored.builds.getFirst().center);
        assertEquals(ChassisBlueprint.POWER, restored.builds.getFirst().blueprint);
        assertEquals(17, restored.builds.getFirst().rows);
        assertEquals(ChassisBlueprint.CARRIAGE, restored.builds.getLast().blueprint);
        assertEquals(45, restored.builds.getLast().rows);
    }
}
