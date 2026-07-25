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
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/** World-persistent state for fallout, clouds and terrain conversion. */
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
    private static final String TERRAIN_EFFECT_ID_TAG = "TerrainEffectId";
    private static final String TERRAIN_STARTED_TAG = "TerrainStarted";
    private static final String TERRAIN_AGE_TAG = "TerrainAge";
    private static final String TERRAIN_VERSION_TAG = "TerrainVersion";
    private static final int CURRENT_TERRAIN_VERSION = 32;
    private static final String PROCESSED_TERRAIN_CHUNKS_TAG =
            "ProcessedTerrainChunks";

    private final List<PersistentEffect> effects =
            new ArrayList<>();

    private DetonationSavedData() {
    }

    public static DetonationSavedData get(
            MinecraftServer server
    ) {
        return server.overworld()
                .getDataStorage()
                .computeIfAbsent(FACTORY, FILE_NAME);
    }

    private static DetonationSavedData load(
            CompoundTag tag,
            HolderLookup.Provider lookupProvider
    ) {
        DetonationSavedData data = new DetonationSavedData();
        ListTag effectTags = tag.getList(EFFECTS_TAG, Tag.TAG_COMPOUND);

        for (int index = 0; index < effectTags.size(); index++) {
            CompoundTag effectTag = effectTags.getCompound(index);

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
                BlockPos centre = BlockPos.of(
                        effectTag.getLong(CENTRE_TAG)
                );

                long terrainEffectId = effectTag.contains(
                        TERRAIN_EFFECT_ID_TAG,
                        Tag.TAG_LONG
                )
                        ? effectTag.getLong(TERRAIN_EFFECT_ID_TAG)
                        : deriveTerrainEffectId(dimension, centre);

                /* Old v23 saves already contain completed craters. */
                boolean terrainStarted = !effectTag.contains(
                        TERRAIN_STARTED_TAG,
                        Tag.TAG_BYTE
                ) || effectTag.getBoolean(TERRAIN_STARTED_TAG);

                int terrainVersion = effectTag.contains(
                        TERRAIN_VERSION_TAG,
                        Tag.TAG_INT
                )
                        ? effectTag.getInt(TERRAIN_VERSION_TAG)
                        : 0;
                int terrainAgeTicks = effectTag.contains(
                        TERRAIN_AGE_TAG,
                        Tag.TAG_INT
                )
                        ? Math.max(0, effectTag.getInt(TERRAIN_AGE_TAG))
                        : 0;

                Set<Long> processedTerrainChunks = new HashSet<>();
                if (effectTag.contains(
                        PROCESSED_TERRAIN_CHUNKS_TAG,
                        Tag.TAG_LONG_ARRAY
                )) {
                    for (long chunk : effectTag.getLongArray(
                            PROCESSED_TERRAIN_CHUNKS_TAG
                    )) {
                        processedTerrainChunks.add(chunk);
                    }
                }

                if (terrainVersion < CURRENT_TERRAIN_VERSION) {
                    processedTerrainChunks.clear();
                    terrainAgeTicks = 0;
                    terrainVersion = CURRENT_TERRAIN_VERSION;
                }

                data.effects.add(
                        new PersistentEffect(
                                dimension,
                                centre,
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
                                ),
                                terrainEffectId,
                                terrainStarted,
                                terrainAgeTicks,
                                terrainVersion,
                                processedTerrainChunks
                        )
                );
            } catch (RuntimeException ignored) {
                /* Skip malformed entries without preventing world loading. */
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
            effectTag.putLong(CENTRE_TAG, effect.centre.asLong());
            effectTag.putFloat(FALLOUT_RADIUS_TAG, effect.falloutRadius);
            effectTag.putInt(
                    FALLOUT_DURATION_TAG,
                    effect.falloutDurationTicks
            );
            effectTag.putInt(FALLOUT_AGE_TAG, effect.falloutAgeTicks);
            effectTag.putFloat(
                    FALLOUT_STRENGTH_TAG,
                    effect.falloutStrength
            );
            effectTag.putFloat(VISUAL_SCALE_TAG, effect.visualScale);
            effectTag.putInt(CLOUD_AGE_TAG, effect.cloudAgeTicks);
            effectTag.putFloat(CLOUD_STRENGTH_TAG, effect.cloudStrength);
            effectTag.putLong(
                    TERRAIN_EFFECT_ID_TAG,
                    effect.terrainEffectId
            );
            effectTag.putBoolean(
                    TERRAIN_STARTED_TAG,
                    effect.terrainStarted
            );
            effectTag.putInt(TERRAIN_AGE_TAG, effect.terrainAgeTicks);
            effectTag.putInt(TERRAIN_VERSION_TAG, effect.terrainVersion);
            effectTag.putLongArray(
                    PROCESSED_TERRAIN_CHUNKS_TAG,
                    effect.processedTerrainChunks.stream()
                            .mapToLong(Long::longValue)
                            .toArray()
            );
            effectTags.add(effectTag);
        }

        tag.put(EFFECTS_TAG, effectTags);
        return tag;
    }

    public long addDetonation(
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

        long terrainEffectId = createTerrainEffectId(dimension, centre);
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
                        Math.clamp(cloudStrength, 0.0F, 1.0F),
                        terrainEffectId,
                        false,
                        0,
                        CURRENT_TERRAIN_VERSION,
                        new HashSet<>()
                )
        );
        setDirty();
        return terrainEffectId;
    }

    public void startTerrainConversion(
            long terrainEffectId
    ) {
        for (PersistentEffect effect : effects) {
            if (effect.terrainEffectId == terrainEffectId) {
                effect.terrainStarted = true;
                effect.terrainAgeTicks = 0;
                effect.terrainVersion = CURRENT_TERRAIN_VERSION;
                setDirty();
                return;
            }
        }
    }

    public TerrainZone terrainZoneById(
            long terrainEffectId
    ) {
        for (PersistentEffect effect : effects) {
            if (
                    effect.terrainEffectId == terrainEffectId
                            && effect.terrainStarted
            ) {
                return effect.toTerrainZone();
            }
        }
        return null;
    }

    public List<TerrainZone> terrainZonesFor(
            ResourceKey<Level> dimension
    ) {
        List<TerrainZone> zones = new ArrayList<>();
        for (PersistentEffect effect : effects) {
            if (
                    effect.terrainStarted
                            && effect.dimension.equals(dimension)
                            && effect.falloutActive()
            ) {
                zones.add(effect.toTerrainZone());
            }
        }
        return List.copyOf(zones);
    }

    public boolean isTerrainChunkProcessed(
            long terrainEffectId,
            long chunkPos
    ) {
        for (PersistentEffect effect : effects) {
            if (effect.terrainEffectId == terrainEffectId) {
                return effect.processedTerrainChunks.contains(chunkPos);
            }
        }
        return false;
    }

    public void markTerrainChunkProcessed(
            long terrainEffectId,
            long chunkPos
    ) {
        for (PersistentEffect effect : effects) {
            if (
                    effect.terrainEffectId == terrainEffectId
                            && effect.processedTerrainChunks.add(chunkPos)
            ) {
                setDirty();
                return;
            }
        }
    }

    /** Advances saved ages on the logical server. */
    public void tick() {
        boolean changed = false;
        Iterator<PersistentEffect> iterator = effects.iterator();

        while (iterator.hasNext()) {
            PersistentEffect effect = iterator.next();

            if (
                    effect.terrainStarted
                            && effect.terrainAgeTicks
                            < DetonationTuning.FalloutTerrain.SPREAD_MAX_AGE_TICKS
            ) {
                effect.terrainAgeTicks++;
                changed = true;
            }

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
                    effect.falloutAgeTicks < effect.falloutDurationTicks
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

    public record TerrainZone(
            long effectId,
            ResourceKey<Level> dimension,
            BlockPos centre,
            float falloutRadius,
            float visualScale,
            int terrainAgeTicks
    ) {
    }

    private static long createTerrainEffectId(
            ResourceKey<Level> dimension,
            BlockPos centre
    ) {
        return mix64(
                deriveTerrainEffectId(dimension, centre)
                        ^ System.nanoTime()
        );
    }

    private static long deriveTerrainEffectId(
            ResourceKey<Level> dimension,
            BlockPos centre
    ) {
        long value = centre.asLong();
        value ^= (long) dimension.location().toString().hashCode()
                * 0x9E3779B97F4A7C15L;
        return mix64(value);
    }

    private static long mix64(long value) {
        value ^= value >>> 30;
        value *= 0xBF58476D1CE4E5B9L;
        value ^= value >>> 27;
        value *= 0x94D049BB133111EBL;
        return value ^ value >>> 31;
    }

    private static final class PersistentEffect {
        private final ResourceKey<Level> dimension;
        private final BlockPos centre;
        private final float falloutRadius;
        private final int falloutDurationTicks;
        private final float falloutStrength;
        private final float visualScale;
        private final float cloudStrength;
        private final long terrainEffectId;
        private final Set<Long> processedTerrainChunks;

        private int falloutAgeTicks;
        private int cloudAgeTicks;
        private boolean terrainStarted;
        private int terrainAgeTicks;
        private int terrainVersion;

        private PersistentEffect(
                ResourceKey<Level> dimension,
                BlockPos centre,
                float falloutRadius,
                int falloutDurationTicks,
                int falloutAgeTicks,
                float falloutStrength,
                float visualScale,
                int cloudAgeTicks,
                float cloudStrength,
                long terrainEffectId,
                boolean terrainStarted,
                int terrainAgeTicks,
                int terrainVersion,
                Set<Long> processedTerrainChunks
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
            this.terrainEffectId = terrainEffectId;
            this.terrainStarted = terrainStarted;
            this.terrainAgeTicks = Math.max(0, terrainAgeTicks);
            this.terrainVersion = terrainVersion;
            this.processedTerrainChunks = processedTerrainChunks;
        }

        private boolean falloutActive() {
            return falloutDurationTicks <= 0
                    || falloutAgeTicks < falloutDurationTicks;
        }

        private boolean cloudActive() {
            return cloudAgeTicks
                    < DetonationTuning.ParticleCloud.TOTAL_LIFETIME_TICKS;
        }

        private TerrainZone toTerrainZone() {
            return new TerrainZone(
                    terrainEffectId,
                    dimension,
                    centre,
                    falloutRadius,
                    visualScale,
                    terrainAgeTicks
            );
        }
    }
}
