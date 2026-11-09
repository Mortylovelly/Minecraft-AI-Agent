package com.mortylovelly.minecraftai.mixin.client;

import com.mortylovelly.minecraftai.client.AiLogOverlay;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.Mouse;
import net.minecraft.client.util.Window;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Mouse.class)
public abstract class MouseMixin {
    @Shadow @Final private MinecraftClient client;

    @Inject(method="onMouseButton",at=@At("HEAD"),cancellable=true)
    private void minecraftAiAgent$onMouseButton(long window,int button,int action,int modifiers,CallbackInfo ci){
        if(client.currentScreen!=null||client.player==null||client.world==null
                ||!AiLogOverlay.isOpen()||client.mouse.isCursorLocked()
                ||button!=GLFW.GLFW_MOUSE_BUTTON_LEFT)return;

        Window w=client.getWindow();
        double mx=AiLogOverlay.toPanelX(scaledX(client.mouse.getX(),w));
        double my=AiLogOverlay.toPanelY(scaledY(client.mouse.getY(),w));
        if(action==GLFW.GLFW_PRESS){
            if(AiLogOverlay.mouseClicked(mx,my,button,w.getScaledWidth(),w.getScaledHeight()))ci.cancel();
        }else if(action==GLFW.GLFW_RELEASE){
            if(AiLogOverlay.mouseReleased(mx,my,button))ci.cancel();
        }
    }

    @Inject(method="onCursorPos",at=@At("HEAD"))
    private void minecraftAiAgent$onCursorPos(long window,double cursorX,double cursorY,CallbackInfo ci){
        if(client.currentScreen!=null||client.player==null||client.world==null
                ||!AiLogOverlay.isOpen()||client.mouse.isCursorLocked())return;

        Window w=client.getWindow();
        AiLogOverlay.mouseDragged(AiLogOverlay.toPanelX(scaledX(cursorX,w)),
                AiLogOverlay.toPanelY(scaledY(cursorY,w)),GLFW.GLFW_MOUSE_BUTTON_LEFT,
                w.getScaledWidth(),w.getScaledHeight());
    }

    @Inject(method="onMouseScroll",at=@At("HEAD"),cancellable=true)
    private void minecraftAiAgent$onMouseScroll(long window,double horizontal,double vertical,CallbackInfo ci){
        if(client.currentScreen!=null||client.player==null||client.world==null
                ||!AiLogOverlay.isOpen()||client.mouse.isCursorLocked())return;

        Window w=client.getWindow();
        double mx=AiLogOverlay.toPanelX(scaledX(client.mouse.getX(),w));
        double my=AiLogOverlay.toPanelY(scaledY(client.mouse.getY(),w));
        if(AiLogOverlay.mouseScrolled(mx,my,vertical,w.getScaledWidth(),w.getScaledHeight()))ci.cancel();
    }

    private static double scaledX(double rawX,Window window){
        return rawX*window.getScaledWidth()/(double)window.getWidth();
    }

    private static double scaledY(double rawY,Window window){
        return rawY*window.getScaledHeight()/(double)window.getHeight();
    }
}
