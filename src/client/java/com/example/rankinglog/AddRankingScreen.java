package com.example.rankinglog;

import com.google.gson.JsonObject;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

public class AddRankingScreen extends Screen {

    private TextFieldWidget playerField;
    private TextFieldWidget trackField;
    private TextFieldWidget timeField;

    private final String player;
    private final String track;
    private final String time;

    private long lastSubmitTime = 0;

    public AddRankingScreen(String player, String track, String time) {
        super(Text.literal("기록 추가"));
        this.player = player;
        this.track = track;
        this.time = time;
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int cy = this.height / 2;

        playerField = new TextFieldWidget(textRenderer, cx - 100, cy - 50, 200, 20, Text.literal("플레이어"));
        playerField.setText(player);

        trackField = new TextFieldWidget(textRenderer, cx - 100, cy - 20, 200, 20, Text.literal("트랙"));
        trackField.setText(track);

        timeField = new TextFieldWidget(textRenderer, cx - 100, cy + 10, 200, 20, Text.literal("기록"));
        timeField.setText(time);

        addDrawableChild(playerField);
        addDrawableChild(trackField);
        addDrawableChild(timeField);

        addDrawableChild(
                ButtonWidget.builder(Text.literal("전송"), btn -> {

                    long now = System.currentTimeMillis();
                    if (now - lastSubmitTime < 5000) return;

                    String p = playerField.getText();
                    String tr = trackField.getText();
                    String t = timeField.getText();

                    long newTimeMillis = parseTimeToMillis(t);
                    if (newTimeMillis < 0) {
                        if (client != null && client.player != null) {
                            client.player.sendMessage(Text.literal("등록 실패 : 기록 형식이 올바르지 않습니다"), false);
                        }
                        return;
                    }

                    btn.active = false;
                    lastSubmitTime = now;

                    new Thread(() -> {
                        try {
                            String engineName = "[X]";
                            String bodyName = BodyCaptureManager.getCachedKartBodyNameOrUnknown();
                            String bodyColor = BodyCaptureManager.getCachedKartColorOrHex();
                            String modesCsv = "없음";
                            String tireName = "레이싱 타이어";

                            String kartSpecDebug = "없음";
                            // UI 모달 수동 전송 시 랩타임 기록은 없음
                            String lapsCsv = "";

                            var session = MinecraftClient.getInstance().getSession();
                            String uuid = String.valueOf(session.getUuidOrNull());
                            String token = session.getAccessToken();

                            String currentVersion = AutoSubmitter.getModVersion();
                            String serverAddress = CurrentServerHolder.get();

                            JsonObject submitRes = submitRecord(p, tr, t, newTimeMillis, engineName, bodyName, bodyColor, tireName, modesCsv, kartSpecDebug, lapsCsv, uuid, token, currentVersion, serverAddress);

                            boolean ok = submitRes.has("ok") && submitRes.get("ok").getAsBoolean();
                            if (!ok) {
                                String err = submitRes.has("error") ? submitRes.get("error").getAsString() : "unknown error";
                                if ("OUTDATED_VERSION".equals(err)) {
                                    err = submitRes.has("message") ? submitRes.get("message").getAsString() : "버전이 낮습니다.";
                                }

                                final String finalErr = err;
                                MinecraftClient.getInstance().execute(() -> {
                                    if (client != null && client.player != null) {
                                        client.player.sendMessage(Text.literal("등록 실패 : " + finalErr), false);
                                    }
                                    btn.active = true;
                                });
                                return;
                            }

                            MinecraftClient.getInstance().execute(() -> {
                                RacingAchievementToast.showNotifications(submitRes);
                                this.close();
                            });

                        } catch (Exception e) {
                            e.printStackTrace();
                            MinecraftClient.getInstance().execute(() -> {
                                if (client != null && client.player != null) {
                                    client.player.sendMessage(Text.literal("등록 실패 : 통신 오류"), false);
                                }
                                btn.active = true;
                            });
                        }
                    }, "ranking-submit-thread").start();

                }).dimensions(cx - 40, cy + 40, 80, 20).build()
        );

        setInitialFocus(playerField);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    public static long parseTimeToMillis(String time) {
        try {
            String[] minSplit = time.split(":");
            int minutes = Integer.parseInt(minSplit[0]);
            String[] secSplit = minSplit[1].split("\\.");
            int seconds = Integer.parseInt(secSplit[0]);
            int millis = Integer.parseInt(secSplit[1]);
            return minutes * 60_000L + seconds * 1_000L + millis;
        } catch (Exception e) {
            return -1;
        }
    }

    public static JsonObject submitRecord(String player, String track, String timeStr, long timeMillis,
                                          String engineName, String bodyName, String bodyColor, String tireName, String modesCsv,
                                          String kartSpecDebug, String lapsCsv,
                                          String uuid, String token, String version, String serverAddress) {

        JsonObject json = new JsonObject();
        json.addProperty("action", "submit_record");

        json.addProperty("p_player", player);
        json.addProperty("p_track", track);
        json.addProperty("p_time_millis", timeMillis);
        json.addProperty("p_time_str", timeStr);
        json.addProperty("p_engine_name", engineName);
        json.addProperty("p_body_name", bodyName);
        json.addProperty("p_body_color", bodyColor);
        json.addProperty("p_mode", modesCsv);
        json.addProperty("p_tire", tireName);
        json.addProperty("p_uuid", uuid);
        json.addProperty("p_token", token);
        json.addProperty("p_version", version);
        json.addProperty("p_kart_spec_debug", kartSpecDebug);
        json.addProperty("p_server_address", serverAddress != null ? serverAddress : CurrentServerHolder.get());

        // ★ Supabase에 보낼 랩타임 데이터
        json.addProperty("p_laps", lapsCsv);

        return callEdgeFunction(json);
    }

    // ★ 본인 기록 삭제 요청. 엣지 펑션이 모장 토큰으로 실제 닉네임을 검증한 뒤
    //   delete_racing_record_v1 RPC를 호출해 소유자가 맞는 기록만 삭제합니다.
    public static JsonObject deleteRecord(String player, String recordId, String uuid, String token) {
        JsonObject json = new JsonObject();
        json.addProperty("action", "delete_record");
        json.addProperty("p_player", player);
        json.addProperty("p_record_id", recordId);
        json.addProperty("p_uuid", uuid);
        json.addProperty("p_token", token);

        return callEdgeFunction(json);
    }

    // ★ submitRecord / deleteRecord가 공유하는 엣지 펑션 HTTP 호출 로직
    private static JsonObject callEdgeFunction(JsonObject json) {
        try {
            java.net.URI uri = java.net.URI.create("https://wmlcwmfabuziancpxdoq.supabase.co/functions/v1/very_secret_code_v4");
            java.net.HttpURLConnection con = (java.net.HttpURLConnection) uri.toURL().openConnection();
            con.setRequestMethod("POST");
            con.setRequestProperty("Content-Type", "application/json; charset=utf-8");

            con.setRequestProperty("apikey", RankingScreen.SUPABASE_KEY);
            con.setRequestProperty("Authorization", "Bearer " + RankingScreen.SUPABASE_KEY);
            con.setDoOutput(true);

            byte[] input = json.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            con.getOutputStream().write(input);

            int code = con.getResponseCode();
            java.io.InputStream is = (code >= 200 && code < 300) ? con.getInputStream() : con.getErrorStream();

            if (is == null) {
                JsonObject fail = new JsonObject();
                fail.addProperty("ok", false);
                fail.addProperty("error", "HTTP " + code + " (응답 없음)");
                return fail;
            }

            try (java.io.InputStreamReader reader = new java.io.InputStreamReader(is, java.nio.charset.StandardCharsets.UTF_8)) {
                com.google.gson.JsonElement el = com.google.gson.JsonParser.parseReader(reader);

                if (code >= 400) {
                    JsonObject fail = new JsonObject();
                    fail.addProperty("ok", false);

                    String errorMsg = el.toString();
                    if (el.isJsonObject() && el.getAsJsonObject().has("message")) {
                        errorMsg = el.getAsJsonObject().get("message").getAsString();
                    } else if (el.isJsonObject() && el.getAsJsonObject().has("error")) {
                        errorMsg = el.getAsJsonObject().get("error").getAsString();
                    }

                    fail.addProperty("error", "서버 거절(" + code + "): " + errorMsg);
                    return fail;
                }

                return el.getAsJsonObject();
            }
        } catch (Exception e) {
            JsonObject fail = new JsonObject();
            fail.addProperty("ok", false);
            fail.addProperty("error", "자바 에러: " + e.getMessage());
            return fail;
        }
    }
}