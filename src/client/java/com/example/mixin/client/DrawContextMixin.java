package com.example.mixin.client;

import com.example.rankinglog.BodyCaptureManager;
import com.example.rankinglog.ModGatekeeper;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.text.Text;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(DrawContext.class)
public class DrawContextMixin {

    private static final String ALLOWED_ADDRESS  = "mcriders.64bit.kr";
    private static final String ALLOWED_ADDRESS1 = "kart-dev-server.kro.kr";

    /** "로딩중", "로딩중...", "로딩중....." 점 개수 달라도 감지 */
    private static boolean isLoadingText(String raw) {
        if (raw == null) return false;
        String s = raw.replace("\n", " ").trim();
        if (!s.startsWith("로딩중")) return false;

        String tail = s.substring("로딩중".length()).trim();
        if (tail.isEmpty()) return true;

        for (int i = 0; i < tail.length(); i++) {
            char c = tail.charAt(i);
            if (c != '.' && c != '·') return false;
        }
        return true;
    }

    private static boolean isAllowedServerFast() {
        MinecraftClient client = MinecraftClient.getInstance();

        if (client.getServer() != null) return false;

        ServerInfo info = client.getCurrentServerEntry();
        if (info == null || info.address == null) return false;

        String addr = info.address.trim().toLowerCase();
        String a0 = ALLOWED_ADDRESS.trim().toLowerCase();
        String a1 = ALLOWED_ADDRESS1.trim().toLowerCase();

        return addr.equals(a0) || addr.equals(a1);
    }

    private static void triggerLoadingCapture(String text) {
        if (!isLoadingText(text)) return;
        if (!isAllowedServerFast()) return;

        //쿨다운 제거: 호출될 때마다 그대로 트리거
        BodyCaptureManager.onLoadingDetected("custom_screen_text");
        try { ModGatekeeper.onLoadingTitle(); } catch (Throwable ignored) {}
    }

    //drawTextWithShadow(Text)
    @Inject(
            method = "drawTextWithShadow(Lnet/minecraft/client/font/TextRenderer;Lnet/minecraft/text/Text;III)I",
            at = @At("HEAD"),
            require = 0
    )
    private void onDrawTextWithShadow_Text(TextRenderer renderer, Text text, int x, int y, int color,
                                           CallbackInfoReturnable<Integer> cir) {
        if (text == null) return;
        triggerLoadingCapture(text.getString());
    }

    //drawTextWithShadow(String)
    @Inject(
            method = "drawTextWithShadow(Lnet/minecraft/client/font/TextRenderer;Ljava/lang/String;III)I",
            at = @At("HEAD"),
            require = 0
    )
    private void onDrawTextWithShadow_String(TextRenderer renderer, String text, int x, int y, int color,
                                             CallbackInfoReturnable<Integer> cir) {
        triggerLoadingCapture(text);
    }

    //drawText(Text)
    @Inject(
            method = "drawText(Lnet/minecraft/client/font/TextRenderer;Lnet/minecraft/text/Text;IIIZ)I",
            at = @At("HEAD"),
            require = 0
    )
    private void onDrawText_Text(TextRenderer renderer, Text text, int x, int y, int color, boolean shadow,
                                 CallbackInfoReturnable<Integer> cir) {
        if (text == null) return;
        triggerLoadingCapture(text.getString());
    }

    //drawText(String)
    @Inject(
            method = "drawText(Lnet/minecraft/client/font/TextRenderer;Ljava/lang/String;IIIZ)I",
            at = @At("HEAD"),
            require = 0
    )
    private void onDrawText_String(TextRenderer renderer, String text, int x, int y, int color, boolean shadow,
                                   CallbackInfoReturnable<Integer> cir) {
        triggerLoadingCapture(text);
    }

    //drawCenteredText(Text)
    @Inject(
            method = "drawCenteredText(Lnet/minecraft/client/font/TextRenderer;Lnet/minecraft/text/Text;III)V",
            at = @At("HEAD"),
            require = 0
    )
    private void onDrawCenteredText_Text(TextRenderer renderer, Text text, int x, int y, int color,
                                         CallbackInfo ci) {
        if (text == null) return;
        triggerLoadingCapture(text.getString());
    }

    //drawCenteredText(String)
    @Inject(
            method = "drawCenteredText(Lnet/minecraft/client/font/TextRenderer;Ljava/lang/String;III)V",
            at = @At("HEAD"),
            require = 0
    )
    private void onDrawCenteredText_String(TextRenderer renderer, String text, int x, int y, int color,
                                           CallbackInfo ci) {
        triggerLoadingCapture(text);
    }
}