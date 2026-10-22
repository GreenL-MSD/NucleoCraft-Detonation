package com.github.loganmartingreen.nucleocraftdetonation.fluid;

import com.github.loganmartingreen.nucleocraftdetonation.NucleoCraftDetonation;
import com.github.loganmartingreen.nucleocraftdetonation.block.DetonationBlocks;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.material.Fluid;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.fluids.BaseFlowingFluid;
import net.neoforged.neoforge.fluids.FluidType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

/** Water-like fallout fluid. It does not spread contamination by terrain logic; the scheduler decides conversion. */
public final class DetonationFluids {
    public static final DeferredRegister<FluidType> FLUID_TYPES =
            DeferredRegister.create(NeoForgeRegistries.Keys.FLUID_TYPES, NucleoCraftDetonation.MOD_ID);

    public static final DeferredRegister<Fluid> FLUIDS =
            DeferredRegister.create(Registries.FLUID, NucleoCraftDetonation.MOD_ID);

    public static final DeferredHolder<FluidType, FluidType> IRRADIATED_WATER_TYPE =
            FLUID_TYPES.register(
                    "irradiated_water",
                    () -> new FluidType(
                            FluidType.Properties.create()
                                    .descriptionId("fluid.nucleocraft_detonation.irradiated_water")
                                    .canExtinguish(true)
                                    .canConvertToSource(true)
                                    .supportsBoating(true)
                                    .canHydrate(true)
                    )
            );

    public static final DeferredHolder<Fluid, BaseFlowingFluid.Source> IRRADIATED_WATER =
            FLUIDS.register(
                    "irradiated_water",
                    () -> new BaseFlowingFluid.Source(createProperties())
            );

    public static final DeferredHolder<Fluid, BaseFlowingFluid.Flowing> FLOWING_IRRADIATED_WATER =
            FLUIDS.register(
                    "flowing_irradiated_water",
                    () -> new BaseFlowingFluid.Flowing(createProperties())
            );

    private static BaseFlowingFluid.Properties createProperties() {
        return new BaseFlowingFluid.Properties(
                IRRADIATED_WATER_TYPE,
                IRRADIATED_WATER,
                FLOWING_IRRADIATED_WATER
        )
                .block(() -> DetonationBlocks.IRRADIATED_WATER.get())
                .slopeFindDistance(4)
                .levelDecreasePerBlock(1)
                .tickRate(5)
                .explosionResistance(100.0F);
    }

    private DetonationFluids() {
    }

    public static void register(IEventBus modBus) {
        FLUID_TYPES.register(modBus);
        FLUIDS.register(modBus);
    }
}
