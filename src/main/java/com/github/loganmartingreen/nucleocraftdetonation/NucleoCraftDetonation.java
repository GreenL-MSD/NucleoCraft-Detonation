package com.github.loganmartingreen.nucleocraftdetonation;

import com.github.loganmartingreen.nucleocraftdetonation.command.DetonationCommands;
import com.github.loganmartingreen.nucleocraftdetonation.detonation.DetonationManager;
import com.github.loganmartingreen.nucleocraftdetonation.sound.DetonationSounds;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;

@Mod(NucleoCraftDetonation.MOD_ID)
public final class NucleoCraftDetonation {
    public static final String MOD_ID = "nucleocraft_detonation";

    public NucleoCraftDetonation(IEventBus modBus) {
        DetonationSounds.SOUND_EVENTS.register(modBus);

        NeoForge.EVENT_BUS.addListener(DetonationCommands::onRegisterCommands);
        NeoForge.EVENT_BUS.addListener(DetonationManager::onServerTick);
    }
}
