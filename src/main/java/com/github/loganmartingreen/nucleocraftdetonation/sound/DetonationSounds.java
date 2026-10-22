package com.github.loganmartingreen.nucleocraftdetonation.sound;

import com.github.loganmartingreen.nucleocraftdetonation.NucleoCraftDetonation;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;

import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.function.Supplier;

public final class DetonationSounds {
    public static final DeferredRegister<SoundEvent> SOUND_EVENTS =
            DeferredRegister.create(
                    BuiltInRegistries.SOUND_EVENT,
                    NucleoCraftDetonation.MOD_ID
            );

    public static final Supplier<SoundEvent> NUCLEAR_BLAST =
            SOUND_EVENTS.register(
                    "nuclear_blast",
                    () -> SoundEvent.createVariableRangeEvent(
                            ResourceLocation.fromNamespaceAndPath(
                                    NucleoCraftDetonation.MOD_ID,
                                    "nuclear_blast"
                            )
                    )
            );

    private DetonationSounds() {
    }
}
