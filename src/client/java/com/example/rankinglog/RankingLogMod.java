package com.example.rankinglog;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;

public class RankingLogMod implements ClientModInitializer {

    private static KeyBinding OPEN_UI_KEY;

    // ★ 테스트 중 무한으로 튜토리얼이 열리는 것을 막기 위한 1회용 변수
    private static boolean tutorialShownThisSession = false;

    @Override
    public void onInitializeClient() {
        // 기존 초기화 코드들
        ClientKeybinds.init();
        ModGatekeeper.init();
        RankingLogClientCommands.register();

        // ★ 월드 접속 감지 이벤트 등록 (튜토리얼 자동 팝업)
//        ClientTickEvents.END_CLIENT_TICK.register(client -> {
//            // 플레이어가 월드에 완전히 접속했고, 현재 열려있는 화면(채팅, 인벤토리 등)이 없을 때
//            if (client.player != null && client.world != null && client.currentScreen == null) {
//
//                // 튜토리얼을 아직 안 봤고, 이번 게임을 켠 이후로 띄운 적이 없다면
//                if (!ModConfig.get().hasSeenTutorial && !tutorialShownThisSession) {
//                    tutorialShownThisSession = true; // 이번 세션에서는 다시 안 띄움
//
//                    // 튜토리얼 화면 열기 (월드 진입 직후이므로 부모 화면은 null)
//                    client.setScreen(new TutorialScreen(null));
//                }
//            }
//        });

        System.out.println("[RankingLog] Client initialized");
    }
}