package com.veritymod;

import com.veritymod.command.SpawnVerityCommand;
import com.veritymod.entity.ModEntities;
import com.veritymod.sound.ModSounds;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import com.veritymod.entity.VerityEntity;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.Box;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public class VerityMod implements ModInitializer {
    public static final String MOD_ID = "veritymod";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    private static final Set<UUID> PLAYERS_WITH_VERITY = new HashSet<>();

    @Override
    public void onInitialize() {
        ModEntities.register();
        ModSounds.register();
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                SpawnVerityCommand.register(dispatcher));
        ServerTickEvents.END_WORLD_TICK.register(world -> {
            for (ServerPlayerEntity player : world.getPlayers()) {
                if (player.age > 20 && !PLAYERS_WITH_VERITY.contains(player.getUuid())) {
                    spawnInitialVerity(player);
                }
            }
        });
        // Right-click a block while carrying Verity to put her down there.
        UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
            if (hand != Hand.MAIN_HAND || player.isSpectator()) return ActionResult.PASS;
            VerityEntity carried = VerityEntity.findCarriedBy(player);
            if (carried == null) return ActionResult.PASS;
            if (!world.isClient) {
                BlockPos pos = hit.getBlockPos().offset(hit.getSide());
                if (!world.getBlockState(pos).getCollisionShape(world, pos).isEmpty()) pos = pos.up();
                carried.placeDown(Vec3d.ofBottomCenter(pos));
            }
            return ActionResult.SUCCESS;
        });
        LOGGER.info("Verity initialized");
    }

    private static void spawnInitialVerity(ServerPlayerEntity player) {
        Box searchArea = player.getBoundingBox().expand(32.0);
        boolean alreadyPresent = !player.getServerWorld().getEntitiesByType(
                ModEntities.VERITY,
                searchArea,
                entity -> entity.isAlive()
        ).isEmpty();
        if (alreadyPresent) return;

        var verity = ModEntities.VERITY.create(player.getServerWorld());
        if (verity == null) {
            LOGGER.error("Не удалось создать EntityType verity");
            return;
        }
        verity.refreshPositionAndAngles(player.getX(), player.getY() + 0.15, player.getZ(), player.getYaw(), 0.0f);
        if (player.getServerWorld().spawnEntity(verity)) {
            PLAYERS_WITH_VERITY.add(player.getUuid());
            LOGGER.info("Verity box spawned for {} at {}, {}, {}", player.getName().getString(), player.getBlockX(), player.getBlockY(), player.getBlockZ());
        }
    }
}
