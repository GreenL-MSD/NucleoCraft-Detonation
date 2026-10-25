package com.github.loganmartingreen.nucleocraftdetonation.terrain.crater;

import com.github.loganmartingreen.nucleocraftdetonation.NucleoCraftDetonation;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.List;

/**
 * Remember completed surface craters in each dimension so detonating again
 * at the same site cannot repeatedly use the previous crater floor as a fresh
 * reference surface and cut a deeper shaft.
 *
 * This tracks only crater footprints, not every changed block, and is saved
 * separately from persistent fallout so clearing clouds does not clear it.
 */
public final class SurfaceCraterHistory extends SavedData {
    private static final String FILE_NAME = NucleoCraftDetonation.MOD_ID + "_surface_craters";
    private static final int MAX_RECORDS = 4096;
    private static final SavedData.Factory<SurfaceCraterHistory> FACTORY =
            new SavedData.Factory<>(SurfaceCraterHistory::new, SurfaceCraterHistory::load);

    private final List<CraterFootprint> craters = new ArrayList<>();

    private SurfaceCraterHistory() { }

    public static SurfaceCraterHistory get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(FACTORY, FILE_NAME);
    }

    /** True for repeat shots near an existing, same-size or larger completed crater. */
    public boolean alreadyExcavated(BlockPos centre, int radius) {
        for (CraterFootprint footprint : craters) {
            if (SurfaceCraterRepeatRules.sameSite(
                    footprint.x(), footprint.z(), footprint.radius(),
                    centre.getX(), centre.getZ(), radius)) return true;
        }
        return false;
    }

    /** Take a compact per-shot snapshot. Querying thousands of columns
     * against the full saved list would be expensive for large yields.
     */
    public List<CraterFootprint> protectorsNear(BlockPos centre, int newRadius) {
        List<CraterFootprint> nearby = new ArrayList<>();
        for (CraterFootprint footprint : craters) {
            if (footprint.radius() < newRadius) continue;
            double reach = footprint.radius() + (double) newRadius;
            double dx = footprint.x() - centre.getX();
            double dz = footprint.z() - centre.getZ();
            if (dx * dx + dz * dz <= reach * reach) nearby.add(footprint);
        }
        return List.copyOf(nearby);
    }

    /** Stops overlapping equal/smaller craters from excavating below the
     * already-lowered terrain, even when the two blast centres are different.
     */
    public boolean protectsColumn(int x, int z, int newRadius) {
        for (CraterFootprint footprint : craters) {
            if (SurfaceCraterRepeatRules.protectsColumn(
                    footprint.x(), footprint.z(), footprint.radius(),
                    newRadius, x, z)) {
                return true;
            }
        }
        return false;
    }

    /** Called only when physical surface excavation has actually completed. */
    public void recordCompleted(BlockPos centre, int radius) {
        if (alreadyExcavated(centre, radius)) return;
        craters.add(new CraterFootprint(centre.getX(), centre.getZ(), radius));
        if (craters.size() > MAX_RECORDS) craters.remove(0);
        setDirty();
    }

    private static SurfaceCraterHistory load(CompoundTag tag, HolderLookup.Provider lookup) {
        SurfaceCraterHistory data = new SurfaceCraterHistory();
        ListTag saved = tag.getList("Craters", Tag.TAG_COMPOUND);
        for (int i = 0; i < saved.size(); i++) {
            CompoundTag footprint = saved.getCompound(i);
            int radius = footprint.getInt("Radius");
            if (radius > 0) {
                data.craters.add(new CraterFootprint(
                        footprint.getInt("X"), footprint.getInt("Z"), radius));
            }
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider lookup) {
        ListTag records = new ListTag();
        for (CraterFootprint crater : craters) {
            CompoundTag saved = new CompoundTag();
            saved.putInt("X", crater.x());
            saved.putInt("Z", crater.z());
            saved.putInt("Radius", crater.radius());
            records.add(saved);
        }
        tag.put("Craters", records);
        return tag;
    }

    public record CraterFootprint(int x, int z, int radius) { }
}
