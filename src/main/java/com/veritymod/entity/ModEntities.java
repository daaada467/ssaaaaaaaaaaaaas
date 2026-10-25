package com.veritymod.entity;

import com.veritymod.VerityMod;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricEntityTypeBuilder;
import net.minecraft.entity.EntityDimensions;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;

public final class ModEntities {
    public static final EntityType<VerityEntity> VERITY = Registry.register(
            Registries.ENTITY_TYPE,
            Identifier.of(VerityMod.MOD_ID, "verity"),
            FabricEntityTypeBuilder.create(SpawnGroup.MISC, VerityEntity::new)
                    .dimensions(EntityDimensions.fixed(1.0f, 1.0f))
                    .trackRangeBlocks(64)
                    .trackedUpdateRate(1)
                    .fireImmune()
                    .build()
    );

    private ModEntities() {}
    public static void register() {
        FabricDefaultAttributeRegistry.register(VERITY, VerityEntity.createVerityAttributes().build());
    }
}
