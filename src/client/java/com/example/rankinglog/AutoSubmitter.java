package com.example.rankinglog;

import com.google.gson.JsonObject;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.util.Formatting;
import net.minecraft.util.Util;
import net.fabricmc.loader.api.FabricLoader;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

public class AutoSubmitter {

    private static final AtomicLong lastSubmitAt = new AtomicLong(0);
    private static final long MIN_INTERVAL_MS = 1500;

    // ★ 랩타임 감지 및 상태 동기화용 전역 변수
    public static boolean multiPlayerSubmitArmed = false;
    public static final List<String> lapTimes = new ArrayList<>();

    public static String getModVersion() {
        return FabricLoader.getInstance()
                .getModContainer("rankinglog")
                .map(container -> container.getMetadata().getVersion().getFriendlyString())
                .orElse("UNKNOWN");
    }

    // ★ lapsCsv 파라미터 추가
    public static void submitAsync(String player, String track, String timeStr, long timeMillis,
                                   int engine, String engineName, String bodyName,
                                   String tireName,
                                   String modesCsv, String kartSpecDebug, String lapsCsv) {

        long now = System.currentTimeMillis();
        long prev = lastSubmitAt.get();
        if (now - prev < MIN_INTERVAL_MS) return;
        lastSubmitAt.set(now);

        String bodyColor = BodyCaptureManager.getCachedKartColorOrHex();

        var session = MinecraftClient.getInstance().getSession();
        String uuid = String.valueOf(session.getUuidOrNull());
        String token = session.getAccessToken();

        String currentVersion = getModVersion();
        String serverAddress = CurrentServerHolder.get();

        CompletableFuture
                .supplyAsync(() -> {
                    return AddRankingScreen.submitRecord(player, track, timeStr, timeMillis, engineName, bodyName, bodyColor, tireName, modesCsv, kartSpecDebug, lapsCsv, uuid, token, currentVersion, serverAddress);
                }, Util.getIoWorkerExecutor())
                .exceptionally(ex -> {
                    ex.printStackTrace();
                    JsonObject fail = new JsonObject();
                    fail.addProperty("ok", false);
                    fail.addProperty("error", "network exception");
                    return fail;
                })
                .thenAccept(res -> {
                    MinecraftClient client = MinecraftClient.getInstance();
                    client.execute(() -> {
                        if (client.player == null) return;

                        boolean ok = res != null && res.has("ok") && res.get("ok").getAsBoolean();
                        if (ok) {
                            boolean isGeneral = !res.has("generalUpdated") || res.get("generalUpdated").getAsBoolean();
                            boolean isEvent = res.has("eventUpdated") && res.get("eventUpdated").getAsBoolean();

                            String toastTitle = track + " (" + timeStr + ")";

                            // 단축 시간 텍스트 생성 로직
                            String diffStr = "";
                            if (res.has("previousBestTime") && !res.get("previousBestTime").isJsonNull()) {
                                long prevTime = res.get("previousBestTime").getAsLong();
                                if (prevTime > timeMillis) {
                                    long diff = prevTime - timeMillis;
                                    long min = diff / 60000;
                                    long sec = (diff / 1000) % 60;
                                    long ms = diff % 1000;
                                    diffStr = String.format(" (-%02d:%02d.%03d)", min, sec, ms);
                                }
                            } else {
                                diffStr = " §b(첫 기록)§a"; // 첫 기록일 경우 안내
                            }

                            if (isGeneral) {
                                client.getToastManager().add(new RacingAchievementToast(
                                        toastTitle, "기록이 등록되었습니다.", "#55FF55", RacingAchievementToast.Type.SUCCESS
                                ));

                                Text hoverText = Text.literal("§b[ 등록된 기록 정보 ]\n")
                                        .append("§7트랙: §f" + track + "\n")
                                        .append("§7기록: §f" + timeStr + "\n")
                                        .append("§7카트: §f" + bodyName + "\n")
                                        .append("§7엔진: §f" + engineName);

                                // 성공 메시지에 단축 시간(diffStr) 포함
                                Text message = Text.literal("§a[MCRiderRanking] 기록 등록 성공!" + diffStr + " ")
                                        .append(Text.literal("§e§n[클릭해서 랭킹 확인]").styled(style -> style
                                                .withClickEvent(new ClickEvent.RunCommand("/rankinglog_open " + track))
                                                .withHoverEvent(new HoverEvent.ShowText(hoverText))))
                                        .append(Text.literal(" §a(또는 "))
                                        .append(Text.keybind("key.rankinglog.open_ranking").formatted(Formatting.YELLOW))
                                        .append(Text.literal("§a키)"));
                                client.player.sendMessage(message, false);
                            }

                            if (isEvent) {
                                client.getToastManager().add(new RacingAchievementToast(
                                        toastTitle, "이벤트 랭킹에 기록되었습니다!", "#FF55FF", RacingAchievementToast.Type.EVENT_SUCCESS
                                ));

                                Text eventMsg = Text.literal("§d§l[MCRiderRanking] 🎉 " + track + " 이벤트 랭킹 갱신 성공!");
                                client.player.sendMessage(eventMsg, false);
                            }

                            RacingAchievementToast.showNotifications(res);

                            if (res.has("achievementUnlocked") && res.get("achievementUnlocked").getAsBoolean()) {
                                client.player.sendMessage(
                                        Text.literal("§e[MCRiderRanking] 트랙 업적 달성! [")
                                                .append(Text.keybind("key.rankinglog.open_ranking").formatted(Formatting.YELLOW))
                                                .append(Text.literal("§e]키를 눌러 프로필에서 확인하세요")),
                                        false
                                );
                            }
                        } else {
                            String err = (res != null && res.has("error")) ? res.get("error").getAsString() : "unknown";

                            if ("not better".equals(err)) {
                                RacingAchievementToast.showNotifications(res);

                                client.player.sendMessage(
                                        Text.literal("§c[MCRiderRanking] 기존 최고 기록보다 느려 갱신되지 않았습니다."),
                                        false
                                );
                            } else if ("OUTDATED_VERSION".equals(err)) {
                                String msg = res.has("message") ? res.get("message").getAsString() : "버전이 낮습니다.";
                                client.player.sendMessage(Text.literal("§c[MCRiderRanking] " + msg), false);

                            } else if ("UNREGISTERED_SERVER".equals(err)) {
                                String msg = res.has("message") ? res.get("message").getAsString() : "등록되지 않은 서버입니다.";
                                client.player.sendMessage(Text.literal("§c[MCRiderRanking] " + msg), false);

                            } else if ("SINGLE_RECORD_DISABLED".equals(err)) {
                                String msg = res.has("message") ? res.get("message").getAsString() : "현재 싱글플레이에서 기록 등록 서비스를 이용하실 수 없습니다.";
                                client.player.sendMessage(Text.literal("§c[MCRiderRanking] " + msg), false);

                            } else if ("BAD JSON RESPONSE".equalsIgnoreCase(err)) {
                                client.player.sendMessage(Text.literal("§c[MCRiderRanking] 서버 응답 오류. 모드 버전을 확인하세요."), false);

                            } else {
                                client.player.sendMessage(Text.literal("§c[MCRiderRanking] 등록 실패: " + err), false);
                            }
                        }
                    });
                });
    }
}