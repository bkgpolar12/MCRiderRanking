package com.example.rankinglog;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.minecraft.client.MinecraftClient;

/**
 * 클라이언트 전용 커맨드 등록.
 *
 * 사용법 — ClientModInitializer의 onInitializeClient()에 한 줄 추가:
 *   RankingLogClientCommands.register();
 *
 * 등록되는 커맨드:
 *   /rankinglog_open <트랙명>  →  해당 트랙의 RankingScreen을 연다.
 *                                AutoSubmitter의 채팅 클릭 이벤트에서 호출된다.
 */
public final class RankingLogClientCommands {

    private RankingLogClientCommands() {}

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(
                        ClientCommandManager.literal("rankinglog_open")
                                .then(ClientCommandManager.argument("track", StringArgumentType.greedyString())
                                        .executes(ctx -> {
                                            String track = StringArgumentType.getString(ctx, "track");
                                            MinecraftClient client = MinecraftClient.getInstance();
                                            if (client != null) {
                                                client.execute(() -> client.setScreen(new RankingScreen(track)));
                                            }
                                            return 1;
                                        })
                                )
                )
        );
    }
}