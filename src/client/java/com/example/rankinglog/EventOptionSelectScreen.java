package com.example.rankinglog;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.Drawable;
import net.minecraft.client.gui.Element;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public class EventOptionSelectScreen extends Screen {

    private static List<EventEntry> cachedEvents = null;
    private static long cachedAt = 0;

    private static boolean isCacheValid() {
        if (cachedEvents == null) return false;
        return System.currentTimeMillis() - cachedAt <= ModConfig.get().getCacheTtlMs();
    }

    public static void clearCache() {
        cachedEvents = null;
    }

    private final Screen parent;
    private final List<EventEntry> events = new ArrayList<>();
    private static final Set<String> ALLOWED_PLAYERS = Set.of("BKGpolar1");
    private boolean loading = true;

    private int cardScroll = 0;

    private static final int OUTER_PAD = 15;
    private static final int CARD_H = 55;
    private static final int START_Y = 48;

    private SharedSidebar sharedSidebar;
    private ButtonWidget refreshBtn;
    private ButtonWidget backBtn;

    public record EventEntry(String name, String startDate, String endDate, String track,
                             String mode, String kart, String engine, String tire,
                             String sheetTitle, String eventID, String visible) {}

    public EventOptionSelectScreen(Screen parent) {
        super(Text.literal("이벤트 선택"));
        this.parent = parent;
    }

    private boolean isNewUi() {
        try { return !ModConfig.get().useLegacyUi; }
        catch (Exception e) { return false; }
    }

    private int getEffectiveSidebarWidth() {
        if (!isNewUi() || sharedSidebar == null) return 0;
        int sw = sharedSidebar.getCurrentWidth(this.width);
        return sw == 0 ? 20 : sw;
    }

    @Override
    protected void init() {
        if (isNewUi()) {
            sharedSidebar = new SharedSidebar(
                    this, "EVENT", null, false,
                    catId -> { if (client != null) client.setScreen(new TmiRankingScreen(new MainMenuScreen(), catId)); },
                    () -> { loading = true; cachedEvents = null; events.clear(); cardScroll = 0; fetchEvents(); },
                    this::rebuildUI
            );
        }

        if (isCacheValid()) {
            events.clear(); events.addAll(cachedEvents); loading = false; cardScroll = 0;
        } else {
            loading = true; cardScroll = 0; fetchEvents();
        }
        rebuildUI();
    }

    private void rebuildUI() {
        clearChildren();
        int y = height - 30;
        int iconBtnSize = 20, margin = 12, gap = 5;

        int effectiveLeftW = getEffectiveSidebarWidth();
        int rightAreaX = effectiveLeftW == 0 ? OUTER_PAD : OUTER_PAD + effectiveLeftW + 8;
        int rightAreaW = this.width - OUTER_PAD - rightAreaX;

        refreshBtn = addDrawableChild(ButtonWidget.builder(Text.literal("🔄"), b -> {
            loading = true; cachedEvents = null; events.clear(); cardScroll = 0; fetchEvents();
        }).dimensions(rightAreaX + rightAreaW - iconBtnSize - margin, y, iconBtnSize, 20).tooltip(net.minecraft.client.gui.tooltip.Tooltip.of(Text.literal("새로 고침"))).build());

        backBtn = addDrawableChild(ButtonWidget.builder(Text.literal("⏴"), b -> {
                    playUiClick();
                    if (ModConfig.get().showMainScreen){
                        Objects.requireNonNull(client).setScreen(parent);
                    }
                    else {
                        close();
                    }
                })
                .dimensions(rightAreaX + rightAreaW - (iconBtnSize * 2) - margin - gap, y, iconBtnSize, 20).tooltip(net.minecraft.client.gui.tooltip.Tooltip.of(Text.literal("뒤로 가기"))).build());
    }

    @Override
    public void close() { if (this.client != null) this.client.setScreen(null); }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (isNewUi() && sharedSidebar != null && sharedSidebar.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount)) {
            return true;
        }

        if (!loading && !events.isEmpty()) {
            int listAreaH = this.height - 80;
            int cardsPerPage = Math.max(1, listAreaH / (CARD_H + 8));
            int maxScroll = Math.max(0, events.size() - cardsPerPage);

            if (maxScroll > 0) {
                cardScroll = Math.max(0, Math.min(cardScroll - (int)Math.signum(verticalAmount) * 1, maxScroll));
                return true;
            }
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (isNewUi() && sharedSidebar != null && sharedSidebar.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }

        if (!loading && !events.isEmpty()) {
            int effectiveLeftW = getEffectiveSidebarWidth();
            int rightAreaX = effectiveLeftW == 0 ? OUTER_PAD : OUTER_PAD + effectiveLeftW + 8;
            int rightAreaW = this.width - OUTER_PAD - rightAreaX;
            int cx = rightAreaX + rightAreaW / 2;
            int cardW = Math.min(rightAreaW - 40, 600);
            int left = cx - cardW / 2;
            int right = left + cardW;

            int listAreaH = this.height - 80;
            int cardsPerPage = Math.max(1, listAreaH / (CARD_H + 8));

            int maxScroll = Math.max(0, events.size() - cardsPerPage);
            int start = Math.max(0, Math.min(cardScroll, maxScroll));
            int end = Math.min(start + cardsPerPage, events.size());

            for (int i = start; i < end; i++) {
                int y = START_Y + (i - start) * (CARD_H + 8);
                if (mouseX >= left && mouseX <= right && mouseY >= y && mouseY <= y + CARD_H) {
                    playUiClick();
                    if (client != null) { client.setScreen(new EventRankingScreen(this, events.get(i))); return true; }
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private void playUiClick() {
        if (this.client != null) {
            this.client.getSoundManager().play(net.minecraft.client.sound.PositionedSoundInstance.master(net.minecraft.sound.SoundEvents.UI_BUTTON_CLICK, 1.0f));
        }
    }

    private void fetchEvents() {
        new Thread(() -> {
            List<EventEntry> fetchedEvents = new ArrayList<>();
            try {
                JsonObject obj = RankingScreen.Net.postJson(RankingScreen.SUPABASE_RPC_URL + "get_event_list", "{}");

                if (obj.get("ok").getAsBoolean()) {
                    JsonArray arr = obj.getAsJsonArray("events");
                    MinecraftClient client = MinecraftClient.getInstance();
                    String playerName = (client.player != null) ? client.player.getName().getString() : "";
                    boolean isDev = ALLOWED_PLAYERS.contains(playerName);

                    for (int i = 0; i < arr.size(); i++) {
                        JsonObject o = arr.get(i).getAsJsonObject();
                        String visibleValue = o.has("visible") ? o.get("visible").getAsString().trim() : "";
                        boolean shouldShow = true;

                        if ("false-dev".equalsIgnoreCase(visibleValue)) { if (isDev) shouldShow = false; }
                        else if ("true-dev".equalsIgnoreCase(visibleValue)) { if (!isDev) shouldShow = false; }

                        if (shouldShow) {
                            fetchedEvents.add(new EventEntry(
                                    o.get("eventName").getAsString(), o.get("startDate").getAsString(), o.get("endDate").getAsString(),
                                    o.get("track").getAsString(), o.get("mode").getAsString(), o.get("kart").getAsString(),
                                    o.get("engine").getAsString(), o.get("tire").getAsString(), o.get("sheetTitle").getAsString(),
                                    o.get("eventID").getAsString(), visibleValue
                            ));
                        }
                    }
                }
            } catch (Exception e) { e.printStackTrace(); }

            if (this.client != null) {
                this.client.execute(() -> {
                    events.clear();
                    events.addAll(fetchedEvents);
                    cachedEvents = new ArrayList<>(events);
                    cachedAt = System.currentTimeMillis();
                    loading = false;
                    rebuildUI();
                });
            }
        }).start();
    }

    private void drawRectBorder(DrawContext c, int x, int y, int w, int h, int color) {
        c.fill(x, y, x + w, y + 1, color);
        c.fill(x, y + h - 1, x + w, y + h, color);
        c.fill(x, y, x + 1, y + h, color);
        c.fill(x + w - 1, y, x + w, y + h, color);
    }

    private String trimWithEllipsis(String text, int maxWidth) {
        if (maxWidth <= 0) return "";
        if (textRenderer.getWidth(text) <= maxWidth) return text;
        int ellipsisWidth = textRenderer.getWidth("...");
        if (maxWidth <= ellipsisWidth) return "";
        return textRenderer.trimToWidth(Text.literal(text), maxWidth - ellipsisWidth).getString() + "...";
    }

    // ★ RankingScreen과 100% 동일한 백그라운드 렌더링 및 일시정지 방지 로직
    @Override
    public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
        context.fill(0, 0, this.width, this.height, ModConfig.get().getBgColor());
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackground(context, mouseX, mouseY, delta);

        if (isNewUi() && sharedSidebar != null) {
            sharedSidebar.render(context, mouseX, mouseY, delta);
        }

        int effectiveLeftW = getEffectiveSidebarWidth();
        int rightAreaX = effectiveLeftW == 0 ? OUTER_PAD : OUTER_PAD + effectiveLeftW + 8;
        int rightAreaW = this.width - OUTER_PAD - rightAreaX;
        int centerX = rightAreaX + rightAreaW / 2;
        int cardW = Math.min(rightAreaW - 40, 600);
        int left = centerX - cardW / 2;
        int right = left + cardW;

        context.fill(rightAreaX, 8, rightAreaX + rightAreaW, 28, 0xCC000000);
        drawRectBorder(context, rightAreaX, 8, rightAreaW, 20, 0xFF2A2A2A);

        float scale = Math.max(0.85f, Math.min(1.0f, rightAreaW / 450.0f));

        context.getMatrices().push();
        context.getMatrices().scale(scale, scale, 1.0f);
        String title = "현재 진행 중인 이벤트";
        context.drawText(textRenderer, title, (int)(centerX / scale) - textRenderer.getWidth(title) / 2, (int)(13 / scale), 0xFFFFAA00, false);
        context.getMatrices().pop();

        int listAreaTop = 40;
        int listAreaH = this.height - 80;

        context.fill(rightAreaX, listAreaTop, rightAreaX + rightAreaW, listAreaTop + listAreaH, 0x66000000);
        drawRectBorder(context, rightAreaX, listAreaTop, rightAreaW, listAreaH, 0xFF222222);

        if (loading) {
            String msg = "데이터 로딩 중...";
            context.getMatrices().push();
            context.getMatrices().scale(scale, scale, 1.0f);
            context.drawText(textRenderer, msg, (int)(centerX / scale) - textRenderer.getWidth(msg) / 2, (int)((this.height / 2) / scale), 0xFFFFFFFF, false);
            context.getMatrices().pop();
        } else if (events.isEmpty()) {
            String msg = "진행 중인 이벤트가 없습니다.";
            context.getMatrices().push();
            context.getMatrices().scale(scale, scale, 1.0f);
            context.drawText(textRenderer, msg, (int)(centerX / scale) - textRenderer.getWidth(msg) / 2, (int)((this.height / 2) / scale), 0xAAAAAA, false);
            context.getMatrices().pop();
        } else {
            int cardsPerPage = Math.max(1, listAreaH / (CARD_H + 8));

            int maxScroll = Math.max(0, events.size() - cardsPerPage);
            cardScroll = Math.max(0, Math.min(cardScroll, maxScroll));

            int start = cardScroll;
            int end = Math.min(start + cardsPerPage, events.size());

            for (int i = start; i < end; i++) {
                EventEntry e = events.get(i);
                int y = START_Y + (i - start) * (CARD_H + 8);
                boolean hovered = mouseX >= left && mouseX <= right && mouseY >= y && mouseY <= y + CARD_H;
                int boxColor = hovered ? 0x88444444 : 0x66222222;
                int borderColor = hovered ? 0xFFFFFFFF : 0xFF666666;

                context.fill(left, y, right, y + CARD_H, boxColor);
                drawRectBorder(context, left, y, cardW, CARD_H, borderColor);
                context.fill(left + 5, y + 17, right - 5, y + 18, 0xFF333333);

                context.getMatrices().push();
                context.getMatrices().scale(scale, scale, 1.0f);

                int sX = (int)((left + 8) / scale);
                int sY = (int)((y + 6) / scale);
                int sRightEdge = (int)((right - 8) / scale);

                String period = e.startDate + " ~ " + e.endDate;
                context.drawText(textRenderer, period, sX, sY, 0xFFAAAAAA, false);

                int periodW = textRenderer.getWidth(period);
                int maxNameW = Math.max(0, sRightEdge - (sX + periodW + 10));

                context.drawText(textRenderer, trimWithEllipsis(e.name, maxNameW), sX + periodW + 10, sY, 0xFFFFFF00, false);

                int sInfoY = (int)((y + 24) / scale);
                int lh = (int)(11 / scale);

                String trackText = "트랙: " + e.track;
                String modeText = "모드: " + formatVal(e.mode);
                String tireText = "타이어: " + formatVal(e.tire);
                String kartText = "카트: " + formatVal(e.kart);
                String engText = "엔진: " + formatVal(e.engine);

                int modeW = textRenderer.getWidth(modeText);
                int tireW = textRenderer.getWidth(tireText);
                int maxRightColW = Math.max(modeW, tireW);

                context.drawText(textRenderer, modeText, sRightEdge - modeW, sInfoY + lh, 0xFFFFFFFF, false);
                context.drawText(textRenderer, tireText, sRightEdge - tireW, sInfoY + lh * 2, 0xFFFFFFFF, false);

                int maxLeftW = Math.max(0, (sRightEdge - sX) - maxRightColW - 15);

                context.drawText(textRenderer, trimWithEllipsis(trackText, sRightEdge - sX), sX, sInfoY, 0xFFFFFFFF, false);
                context.drawText(textRenderer, trimWithEllipsis(kartText, maxLeftW), sX, sInfoY + lh, 0xFFFFFFFF, false);
                context.drawText(textRenderer, trimWithEllipsis(engText, maxLeftW), sX, sInfoY + lh * 2, 0xFFFFFFFF, false);

                context.getMatrices().pop();
            }

            if (maxScroll > 0) {
                int barW = 6;
                int barX = rightAreaX + rightAreaW - barW - 4;
                int barY = listAreaTop + 8;
                int barH = listAreaH - 16;

                context.fill(barX, barY, barX + barW, barY + barH, 0x55000000);

                int thumbH = Math.max(15, (int) (barH * ((float) cardsPerPage / events.size())));
                int thumbY = barY + (int) ((barH - thumbH) * ((float) cardScroll / maxScroll));

                context.fill(barX, thumbY, barX + barW, thumbY + thumbH, 0xFF888888);
            }
        }

        // ★ super.render 대신 children 순회 방식을 사용하여 이벤트 카드가 어두워지는 현상 방지
        for (Element element : this.children()) {
            if (element instanceof Drawable drawable) drawable.render(context, mouseX, mouseY, delta);
        }
    }

    private String formatVal(String val) {
        if ("ALL".equalsIgnoreCase(val)) return "제한없음";
        if ("uniq[X]".equalsIgnoreCase(val)) return "유니크 금지";
        if ("spe[X]".equalsIgnoreCase(val)) return "스페셜 금지";
        if ("instant_boost[X]".equalsIgnoreCase(val)) return "순간 부스터 금지";
        return val;
    }
}