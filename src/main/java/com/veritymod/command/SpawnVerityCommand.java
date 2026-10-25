package com.veritymod.command;

import com.veritymod.entity.ModEntities;
import com.veritymod.entity.VerityEntity;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

public final class SpawnVerityCommand {
    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(CommandManager.literal("spawnverity")
                .executes(context -> {
                    ServerPlayerEntity player = context.getSource().getPlayer();
                    if (player == null) return 0;
                    VerityEntity entity = ModEntities.VERITY.create(player.getWorld());
                    if (entity == null) return 0;
                    entity.refreshPositionAndAngles(player.getX(), player.getY() + 1.0, player.getZ(), player.getYaw(), 0);
                    player.getWorld().spawnEntity(entity);
                    context.getSource().sendFeedback(() -> Text.literal("Verity появилась рядом с игроком"), false);
                    return 1;
                }));
    }
}
