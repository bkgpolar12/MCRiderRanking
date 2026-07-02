package com.example.rankinglog;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.toast.Toast;
import net.minecraft.client.toast.ToastManager;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

public class RacingAchievementToast implements Toast {

    public enum Type {
        UNLOCK, PROGRESS, SUCCESS, EVENT_SUCCESS // ★ 이벤트 전용 타입 추가
    }

    private final String title;
    private final String description;
    private final int color;
    private final Type type;

    private final int current;
    private final int target;
    private final int targetMs;
    private final int targetCount;

    private Toast.Visibility visibility = Toast.Visibility.SHOW;
    private long startTime;

    public RacingAchievementToast(String title, String description, String hexColor, Type type) {
        this(title, description, hexColor, type, 0, 0, 0, 0);
    }

    public RacingAchievementToast(String title, String description, String hexColor, Type type,
                                  int current, int target, int targetMs, int targetCount) {
        this.title = title;
        this.description = description;
        this.color = parseHex(hexColor);
        this.type = type;
        this.current = current;
        this.target = target;
        this.targetMs = targetMs;
        this.targetCount = targetCount;
    }

    @Override
    public int getWidth() {
        return 220;
    }

    @Override
    public int getHeight() {
        return 46;
    }

    @Override
    public Toast.Visibility getVisibility() {
        return this.visibility;
    }

    @Override
    public void update(ToastManager manager, long time) {
        if (this.startTime == 0L) {
            this.startTime = net.minecraft.util.Util.getMeasuringTimeMs();
        }
        if (net.minecraft.util.Util.getMeasuringTimeMs() - this.startTime >= 5000L) {
            this.visibility = Toast.Visibility.HIDE;
        }
    }

    @Override
    public void draw(DrawContext context, TextRenderer textRenderer, long unusedTime) {
        int width = getWidth();
        int height = getHeight();

        MinecraftClient client = MinecraftClient.getInstance();
        int screenWidth = client.getWindow().getScaledWidth();
        int screenHeight = client.getWindow().getScaledHeight();

        int pos = ModConfig.get().toastPosition;

        org.joml.Matrix4f matrix = context.getMatrices().peek().getPositionMatrix();
        float vanillaX = matrix.m30();
        float vanillaY = matrix.m31();

        long elapsed = net.minecraft.util.Util.getMeasuringTimeMs() - this.startTime;
        if (this.startTime == 0) elapsed = 0;

        float anim = 1.0f;
        if (elapsed < 500) {
            anim = elapsed / 500.0f;
        } else if (elapsed > 4500) {
            anim = Math.max(0.0f, (5000 - elapsed) / 500.0f);
        }
        anim = (float) Math.sin(anim * Math.PI / 2.0);

        float offsetX = 0;
        if (pos == 0 || pos == 2) {
            offsetX = screenWidth - width - 5 + ((width + 5) * (1.0f - anim));
        } else {
            offsetX = 5 - ((width + 5) * (1.0f - anim));
        }

        float offsetY = 0;
        if (pos == 0 || pos == 1) {
            offsetY = vanillaY + 5;
        } else {
            offsetY = screenHeight - vanillaY - height - 5;
        }

        context.getMatrices().push();
        context.getMatrices().translate(-vanillaX, -vanillaY, 0);
        context.getMatrices().translate(offsetX, offsetY, 0);

        context.fill(0, 0, width, height, 0xEE0B0B0B);

        // ★ 테두리, 아이콘, 텍스트 컬러 디자인 (EVENT_SUCCESS 처리)
        int borderColor = (type == Type.UNLOCK) ? 0xFFFFD700 : ((type == Type.SUCCESS) ? 0xFF00E5FF : ((type == Type.EVENT_SUCCESS) ? 0xFFFF55FF : 0xFFFF8800));
        drawRectBorder(context, 0, 0, width, height, borderColor);

        String icon = (type == Type.UNLOCK) ? "🏆" : ((type == Type.SUCCESS) ? "⏱" : ((type == Type.EVENT_SUCCESS) ? "🎉" : "🎯"));
        int iconColor = (type == Type.UNLOCK) ? 0xFFFFD700 : ((type == Type.SUCCESS) ? 0xFF55FFFF : ((type == Type.EVENT_SUCCESS) ? 0xFFFF55FF : 0xFFFFAA00));
        context.drawTextWithShadow(textRenderer, icon, 10, 17, iconColor);

        int textX = 28;

        if (type == Type.UNLOCK) {
            context.drawText(textRenderer, Text.literal("업적 달성!").formatted(Formatting.GOLD, Formatting.BOLD), textX, 6, -1, false);
            context.drawText(textRenderer, Text.literal(title), textX, 19, color, false);

            String completeDetail = "도전 과제 클리어";
            if (targetMs > 0) completeDetail = "목표 기록 달성: " + formatMillis(targetMs);
            else if (targetCount > 0) completeDetail = "목표 누적 횟수 달성: " + targetCount + "회";
            context.drawText(textRenderer, Text.literal(completeDetail).formatted(Formatting.GRAY, Formatting.ITALIC), textX, 31, -1, false);

        } else if (type == Type.SUCCESS) {
            context.drawText(textRenderer, Text.literal("기록 등록 성공!").formatted(Formatting.AQUA, Formatting.BOLD), textX, 12, -1, false);
            context.drawText(textRenderer, Text.literal(title), textX, 26, color, false);

            // ★ 이벤트 전용 텍스트 및 서식 렌더링
        } else if (type == Type.EVENT_SUCCESS) {
            context.drawText(textRenderer, Text.literal("이벤트 랭킹 갱신!").formatted(Formatting.LIGHT_PURPLE, Formatting.BOLD), textX, 12, -1, false);
            context.drawText(textRenderer, Text.literal(title), textX, 26, color, false);

        } else if (type == Type.PROGRESS) {
            context.drawText(textRenderer, Text.literal("업적 진행도").formatted(Formatting.YELLOW, Formatting.BOLD), textX, 5, -1, false);
            context.drawText(textRenderer, Text.literal(title), textX, 17, color, false);

            int barX = textX; int barY = 36; int barW = width - barX - 10; int barH = 4;

            if (targetMs > 0) {
                long currentPb = current; float pct = 0.0f; String currentStr = "기록 없음";
                if (currentPb > 0 && currentPb != Long.MAX_VALUE) { pct = (currentPb <= targetMs) ? 1.0f : (float) targetMs / currentPb; currentStr = formatMillis(currentPb); }
                int pctInt = (int) (pct * 100); String pctText = pctInt + "%"; int pctW = textRenderer.getWidth(pctText);
                int fillColor = (pctInt >= 100) ? 0xFF55FF55 : 0xFFFFAA00;
                context.drawText(textRenderer, Text.literal(pctText).formatted(Formatting.LIGHT_PURPLE, Formatting.BOLD), width - 10 - pctW, 5, -1, false);

                String detailStr = currentStr + " (목표: " + formatMillis(targetMs) + ")";
                context.getMatrices().push(); context.getMatrices().scale(0.85f, 0.85f, 1.0f);
                context.drawText(textRenderer, Text.literal(detailStr).formatted(Formatting.GRAY), (int)(textX / 0.85f), (int)(26 / 0.85f), -1, false);
                context.getMatrices().pop();

                context.fill(barX, barY, barX + barW, barY + barH, 0xFF222222);
                if (pct > 0) context.fill(barX, barY, barX + (int) (barW * pct), barY + barH, fillColor);
                drawRectBorder(context, barX, barY, barW, barH, 0xFF555555);
            }
            else if (target > 0 || targetCount > 0) {
                int finalTarget = target > 0 ? target : targetCount; float pct = Math.min(1.0f, (float) current / finalTarget);
                int pctInt = (int) (pct * 100); String pctText = pctInt + "%"; int pctW = textRenderer.getWidth(pctText);
                int fillColor = (pctInt >= 100) ? 0xFF55FF55 : 0xFFFFAA00;
                context.drawText(textRenderer, Text.literal(pctText).formatted(Formatting.LIGHT_PURPLE, Formatting.BOLD), width - 10 - pctW, 5, -1, false);

                String countStr = current + " / " + finalTarget + "회"; int countW = textRenderer.getWidth(countStr);
                context.drawText(textRenderer, Text.literal(countStr).formatted(Formatting.GRAY), width - 10 - countW, 17, -1, false);

                context.fill(barX, barY, barX + barW, barY + barH, 0xFF222222);
                if (pct > 0) context.fill(barX, barY, barX + (int) (barW * pct), barY + barH, fillColor);
                drawRectBorder(context, barX, barY, barW, barH, 0xFF555555);
            }
            else {
                context.drawText(textRenderer, Text.literal(description).formatted(Formatting.GRAY, Formatting.ITALIC), textX, 29, -1, false);
            }
        }
        context.getMatrices().pop();
    }

    private void drawRectBorder(DrawContext c, int x, int y, int w, int h, int color) {
        c.fill(x, y, x + w, y + 1, color); c.fill(x, y + h - 1, x + w, y + h, color);
        c.fill(x, y, x + 1, y + h, color); c.fill(x + w - 1, y, x + w, y + h, color);
    }

    private int parseHex(String hex) {
        try { if (hex.startsWith("#")) hex = hex.substring(1); return Integer.parseInt(hex, 16) | 0xFF000000; }
        catch (Exception e) { return 0xFFFFFFFF; }
    }

    private String formatMillis(long ms) {
        long m = ms / 60000, s = (ms % 60000) / 1000, mm = ms % 1000;
        return String.format("%02d:%02d.%03d", m, s, mm);
    }

    public static void showNotifications(JsonObject response) {
        if (response == null || !ModConfig.get().showToasts) return;

        ToastManager toastManager = MinecraftClient.getInstance().getToastManager();

        if (response.has("newlyUnlocked")) {
            JsonArray newly = response.getAsJsonArray("newlyUnlocked");
            for (JsonElement e : newly) {
                JsonObject ach = e.getAsJsonObject();
                int tMs = ach.has("targetMs") ? ach.get("targetMs").getAsInt() : 0;
                int tCount = ach.has("targetCount") ? ach.get("targetCount").getAsInt() : 0;
                toastManager.add(new RacingAchievementToast(ach.get("name").getAsString(), "업적을 달성했습니다!", ach.get("color").getAsString(), Type.UNLOCK, 0, 0, tMs, tCount));
            }
        }

        if (response.has("progressUpdates")) {
            JsonArray updates = response.getAsJsonArray("progressUpdates");
            for (JsonElement e : updates) {
                JsonObject prog = e.getAsJsonObject();
                String progType = prog.get("type").getAsString();
                String progTitle = prog.get("name").getAsString();
                String hexColor = prog.has("color") ? prog.get("color").getAsString() : "#AAAAAA";

                int tMs = prog.has("targetMs") ? prog.get("targetMs").getAsInt() : 0;
                int tCount = prog.has("targetCount") ? prog.get("targetCount").getAsInt() : 0;
                int targetVal = prog.has("target") ? prog.get("target").getAsInt() : 0;
                int currentVal = prog.has("current") ? prog.get("current").getAsInt() : 0;

                if (targetVal > 1000) { if (tMs == 0) tMs = targetVal; }
                else if (targetVal > 0) { if (tCount == 0) tCount = targetVal; }

                boolean isRecordType = progType.equals("RECORD") || tMs > 1000;

                if (isRecordType) {
                    toastManager.add(new RacingAchievementToast(progTitle, "기록 도전 중", hexColor, Type.PROGRESS, currentVal, tMs, tMs, tCount));
                }
                else if (progType.equals("COUNT") || tCount > 0 || progType.equals("TRACK")) {
                    toastManager.add(new RacingAchievementToast(progTitle, "진행도 누적 중", hexColor, Type.PROGRESS, currentVal, tCount > 0 ? tCount : 1, tMs, tCount > 0 ? tCount : 1));
                } else {
                    toastManager.add(new RacingAchievementToast(progTitle, "목표 달성을 위해 도전 중!", hexColor, Type.PROGRESS, currentVal, targetVal, tMs, tCount));
                }
            }
        }
    }
}