package com.github.loganmartingreen.nucleocraftdetonation.particle;

import com.github.loganmartingreen.nucleocraftdetonation.NucleoCraftDetonation;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleType;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.function.Supplier;

/**
 * Registration and network/data representation for the tinted poof particle.
 *
 * <p>The particle uses the vanilla poof sprite animation, but every instance
 * can supply its own starting colour, ending colour, size, lifetime and light
 * behaviour. This lets the same particle form the white-hot fireball and the
 * darker mushroom cloud.</p>
 */
public final class DetonationParticles {
    public static final DeferredRegister<ParticleType<?>> PARTICLE_TYPES =
            DeferredRegister.create(
                    BuiltInRegistries.PARTICLE_TYPE,
                    NucleoCraftDetonation.MOD_ID
            );

    public static final Supplier<ParticleType<TintedPoofOptions>> TINTED_POOF =
            PARTICLE_TYPES.register(
                    "tinted_poof",
                    () -> new ParticleType<TintedPoofOptions>(true) {
                        @Override
                        public MapCodec<TintedPoofOptions> codec() {
                            return TintedPoofOptions.CODEC;
                        }

                        @Override
                        public StreamCodec<? super RegistryFriendlyByteBuf, TintedPoofOptions> streamCodec() {
                            return TintedPoofOptions.STREAM_CODEC;
                        }
                    }
            );

    private DetonationParticles() {
    }

    public static void register(IEventBus modBus) {
        PARTICLE_TYPES.register(modBus);
    }

    public record TintedPoofOptions(
            int startRgb,
            int endRgb,
            float startScale,
            float endScale,
            int lifetime,
            float gravity,
            float drag,
            float endAlpha,
            boolean fullBright
    ) implements ParticleOptions {
        public static final MapCodec<TintedPoofOptions> CODEC =
                RecordCodecBuilder.mapCodec(instance -> instance.group(
                        Codec.INT.fieldOf("start_rgb").forGetter(TintedPoofOptions::startRgb),
                        Codec.INT.fieldOf("end_rgb").forGetter(TintedPoofOptions::endRgb),
                        Codec.FLOAT.fieldOf("start_scale").forGetter(TintedPoofOptions::startScale),
                        Codec.FLOAT.fieldOf("end_scale").forGetter(TintedPoofOptions::endScale),
                        Codec.INT.fieldOf("lifetime").forGetter(TintedPoofOptions::lifetime),
                        Codec.FLOAT.fieldOf("gravity").forGetter(TintedPoofOptions::gravity),
                        Codec.FLOAT.fieldOf("drag").forGetter(TintedPoofOptions::drag),
                        Codec.FLOAT.fieldOf("end_alpha").forGetter(TintedPoofOptions::endAlpha),
                        Codec.BOOL.fieldOf("full_bright").forGetter(TintedPoofOptions::fullBright)
                ).apply(instance, TintedPoofOptions::new));

        public static final StreamCodec<RegistryFriendlyByteBuf, TintedPoofOptions> STREAM_CODEC =
                new StreamCodec<>() {
                    @Override
                    public TintedPoofOptions decode(RegistryFriendlyByteBuf buffer) {
                        return new TintedPoofOptions(
                                buffer.readVarInt(),
                                buffer.readVarInt(),
                                buffer.readFloat(),
                                buffer.readFloat(),
                                buffer.readVarInt(),
                                buffer.readFloat(),
                                buffer.readFloat(),
                                buffer.readFloat(),
                                buffer.readBoolean()
                        );
                    }

                    @Override
                    public void encode(
                            RegistryFriendlyByteBuf buffer,
                            TintedPoofOptions value
                    ) {
                        buffer.writeVarInt(value.startRgb());
                        buffer.writeVarInt(value.endRgb());
                        buffer.writeFloat(value.startScale());
                        buffer.writeFloat(value.endScale());
                        buffer.writeVarInt(value.lifetime());
                        buffer.writeFloat(value.gravity());
                        buffer.writeFloat(value.drag());
                        buffer.writeFloat(value.endAlpha());
                        buffer.writeBoolean(value.fullBright());
                    }
                };

        public TintedPoofOptions {
            startRgb &= 0xFFFFFF;
            endRgb &= 0xFFFFFF;
            startScale = Math.max(0.05F, startScale);
            endScale = Math.max(0.05F, endScale);
            lifetime = Math.max(1, lifetime);
            drag = Math.clamp(drag, 0.0F, 1.25F);
            endAlpha = Math.clamp(endAlpha, 0.0F, 1.0F);
        }

        @Override
        public ParticleType<?> getType() {
            return TINTED_POOF.get();
        }
    }
}
