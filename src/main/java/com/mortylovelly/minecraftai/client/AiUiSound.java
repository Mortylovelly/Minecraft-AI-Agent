package com.mortylovelly.minecraftai.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.sound.SoundEvents;

public final class AiUiSound {
    private AiUiSound(){}
    public static void playClick(){play(0.58F,1.0F);}
    public static void playOpen(){play(0.58F,1.10F);}
    public static void playClose(){play(0.58F,0.88F);}
    private static void play(float volume,float pitch){
        MinecraftClient client=MinecraftClient.getInstance();
        if(client.player==null)return;
        client.getSoundManager().play(PositionedSoundInstance.master(SoundEvents.UI_BUTTON_CLICK,volume,pitch));
    }
}
