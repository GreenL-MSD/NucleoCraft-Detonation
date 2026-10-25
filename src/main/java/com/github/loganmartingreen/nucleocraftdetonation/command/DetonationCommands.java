package com.github.loganmartingreen.nucleocraftdetonation.command;

import com.github.loganmartingreen.nucleocraftdetonation.config.DetonationTuning;
import com.github.loganmartingreen.nucleocraftdetonation.detonation.DetonationManager;
import com.github.loganmartingreen.nucleocraftdetonation.detonation.NuclearYield;
import com.github.loganmartingreen.nucleocraftdetonation.network.DetonationNetwork.ClearMushroomCloudsPayload;
import com.github.loganmartingreen.nucleocraftdetonation.persistence.DetonationSavedData;
import com.mojang.brigadier.arguments.StringArgumentType;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.network.PacketDistributor;

public final class DetonationCommands {
    private DetonationCommands() {
    }

    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(
                Commands.literal("nuke")
                        .requires(source -> source.hasPermission(2))
                        .then(
                                Commands.literal("detonate")
                                        .then(
                                                Commands.argument(
                                                                "yield",
                                                                StringArgumentType.word()
                                                        )
                                                        .executes(context -> detonate(
                                                                context.getSource(),
                                                                StringArgumentType.getString(
                                                                        context,
                                                                        "yield"
                                                                )
                                                        ))
                                        )
                        )
                        .then(Commands.literal("debug")
                                .then(Commands.literal("on")
                                        .executes(context -> setDebug(context.getSource(), true)))
                                .then(Commands.literal("off")
                                        .executes(context -> setDebug(context.getSource(), false))))
                        .then(
                                Commands.literal("clearclouds")
                                        .executes(context -> clearClouds(context.getSource()))
                        )
        );
    }

    private static int setDebug(CommandSourceStack source, boolean enabled) {
        try {
            DetonationManager.setDebug(source.getPlayerOrException(), enabled);
            return 1;
        } catch (Exception e) {
            source.sendFailure(Component.literal("Debug command must be run by a player."));
            return 0;
        }
    }

    private static int clearClouds(CommandSourceStack source) {
        int cleared = DetonationSavedData.get(source.getServer())
                .clearCloudEffects(source.getLevel().dimension());

        PacketDistributor.sendToPlayersInDimension(
                source.getLevel(),
                new ClearMushroomCloudsPayload(true)
        );

        source.sendSuccess(
                () -> Component.literal(
                        "Cleared " + cleared
                                + " persistent mushroom cloud effect"
                                + (cleared == 1 ? "" : "s")
                                + " in this dimension."
                ),
                true
        );
        return 1;
    }

    private static int detonate(
            CommandSourceStack source,
            String rawYield
    ) {
        final ServerPlayer player;

        try {
            player = source.getPlayerOrException();
        } catch (Exception exception) {
            source.sendFailure(
                    Component.literal(
                            "This development command must be run by a player."
                    )
            );
            return 0;
        }

        final NuclearYield yield;

        try {
            yield = NuclearYield.parse(rawYield);
        } catch (IllegalArgumentException exception) {
            source.sendFailure(
                    Component.literal(exception.getMessage())
            );
            return 0;
        }

        HitResult hitResult = player.pick(
                DetonationTuning.Command.TARGET_RANGE_BLOCKS,
                1.0F,
                false
        );

        if (
                !(hitResult instanceof BlockHitResult blockHit)
                        || hitResult.getType() != HitResult.Type.BLOCK
        ) {
            source.sendFailure(
                    Component.literal(
                            "Look at a block within "
                                    + (int) DetonationTuning.Command.TARGET_RANGE_BLOCKS
                                    + " blocks."
                    )
            );
            return 0;
        }

        BlockPos target = blockHit.getBlockPos().immutable();

        DetonationManager.scheduleDetonation(
                player,
                target,
                yield
        );

        return 1;
    }
}
