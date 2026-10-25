package com.veritymod.sound;

import com.veritymod.VerityMod;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.Identifier;

public final class ModSounds {
    public static final SoundEvent VERITY_GREETING = register("verity_greeting");
    private static SoundEvent register(String id) {
        Identifier identifier = Identifier.of(VerityMod.MOD_ID, id);
        return Registry.register(Registries.SOUND_EVENT, identifier, SoundEvent.of(identifier));
    }
    public static void register() {}
}
