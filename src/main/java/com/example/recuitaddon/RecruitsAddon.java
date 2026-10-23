package com.example.recuitaddon;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;

@Mod(RecruitsAddon.MOD_ID)
public class RecruitsAddon {
    public static final String MOD_ID = "recruitsaddon";

    public RecruitsAddon() {
        MinecraftForge.EVENT_BUS.register(CommandCreateRecruits.class);
    }
}