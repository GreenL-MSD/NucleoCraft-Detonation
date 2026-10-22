package com.github.loganmartingreen.nucleocraftdetonation;

import com.github.loganmartingreen.nucleocraftdetonation.block.DetonationBlocks;
import com.github.loganmartingreen.nucleocraftdetonation.command.DetonationCommands;
import com.github.loganmartingreen.nucleocraftdetonation.config.DetonationClientConfig;
import com.github.loganmartingreen.nucleocraftdetonation.fluid.DetonationFluids;
import com.github.loganmartingreen.nucleocraftdetonation.detonation.DetonationManager;
import com.github.loganmartingreen.nucleocraftdetonation.sound.DetonationSounds;
import com.github.loganmartingreen.nucleocraftdetonation.terrain.FalloutTerrainManager;
import com.github.loganmartingreen.nucleocraftdetonation.particle.DetonationParticles;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;

@Mod(NucleoCraftDetonation.MOD_ID)
public final class NucleoCraftDetonation {
    public static final String MOD_ID = "nucleocraft_detonation";

    public NucleoCraftDetonation(IEventBus modBus, ModContainer modContainer) {
        modContainer.registerConfig(
                ModConfig.Type.CLIENT,
                DetonationClientConfig.SPEC,
                "nucleocraft_detonation-client.toml"
        );

        DetonationFluids.register(modBus);
        DetonationBlocks.register(modBus);
        DetonationSounds.SOUND_EVENTS.register(modBus);
        DetonationParticles.register(modBus);
        modBus.addListener(FalloutTerrainManager::registerTicketController);

        NeoForge.EVENT_BUS.addListener(DetonationCommands::onRegisterCommands);
        NeoForge.EVENT_BUS.addListener(DetonationManager::onServerTick);
        NeoForge.EVENT_BUS.addListener(FalloutTerrainManager::onChunkLoad);
    }
}
