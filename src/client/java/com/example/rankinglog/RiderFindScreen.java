package com.example.rankinglog;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import java.util.*;
import com.google.gson.JsonObject;
import com.google.gson.JsonArray;

public class RiderFindScreen extends Screen {

    private final Screen parent;
    private TextFieldWidget input;
    // 전체 서버 검색 전용 — ApiCache와 별개로 관리
    private final List<String> allPlayersGlobal = new ArrayList<>();
    private final Map<String, String> playerRepMapGlobal = new HashMap<>();
    private boolean globalLoaded = false;

    private final List<String> allPlayers = new ArrayList<>();
    private final List<String> filtered = new ArrayList<>();
    private final Map<String, String> playerRepMap = new HashMap<>();

    // ★ 스크롤 관련 변수
    private int tableScroll = 0;
    private int rowsPerPage = 10;

    private boolean loading = false;
    private String error = null;
    private ButtonWidget backBtn, refreshBtn;
    private static final int OUTER_PAD = 12;

    private SharedSidebar sharedSidebar;

    public RiderFindScreen(Screen parent) { super(Text.literal("라이더 찾기")); this.parent = parent; }

    @Override public void close() { if (this.client != null) this.client.setScreen(null); }

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
                    this, "RIDER_FIND", null, false,
                    catId -> {
                        if (this.client != null) {
                            this.client.setScreen(new TmiRankingScreen(new MainMenuScreen(), catId));
                        }
                    },
                    () -> {
                        loading = true; error = null; updateButtons();
                        RankingScreen.ApiCache.fetchAllAsync(true, p -> { loading = false; error = null; rebuildPlayerList(); tableScroll = 0; applyFilter(); updateButtons(); }, err -> { loading = false; error = err; updateButtons(); });
                    },
                    this::repositionUiElements
            );
        }

        int iconBtnSize = 20;

        backBtn = ButtonWidget.builder(Text.literal("⏴"), b -> { playUiClick(); this.close(); }).dimensions(0, 0, iconBtnSize, 20).tooltip(net.minecraft.client.gui.tooltip.Tooltip.of(Text.literal("뒤로 가기"))).build(); addDrawableChild(backBtn);

        refreshBtn = ButtonWidget.builder(Text.literal("🔄"), b -> {
            loading = true; error = null; updateButtons();
            RankingScreen.ApiCache.fetchAllAsync(true, p -> { loading = false; error = null; rebuildPlayerList(); tableScroll = 0; applyFilter(); updateButtons(); }, err -> { loading = false; error = err; updateButtons(); });
            fetchGlobalPlayers();
        }).dimensions(0, 0, iconBtnSize, 20).tooltip(net.minecraft.client.gui.tooltip.Tooltip.of(Text.literal("새로 고침"))).build(); addDrawableChild(refreshBtn);

        input = new TextFieldWidget(this.textRenderer, 0, 42, 300, 20, Text.literal(""));
        input.setMaxLength(32); input.setChangedListener(s -> { tableScroll = 0; applyFilter(); updateButtons(); });
        addSelectableChild(input); setInitialFocus(input);

        repositionUiElements();
        rebuildPlayerList(); tableScroll = 0; applyFilter();
        // 전체 서버 플레이어 목록을 별도로 fetch
        fetchGlobalPlayers();
    }

    private void repositionUiElements() {
        int effectiveLeftW = getEffectiveSidebarWidth();
        int rightAreaX = effectiveLeftW == 0 ? OUTER_PAD : OUTER_PAD + effectiveLeftW + 8;
        int rightAreaW = this.width - OUTER_PAD - rightAreaX;
        int cx = rightAreaX + rightAreaW / 2;
        int bottomY = this.height - 28;
        int iconBtnSize = 20;

        if (backBtn != null) backBtn.setPosition(rightAreaX, bottomY);
        if (refreshBtn != null) refreshBtn.setPosition(rightAreaX + rightAreaW - iconBtnSize, bottomY);
        if (input != null) {
            input.setWidth(Math.min(300, rightAreaW - 40));
            input.setX(cx - input.getWidth() / 2);
        }
    }

    private void playUiClick() { if (this.client != null && this.client.getSoundManager() != null) this.client.getSoundManager().play(net.minecraft.client.sound.PositionedSoundInstance.master(net.minecraft.sound.SoundEvents.UI_BUTTON_CLICK, 1.0F)); }
    private void computeRowsPerPage() { rowsPerPage = Math.min(14, Math.max(1, Math.max(1, this.height - 46 - 80 - 18 - 18) / 18)); }

    private void rebuildPlayerList() {
        allPlayers.clear(); playerRepMap.clear();
        RankingScreen.ApiCache.AllPayload all = RankingScreen.ApiCache.getAllIfReady(); if (all == null) return;
        LinkedHashSet<String> uniq = new LinkedHashSet<>();
        for (var it : all.rankingsByTrack.entrySet()) {
            for (RankingScreen.Entry e : it.getValue().ranking) {
                String name = (e.player() == null) ? "" : e.player().trim();
                if (!name.isBlank()) { uniq.add(name); if (e.repTitle() != null && !e.repTitle().isEmpty()) playerRepMap.put(name, e.repTitle()); }
            }
        }
        allPlayers.addAll(uniq); allPlayers.sort(String.CASE_INSENSITIVE_ORDER);
    }

    /** 전체 서버를 대상으로 플레이어 목록을 fetch한다 (검색 전용) */
    private void fetchGlobalPlayers() {
        globalLoaded = false;
        new Thread(() -> {
            try {
                JsonObject req = new JsonObject();
                req.addProperty("p_server_address", "ALL");
                req.addProperty("p_search_player", ""); // 빈 문자열 + ALL = 전체 서버 모든 플레이어
                JsonObject res = RankingScreen.Net.postJson(
                        RankingScreen.SUPABASE_RPC_URL + "get_all_rankings_v2", req.toString());
                if (res.has("ok") && res.get("ok").getAsBoolean()) {
                    java.util.LinkedHashSet<String> uniq = new java.util.LinkedHashSet<>();
                    java.util.Map<String, String> repMap = new java.util.HashMap<>();
                    com.google.gson.JsonObject rankings = res.has("rankings")
                            ? res.getAsJsonObject("rankings") : new com.google.gson.JsonObject();
                    for (String track : rankings.keySet()) {
                        com.google.gson.JsonObject td = rankings.getAsJsonObject(track);
                        if (!td.has("ranking") || td.get("ranking").isJsonNull()) continue;
                        com.google.gson.JsonArray arr = td.getAsJsonArray("ranking");
                        for (int i = 0; i < arr.size(); i++) {
                            com.google.gson.JsonObject entry = arr.get(i).getAsJsonObject();
                            String name = entry.has("player") && !entry.get("player").isJsonNull()
                                    ? entry.get("player").getAsString().trim() : "";
                            if (name.isBlank()) continue;
                            uniq.add(name);
                            if (entry.has("repTitle") && !entry.get("repTitle").isJsonNull()) {
                                String rep = entry.get("repTitle").getAsString();
                                if (!rep.isEmpty()) repMap.put(name, rep);
                            }
                        }
                    }
                    List<String> sorted = new ArrayList<>(uniq);
                    sorted.sort(String.CASE_INSENSITIVE_ORDER);
                    if (this.client != null) this.client.execute(() -> {
                        allPlayersGlobal.clear(); allPlayersGlobal.addAll(sorted);
                        playerRepMapGlobal.clear(); playerRepMapGlobal.putAll(repMap);
                        globalLoaded = true;
                        // allPlayers를 글로벌 목록으로 교체 후 필터 재적용
                        allPlayers.clear(); allPlayers.addAll(allPlayersGlobal);
                        playerRepMap.clear(); playerRepMap.putAll(playerRepMapGlobal);
                        tableScroll = 0; applyFilter();
                    });
                }
            } catch (Exception ex) {
                ex.printStackTrace();
            }
        }, "RiderFind-GlobalFetch").start();
    }

    private void applyFilter() {
        computeRowsPerPage(); filtered.clear(); String q = (input == null) ? "" : input.getText().trim().toLowerCase(Locale.ROOT);
        for (String p : allPlayers) { if (q.isEmpty() || p.toLowerCase(Locale.ROOT).contains(q)) filtered.add(p); }
        int maxScroll = Math.max(0, filtered.size() - rowsPerPage); if (tableScroll > maxScroll) tableScroll = maxScroll; if (tableScroll < 0) tableScroll = 0;
    }

    private void updateButtons() { computeRowsPerPage(); }

    @Override protected void applyBlur() { }
    @Override public void blur() { }
    @Override
    public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
        context.fill(0, 0, this.width, this.height, ModConfig.get().getBgColor());
    }
    @Override public void resize(net.minecraft.client.MinecraftClient client, int width, int height) { super.resize(client, width, height); applyFilter(); updateButtons(); repositionUiElements(); }
    @Override public boolean charTyped(char chr, int modifiers) { if (input != null && input.charTyped(chr, modifiers)) return true; return super.charTyped(chr, modifiers); }
    @Override public boolean keyPressed(int keyCode, int scanCode, int modifiers) { if (input != null && input.keyPressed(keyCode, scanCode, modifiers)) return true; return super.keyPressed(keyCode, scanCode, modifiers); }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (isNewUi() && sharedSidebar != null && sharedSidebar.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount)) {
            return true;
        }

        if (!loading && error == null && !filtered.isEmpty()) {
            int effectiveLeftW = getEffectiveSidebarWidth();
            int rightAreaX = effectiveLeftW == 0 ? OUTER_PAD : OUTER_PAD + effectiveLeftW + 8;
            int rightAreaW = this.width - OUTER_PAD - rightAreaX;
            int tableX = rightAreaX + 8; int tableY = 80; int tableW = rightAreaW - 16; int tableH = this.height - 46 - tableY;

            if (mouseX >= tableX && mouseX <= tableX + tableW && mouseY >= tableY && mouseY <= tableY + tableH) {
                computeRowsPerPage();
                int maxScroll = Math.max(0, filtered.size() - rowsPerPage);
                tableScroll = Math.max(0, Math.min(tableScroll - (int)Math.signum(verticalAmount) * 3, maxScroll));
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

        if (input != null && input.mouseClicked(mouseX, mouseY, button)) return true;
        if (!loading && error == null && !filtered.isEmpty()) {
            int effectiveLeftW = getEffectiveSidebarWidth();
            int rightAreaX = effectiveLeftW == 0 ? OUTER_PAD : OUTER_PAD + effectiveLeftW + 8;
            int rightAreaW = this.width - OUTER_PAD - rightAreaX;
            int tableX = rightAreaX + 8; int tableY = 80; int tableW = rightAreaW - 16; int tableH = this.height - 46 - tableY;

            if (mouseX >= tableX && mouseX <= tableX + tableW && mouseY >= tableY && mouseY <= tableY + tableH) {
                int start = tableScroll; int end = Math.min(start + rowsPerPage, filtered.size()); int y = tableY + 24; int rowH = 18;
                for (int i = start; i < end; i++) {
                    String name = filtered.get(i); String repTitle = playerRepMap.getOrDefault(name, ""); String displayName = name;
                    if (!repTitle.isEmpty()) displayName += " §b[" + repTitle + "]§r";
                    int nameX = tableX + 16; int nameY = y; int nameW = this.textRenderer.getWidth(displayName);
                    if (mouseX >= nameX && mouseX <= nameX + nameW && mouseY >= nameY - 2 && mouseY <= nameY + 10) {
                        if (this.client != null) this.client.setScreen(new PlayerProfileScreen(name, this)); return true;
                    }
                    y += rowH;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
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
        int cx = rightAreaX + rightAreaW / 2;

        context.drawCenteredTextWithShadow(textRenderer, "라이더 찾기", cx, 18, 0xFFFFFF);
        context.drawCenteredTextWithShadow(textRenderer, "닉네임을 입력하세요", cx, 30, 0xBBBBBB);
        if (input != null) input.render(context, mouseX, mouseY, delta);

        int tableX = rightAreaX + 8; int tableY = 80; int tableW = rightAreaW - 16; int tableH = this.height - 46 - tableY;
        context.fill(tableX, tableY, tableX + tableW, tableY + tableH, 0x66000000); drawRectBorder(context, tableX, tableY, tableW, tableH, 0xFF222222);

        if (loading) { context.drawCenteredTextWithShadow(textRenderer, "불러오는 중...", cx, tableY + 26, 0xFFFFFF); super.render(context, mouseX, mouseY, delta); return; }
        if (error != null) { String displayError = error.toLowerCase().contains("http") ? "이 버전은 서비스 종료 되었습니다. 최신 버전을 이용해 주세요." : error; context.drawCenteredTextWithShadow(this.textRenderer, "오류: " + displayError, cx, tableY + 26, 0xFF5555); super.render(context, mouseX, mouseY, delta); return; }

        context.drawTextWithShadow(textRenderer, "닉네임 (클릭하면 프로필)", tableX + 16, tableY + 8, 0xDDDDDD);
        int start = tableScroll; int end = Math.min(start + rowsPerPage, filtered.size()); int y = tableY + 24; int rowH = 18;

        for (int i = start; i < end; i++) {
            String name = filtered.get(i); String repTitle = playerRepMap.getOrDefault(name, ""); String displayName = name;
            if (!repTitle.isEmpty()) displayName += " §b[" + repTitle + "]§r";
            boolean hover = mouseX >= tableX + 16 && mouseX <= tableX + 16 + textRenderer.getWidth(displayName) && mouseY >= y - 2 && mouseY <= y + 10;
            context.drawTextWithShadow(textRenderer, displayName, tableX + 16, y, hover ? 0xFFFFEE88 : 0xFFFFFF); y += rowH;
        }

        // 스크롤바 렌더링
        int maxScroll = Math.max(0, filtered.size() - rowsPerPage);
        if (maxScroll > 0) {
            int barW = 6; int barX = tableX + tableW - barW - 2; int barY = tableY + 24; int barH = tableH - 26;
            context.fill(barX, barY, barX + barW, barY + barH, 0x55000000);
            int thumbH = Math.max(10, (int) (barH * ((float) rowsPerPage / filtered.size())));
            int thumbY = barY + (int) ((barH - thumbH) * ((float) tableScroll / maxScroll));
            context.fill(barX, thumbY, barX + barW, thumbY + thumbH, 0xFF888888);
        }

        context.drawCenteredTextWithShadow(textRenderer, String.format("검색된 라이더: %d명", filtered.size()), cx, height - 26, 0xAAAAAA);
        super.render(context, mouseX, mouseY, delta);
    }

    private void drawRectBorder(DrawContext context, int x, int y, int w, int h, int color) { context.fill(x, y, x + w, y + 1, color); context.fill(x, y + h - 1, x + w, y + h, color); context.fill(x, y, x + 1, y + h, color); context.fill(x + w - 1, y, x + w, y + h, color); }
    @Override public boolean shouldPause() { return false; }
}