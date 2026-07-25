package com.github.loganmartingreen.nucleocraftdetonation.persistence;

import com.github.loganmartingreen.nucleocraftdetonation.NucleoCraftDetonation;
import com.github.loganmartingreen.nucleocraftdetonation.config.DetonationTuning;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * World-persistent state for fallout zones and long-lived mushroom clouds.
 *
 * <p>The data is attached to the Overworld because it contains entries for
 * every dimension. The Overworld is the server's persistent level and is the
 * recommended home for multi-dimension SavedData.</p>
 */
public final class DetonationSavedData extends SavedData {
    private static final String FILE_NAME =
            NucleoCraftDetonation.MOD_ID + "_persistent_effects";

    private static final SavedData.Factory<DetonationSavedData> FACTORY =
            new SavedData.Factory<>(
                    DetonationSavedData::new,
                    DetonationSavedData::load
            );

    private static final String EFFECTS_TAG = "Effects";
    private static final String DIMENSION_TAG = "Dimension";
    private static final String CENTRE_TAG = "Centre";
    private static final String FALLOUT_RADIUS_TAG = "FalloutRadius";
    private static final String FALLOUT_DURATION_TAG = "FalloutDuration";
    private static final String FALLOUT_AGE_TAG = "FalloutAge";
    private static final String FALLOUT_STRENGTH_TAG = "FalloutStrength";
    private static final String VISUAL_SCALE_TAG = "VisualScale";
    private static final String CLOUD_AGE_TAG = "CloudAge";
    private static final String CLOUD_STRENGTH_TAG = "CloudStrength";

    private final List<PersistentEffect> effects =
            new ArrayList<>();

    private DetonationSavedData() {
    }

    public static DetonationSavedData get(
            MinecraftServer server
    ) {
        return server.overworld()
                .getDataStorage()
                .computeIfAbsent(
                        FACTORY,
                        FILE_NAME
                );
    }

    private static DetonationSavedData load(
            CompoundTag tag,
            HolderLookup.Provider lookupProvider
    ) {
        DetonationSavedData data =
                new DetonationSavedData();

        ListTag effectTags = tag.getList(
                EFFECTS_TAG,
                Tag.TAG_COMPOUND
        );

        for (int index = 0; index < effectTags.size(); index++) {
            CompoundTag effectTag =
                    effectTags.getCompound(index);

            try {
                ResourceLocation dimensionLocation =
                        ResourceLocation.parse(
                                effectTag.getString(DIMENSION_TAG)
                        );

                ResourceKey<Level> dimension =
                        ResourceKey.create(
                                Registries.DIMENSION,
                                dimensionLocation
                        );

                data.effects.add(
                        new PersistentEffect(
                                dimension,
                                BlockPos.of(
                                        effectTag.getLong(CENTRE_TAG)
                                ),
                                Math.max(
                                        1.0F,
                                        effectTag.getFloat(FALLOUT_RADIUS_TAG)
                                ),
                                effectTag.getInt(FALLOUT_DURATION_TAG),
                                Math.max(
                                        0,
                                        effectTag.getInt(FALLOUT_AGE_TAG)
                                ),
                                Math.clamp(
                                        effectTag.getFloat(FALLOUT_STRENGTH_TAG),
                                        0.0F,
                                        1.0F
                                ),
                                Math.max(
                                        1.0F,
                                        effectTag.getFloat(VISUAL_SCALE_TAG)
                                ),
                                Math.max(
                                        0,
                                        effectTag.getInt(CLOUD_AGE_TAG)
                                ),
                                Math.clamp(
                                        effectTag.getFloat(CLOUD_STRENGTH_TAG),
                                        0.0F,
                                        1.0F
                                )
                        )
                );
            } catch (RuntimeException ignored) {
                /* Skip a malformed entry without preventing the world loading. */
            }
        }

        return data;
    }

    @Override
    public CompoundTag save(
            CompoundTag tag,
            HolderLookup.Provider registries
    ) {
        ListTag effectTags = new ListTag();

        for (PersistentEffect effect : effects) {
            CompoundTag effectTag = new CompoundTag();

            effectTag.putString(
                    DIMENSION_TAG,
                    effect.dimension.location().toString()
            );
            effectTag.putLong(
                    CENTRE_TAG,
                    effect.centre.asLong()
            );
            effectTag.putFloat(
                    FALLOUT_RADIUS_TAG,
                    effect.falloutRadius
            );
            effectTag.putInt(
                    FALLOUT_DURATION_TAG,
                    effect.falloutDurationTicks
            );
            effectTag.putInt(
                    FALLOUT_AGE_TAG,
                    effect.falloutAgeTicks
            );
            effectTag.putFloat(
                    FALLOUT_STRENGTH_TAG,
                    effect.falloutStrength
            );
            effectTag.putFloat(
                    VISUAL_SCALE_TAG,
                    effect.visualScale
            );
            effectTag.putInt(
                    CLOUD_AGE_TAG,
                    effect.cloudAgeTicks
            );
            effectTag.putFloat(
                    CLOUD_STRENGTH_TAG,
                    effect.cloudStrength
            );

            effectTags.add(effectTag);
        }

        tag.put(EFFECTS_TAG, effectTags);
        return tag;
    }

    public void addDetonation(
            ResourceKey<Level> dimension,
            BlockPos centre,
            float falloutRadius,
            int falloutDurationTicks,
            float falloutStrength,
            float visualScale,
            float cloudStrength
    ) {
        effects.removeIf(effect ->
                effect.dimension.equals(dimension)
                        && effect.centre.equals(centre)
        );

        effects.add(
                new PersistentEffect(
                        dimension,
                        centre.immutable(),
                        Math.max(1.0F, falloutRadius),
                        falloutDurationTicks <= 0
                                ? -1
                                : falloutDurationTicks,
                        0,
                        Math.clamp(falloutStrength, 0.0F, 1.0F),
                        Math.max(1.0F, visualScale),
                        0,
                        Math.clamp(cloudStrength, 0.0F, 1.0F)
                )
        );

        setDirty();
    }

    /**
     * Advances saved ages on the logical server. Offline real-world time is
     * deliberately not counted; the effect resumes from its saved game age.
     */
    public void tick() {
        boolean changed = false;
        Iterator<PersistentEffect> iterator =
                effects.iterator();

        while (iterator.hasNext()) {
            PersistentEffect effect = iterator.next();

            if (
                    effect.cloudAgeTicks
                            < DetonationTuning.ParticleCloud.TOTAL_LIFETIME_TICKS
            ) {
                effect.cloudAgeTicks++;
                changed = true;
            }

            if (effect.falloutDurationTicks <= 0) {
                if (
                        effect.falloutAgeTicks
                                < DetonationTuning.Fallout.FADE_IN_TICKS
                ) {
                    effect.falloutAgeTicks++;
                    changed = true;
                }
            } else if (
                    effect.falloutAgeTicks
                            < effect.falloutDurationTicks
            ) {
                effect.falloutAgeTicks++;
                changed = true;
            }

            if (!effect.cloudActive() && !effect.falloutActive()) {
                iterator.remove();
                changed = true;
            }
        }

        if (changed) {
            setDirty();
        }
    }

    public List<Snapshot> snapshotsFor(
            ResourceKey<Level> dimension
    ) {
        List<Snapshot> snapshots = new ArrayList<>();

        for (PersistentEffect effect : effects) {
            if (!effect.dimension.equals(dimension)) {
                continue;
            }

            boolean falloutActive = effect.falloutActive();
            boolean cloudActive = effect.cloudActive();

            if (!falloutActive && !cloudActive) {
                continue;
            }

            snapshots.add(
                    new Snapshot(
                            effect.centre,
                            falloutActive,
                            effect.falloutRadius,
                            effect.falloutDurationTicks,
                            effect.falloutAgeTicks,
                            effect.falloutStrength,
                            cloudActive,
                            effect.visualScale,
                            effect.cloudAgeTicks,
                            effect.cloudStrength
                    )
            );
        }

        return List.copyOf(snapshots);
    }

    public record Snapshot(
            BlockPos centre,
            boolean falloutActive,
            float falloutRadius,
            int falloutDurationTicks,
            int falloutAgeTicks,
            float falloutStrength,
            boolean cloudActive,
            float visualScale,
            int cloudAgeTicks,
            float cloudStrength
    ) {
    }

    private static final class PersistentEffect {
        private final ResourceKey<Level> dimension;
        private final BlockPos centre;
        private final float falloutRadius;
        private final int falloutDurationTicks;
        private final float falloutStrength;
        private final float visualScale;
        private final float cloudStrength;

        private int falloutAgeTicks;
        private int cloudAgeTicks;

        private PersistentEffect(
                ResourceKey<Level> dimension,
                BlockPos centre,
                float falloutRadius,
                int falloutDurationTicks,
                int falloutAgeTicks,
                float falloutStrength,
                float visualScale,
                int cloudAgeTicks,
                float cloudStrength
        ) {
            this.dimension = dimension;
            this.centre = centre;
            this.falloutRadius = falloutRadius;
            this.falloutDurationTicks = falloutDurationTicks;
            this.falloutAgeTicks = falloutAgeTicks;
            this.falloutStrength = falloutStrength;
            this.visualScale = visualScale;
            this.cloudAgeTicks = cloudAgeTicks;
            this.cloudStrength = cloudStrength;
        }

        private boolean falloutActive() {
            return falloutDurationTicks <= 0
                    || falloutAgeTicks < falloutDurationTicks;
        }

        private boolean cloudActive() {
            return cloudAgeTicks
                    < DetonationTuning.ParticleCloud.TOTAL_LIFETIME_TICKS;
        }
    }
}
