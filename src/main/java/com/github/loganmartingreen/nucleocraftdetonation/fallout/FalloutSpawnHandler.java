package com.github.loganmartingreen.nucleocraftdetonation.fallout;

import com.github.loganmartingreen.nucleocraftdetonation.persistence.DetonationSavedData;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.MobSpawnType;

import net.neoforged.neoforge.event.entity.living.MobSpawnEvent;

/** Prevents ambient world repopulation inside an active fallout zone. */
public final class FalloutSpawnHandler {
    private FalloutSpawnHandler() {
    }

    public static void onPositionCheck(MobSpawnEvent.PositionCheck event) {
        MobSpawnType spawnType = event.getSpawnType();
        if (spawnType != MobSpawnType.NATURAL
                && spawnType != MobSpawnType.CHUNK_GENERATION
                && spawnType != MobSpawnType.PATROL) {
            return;
        }

        if (!(event.getEntity().level() instanceof ServerLevel level)) {
            return;
        }

        if (DetonationSavedData.get(level.getServer()).isInsideActiveFallout(
                level.dimension(),
                event.getX(),
                event.getZ()
        )) {
            event.setResult(MobSpawnEvent.PositionCheck.Result.FAIL);
        }
    }
}
