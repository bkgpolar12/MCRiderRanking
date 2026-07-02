package com.example.rankinglog;

import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

public final class DebugLog {
    private DebugLog() {}

    public static boolean enabled() {
        return ModConfig.get().debugLogEnabled;
    }

    public static void chat(String msg) {
        if (!enabled()) return;

        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.player == null) return;

        client.player.sendMessage(Text.literal(msg), false);
    }
}
