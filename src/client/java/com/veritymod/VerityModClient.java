package com.veritymod;

import com.veritymod.entity.ModEntities;
import com.veritymod.entity.renderer.VerityEntityRenderer;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;

public class VerityModClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        EntityRendererRegistry.register(ModEntities.VERITY, VerityEntityRenderer::new);
    }
}
