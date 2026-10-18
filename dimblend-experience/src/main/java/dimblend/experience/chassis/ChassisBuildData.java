package dimblend.experience.chassis;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/** Only unfinished construction is persisted; completed cars belong to Sable. */
public final class ChassisBuildData extends SavedData {
    private static final Factory<ChassisBuildData> FACTORY = new Factory<>(ChassisBuildData::new,
            (tag, registries) -> load(tag), null);
    final List<Build> builds = new ArrayList<>();

    static final class Build {
        final BlockPos center;
        final ChassisBlueprint blueprint;
        int rows;
        int delay;
        Build(BlockPos center, ChassisBlueprint blueprint) {
            this.center = center.immutable();
            this.blueprint = blueprint;
        }
    }

    public static ChassisBuildData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(FACTORY, "dimblend_chassis_builds");
    }

    static ChassisBuildData load(CompoundTag tag) {
        ChassisBuildData data = new ChassisBuildData();
        ListTag jobs = tag.getList("builds", Tag.TAG_COMPOUND);
        for (int i = 0; i < jobs.size(); i++) {
            CompoundTag job = jobs.getCompound(i);
            String kind = job.getString("kind");
            Build build = new Build(BlockPos.of(job.getLong("center")), ChassisBlueprint.valueOf(kind));
            build.rows = Math.clamp(job.getInt("rows"), 0, ChassisBlueprint.LENGTH);
            data.builds.add(build);
        }
        return data;
    }

    @Override public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag jobs = new ListTag();
        for (Build build : builds) {
            CompoundTag job = new CompoundTag();
            job.putLong("center", build.center.asLong());
            job.putString("kind", build.blueprint.name());
            job.putInt("rows", build.rows);
            jobs.add(job);
        }
        tag.put("builds", jobs);
        return tag;
    }
}
