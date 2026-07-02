package com.example.rankinglog;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;

import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Consumer;

public class RankingScreen extends Screen {

    private String track;
    private final List<Entry> ranking = new ArrayList<>();

    private int tableScroll = 0;
    private int currentOffset = 0;
    private static final int FETCH_LIMIT = 50;
    private boolean hasMoreData = true;
    private boolean isFetchingMore = false;

    private boolean loading = true;
    private String error = null;

    private ButtonWidget searchBtn;
    private ButtonWidget closeBtn;
    private ButtonWidget legacyRefreshBtn;

    private ButtonWidget tireToggleBtn;
    private ButtonWidget engineToggleBtn;
    private ButtonWidget trackSelectBtn;
    private ButtonWidget modeToggleBtn;

    private boolean tirePanelOpen = false;
    private final List<String> tires = new ArrayList<>();
    private String selectedTire = "ALL";

    private boolean enginePanelOpen = false;
    private final List<String> engines = new ArrayList<>();
    private String selectedEngine = "ALL";

    private boolean modePanelOpen = false;
    static final List<String> FIXED_MODES = List.of("팀전", "무한 부스터 모드", "톡톡이 모드", "갓겜 모드", "벽 충돌 페널티");
    private final LinkedHashSet<String> selectedModes = new LinkedHashSet<>();

    private static final int OUTER_PAD = 12;
    private static final int HEADER_TOP = 10;
    private static final int BTN_H = 18;
    private static final int BTN_W_SMALL = 84;
    private static final int BTN_W_TRACK = 110;
    private static final int BTN_GAP = 6;
    private static final int PANEL_PAD = 6;
    private static final int ROW_H = 16;
    private static final int CHECK_SIZE = 12;
    private static final int ENGINE_PANEL_W = 150;
    private static final int TIRE_PANEL_W = 150;
    private static final int MODE_PANEL_W = 200;
    private static final int ENGINE_PANEL_MAX_H = 140;

    private int engineScroll = 0;
    private int engineVisibleRows = 5;
    private int headerH = 66;
    private int rowsPerPage = 6;

    private int tirePanelX, tirePanelY, tirePanelW, tirePanelH;
    private int enginePanelX, enginePanelY, enginePanelW, enginePanelH;
    private int modePanelX, modePanelY, modePanelW, modePanelH;
    private int tableX, tableTop, tableW, tableH;

    // ★ 인라인 아코디언 및 스탯 정보 관련 변수
    private SharedSidebar sharedSidebar;
    private Entry selectedDetailEntry = null;
    private String selectedProfileDesc = "불러오는 중...";
    private ButtonWidget globeBtn;

    // ★ 수동 버튼 렌더링 및 충돌 체크를 위한 좌표/크기 상태 변수들
    private boolean kartSpecExpanded = false;
    private int specBtnScreenX, specBtnScreenY, specBtnScreenW, specBtnScreenH;
    private int profileBtnScreenX, profileBtnScreenY, profileBtnScreenW, profileBtnScreenH;

    public static final String SUPABASE_URL = "https://wmlcwmfabuziancpxdoq.supabase.co/rest/v1/";
    public static final String SUPABASE_RPC_URL = SUPABASE_URL + "rpc/";
    public static final String SUPABASE_KEY = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6IndtbGN3bWZhYnV6aWFuY3B4ZG9xIiwicm9sZSI6ImFub24iLCJpYXQiOjE3NzU4ODEzMzQsImV4cCI6MjA5MTQ1NzMzNH0.0ZZJDv7qMRZzC7QdO2SYWApQ0ezSa-cx1M0aOawKe8M";

    public RankingScreen(String track) {
        super(Text.literal("랭킹"));
        this.track = sanitizeTrackOrDefault(track);
    }

    private static String sanitizeTrackOrDefault(String track) {
        if (track == null || track.isBlank()) {
            String def = ModConfig.get().defaultTrack;
            return (def == null || def.isBlank()) ? "[α] 빌리지 고가의 질주" : def;
        }
        return track.replace("\n", " ").replaceAll("\\s+", " ").trim();
    }

    private boolean isNewUi() {
        try {
            return !ModConfig.get().useLegacyUi;
        } catch (Exception e) { return false; }
    }

    private int getEffectiveSidebarWidth() {
        if (!isNewUi() || sharedSidebar == null) return 0;
        int sw = sharedSidebar.getCurrentWidth(this.width);
        return sw == 0 ? 20 : sw;
    }

    private int parseHex(String hex, int fallback) {
        if (hex == null || hex.isEmpty()) return fallback;
        try { if (hex.startsWith("#")) hex = hex.substring(1); return 0xFF000000 | Integer.parseInt(hex, 16); } catch (Exception e) { return fallback; }
    }

    private void playUiClick() { if (this.client != null) this.client.getSoundManager().play(PositionedSoundInstance.master(SoundEvents.UI_BUTTON_CLICK, 1.0f)); }

    private String trimWithEllipsis(String text, int maxWidth) {
        if (maxWidth <= 0) return "";
        if (this.textRenderer.getWidth(text) <= maxWidth) return text;
        int ellipsisWidth = this.textRenderer.getWidth("...");
        if (maxWidth <= ellipsisWidth) return "";
        return this.textRenderer.trimToWidth(Text.literal(text), maxWidth - ellipsisWidth).getString() + "...";
    }

    // ★ 수동 버튼 렌더링 클릭 충돌 감지 헬퍼 메서드
    private boolean isInside(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx <= x + w && my >= y && my <= y + h;
    }

    private void fetchProfileDesc(String playerName) {
        new Thread(() -> {
            try {
                JsonObject req = new JsonObject();
                req.addProperty("p_player", playerName);
                JsonObject obj = RankingScreen.Net.postJson(SUPABASE_RPC_URL + "get_profile", req.toString());
                if (obj.has("ok") && obj.get("ok").getAsBoolean() && obj.has("description")) {
                    String desc = obj.get("description").getAsString();
                    selectedProfileDesc = desc.trim().isEmpty() ? "작성된 소개글이 없습니다." : desc;
                } else { selectedProfileDesc = "작성된 소개글이 없습니다."; }
            } catch (Exception ex) { selectedProfileDesc = "정보를 불러오지 못했습니다."; }
        }).start();
    }

    @Override
    protected void init() {
        // ★ 서버 목록을 미리 불러와 서버 이름을 표시할 수 있게 준비합니다.
        ServerSelectModalScreen.fetchServersAsync(false);

        if (isNewUi()) {
            sharedSidebar = new SharedSidebar(
                    this, "RANKING", null, false,
                    catId -> { if (this.client != null) this.client.setScreen(new TmiRankingScreen(new MainMenuScreen(), catId)); },
                    () -> fetchRankingData(true), this::repositionUiElements
            );
        }

        int iconBtnSize = 20;
        searchBtn = ButtonWidget.builder(Text.literal("🔍"), b -> { playUiClick(); if (this.client != null) this.client.setScreen(new RiderFindScreen(this)); }).dimensions(0, 0, iconBtnSize, 20).tooltip(net.minecraft.client.gui.tooltip.Tooltip.of(Text.literal("라이더 찾기"))).build();
        closeBtn = ButtonWidget.builder(Text.literal("⏴"), b -> {
            playUiClick();
            if (ModConfig.get().showMainScreen){
                if (this.client != null){
                    this.client.setScreen(null);
                }
            }
            else {
                close();
            }
        }).dimensions(0, 0, iconBtnSize, 20).tooltip(net.minecraft.client.gui.tooltip.Tooltip.of(Text.literal("뒤로 가기"))).build();
        legacyRefreshBtn = ButtonWidget.builder(Text.literal("🔄"), b -> { playUiClick(); fetchRankingData(true); }).dimensions(0, 0, iconBtnSize, 20).tooltip(net.minecraft.client.gui.tooltip.Tooltip.of(Text.literal("새로 고침"))).build();
        globeBtn = ButtonWidget.builder(Text.literal("🌐"), b -> {
            playUiClick();
            if (this.client != null) this.client.setScreen(new ServerSelectModalScreen(this));
        }).dimensions(0, 0, 28, 22).tooltip(net.minecraft.client.gui.tooltip.Tooltip.of(Text.literal("서버 변경"))).build();
        addDrawableChild(globeBtn);
        addDrawableChild(searchBtn); addDrawableChild(closeBtn); addDrawableChild(legacyRefreshBtn);

        trackSelectBtn = ButtonWidget.builder(Text.literal("트랙 선택 🔍"), b -> { playUiClick(); if (this.client != null) this.client.setScreen(new TrackSelectScreen(this, this.track, this::setTrackAndApplyFromCache)); }).dimensions(0, 0, BTN_W_TRACK, BTN_H).build();

        tireToggleBtn = ButtonWidget.builder(getTireToggleText(), b -> {
            playUiClick(); tirePanelOpen = !tirePanelOpen;
            if (tirePanelOpen) { enginePanelOpen = false; modePanelOpen = false; }
            b.setMessage(getTireToggleText()); if (engineToggleBtn != null) engineToggleBtn.setMessage(getEngineToggleText()); if (modeToggleBtn != null) modeToggleBtn.setMessage(getModeToggleText());
            repositionUiElements();
        }).dimensions(0, 0, BTN_W_SMALL, BTN_H).build();

        engineToggleBtn = ButtonWidget.builder(getEngineToggleText(), b -> {
            playUiClick(); enginePanelOpen = !enginePanelOpen;
            if (enginePanelOpen) { modePanelOpen = false; tirePanelOpen = false; ensureEngineScrollForSelection(); }
            b.setMessage(getEngineToggleText()); if (modeToggleBtn != null) modeToggleBtn.setMessage(getModeToggleText()); if (tireToggleBtn != null) tireToggleBtn.setMessage(getTireToggleText());
            repositionUiElements();
        }).dimensions(0, 0, BTN_W_SMALL, BTN_H).build();

        modeToggleBtn = ButtonWidget.builder(getModeToggleText(), b -> {
            playUiClick(); modePanelOpen = !modePanelOpen;
            if (modePanelOpen) { enginePanelOpen = false; tirePanelOpen = false; }
            b.setMessage(getModeToggleText()); if (engineToggleBtn != null) engineToggleBtn.setMessage(getEngineToggleText()); if (tireToggleBtn != null) tireToggleBtn.setMessage(getTireToggleText());
            repositionUiElements();
        }).dimensions(0, 0, BTN_W_SMALL, BTN_H).build();

        addDrawableChild(trackSelectBtn); addDrawableChild(tireToggleBtn); addDrawableChild(engineToggleBtn); addDrawableChild(modeToggleBtn);

        repositionUiElements();

        if (!ApiCache.isAllReady()) {
            ApiCache.fetchAllAsync(false, p -> fetchRankingData(true), err -> { loading = false; error = err; });
        } else {
            fetchRankingData(true);
        }
    }

    private void computeLayout() {
        headerH = 66; tableTop = HEADER_TOP + headerH + 10;
        int tableBottom = this.height - 40;
        tableH = Math.max(80, tableBottom - tableTop);
        // ★ 최소 1개를 보장하되 약간의 보수적 계산을 위해 -1 여유 마진 추가
        rowsPerPage = Math.max(1, (tableH - 24) / ROW_H);

        boolean newUi = isNewUi();
        int effectiveLeftW = getEffectiveSidebarWidth();
        int rightAreaX = effectiveLeftW == 0 ? OUTER_PAD : OUTER_PAD + effectiveLeftW + 8;
        int rightAreaW = this.width - OUTER_PAD - rightAreaX;
        tableX = newUi ? rightAreaX : OUTER_PAD + 8;
        tableW = newUi ? rightAreaW : this.width - (OUTER_PAD + 8) * 2;
    }

    private void repositionUiElements() {
        computeLayout();
        int effectiveLeftW = getEffectiveSidebarWidth();
        int rightAreaX = effectiveLeftW == 0 ? OUTER_PAD : OUTER_PAD + effectiveLeftW + 8;
        int rightAreaW = this.width - OUTER_PAD - rightAreaX;
        int cx = rightAreaX + rightAreaW / 2;
        int iconBtnSize = 20;

        if (globeBtn != null) globeBtn.setPosition(rightAreaX + 6, HEADER_TOP + 10);
        if (searchBtn != null) searchBtn.setPosition(rightAreaX, this.height - 28);
        if (closeBtn != null) closeBtn.setPosition(this.width - (iconBtnSize * 2) - OUTER_PAD - 5, this.height - 28);
        if (legacyRefreshBtn != null) {
            legacyRefreshBtn.setPosition(this.width - iconBtnSize - OUTER_PAD, this.height - 28);
            legacyRefreshBtn.visible = true;
        }

        int btnY = HEADER_TOP + 40;
        int btnGap = BTN_GAP;
        int btnWSmall = BTN_W_SMALL;
        int btnWTrack = BTN_W_TRACK;

        int maxAllowedW = rightAreaW - 10;
        int neededW = (btnWSmall * 3) + btnWTrack + (btnGap * 3);

        if (neededW > maxAllowedW) {
            btnGap = 2;
            int availableForBtns = maxAllowedW - (btnGap * 3);
            int newSmall = (availableForBtns - 26) / 4;
            btnWSmall = Math.max(20, newSmall);
            btnWTrack = btnWSmall + 26;
        }

        int startX = cx - ((btnWSmall * 3) + btnWTrack + (btnGap * 3)) / 2;

        if (trackSelectBtn != null) { trackSelectBtn.setPosition(startX, btnY); trackSelectBtn.setWidth(btnWTrack); }
        if (tireToggleBtn != null) { tireToggleBtn.setPosition(startX + btnWTrack + btnGap, btnY); tireToggleBtn.setWidth(btnWSmall); }
        if (engineToggleBtn != null) { engineToggleBtn.setPosition(startX + btnWTrack + btnWSmall + btnGap * 2, btnY); engineToggleBtn.setWidth(btnWSmall); }
        if (modeToggleBtn != null) { modeToggleBtn.setPosition(startX + btnWTrack + btnWSmall * 2 + btnGap * 3, btnY); modeToggleBtn.setWidth(btnWSmall); }
    }

    private void setTrackAndApplyFromCache(String newTrack) {
        this.track = sanitizeTrackOrDefault(newTrack);
        selectedTire = ModConfig.get().defaultTire;
        selectedEngine = ModConfig.get().defaultEngine;
        selectedModes.clear(); selectedModes.addAll(ModConfig.get().defaultModes);

        if (tireToggleBtn != null) tireToggleBtn.setMessage(getTireToggleText());
        if (engineToggleBtn != null) engineToggleBtn.setMessage(getEngineToggleText());
        if (modeToggleBtn != null) modeToggleBtn.setMessage(getModeToggleText());

        fetchRankingData(true);
    }

    private void fetchRankingData(boolean reset) {
        if (reset) {
            currentOffset = 0;
            ranking.clear();
            hasMoreData = true;
            tableScroll = 0;
            loading = true;

            selectedDetailEntry = null;
            kartSpecExpanded = false;
        }
        if (!hasMoreData || isFetchingMore) return;

        isFetchingMore = true;
        error = null;

        new Thread(() -> {
            try {
                JsonObject req = new JsonObject();
                req.addProperty("p_track", track);
                req.addProperty("p_tire", selectedTire.equals("ALL") ? "" : selectedTire);
                req.addProperty("p_engine", selectedEngine.equals("ALL") ? "" : selectedEngine);
                req.addProperty("p_modes", String.join(",", selectedModes));
                req.addProperty("p_limit", FETCH_LIMIT);
                req.addProperty("p_offset", currentOffset);

                String reqPlayer1 = (MinecraftClient.getInstance() != null
                        && MinecraftClient.getInstance().getSession() != null)
                        ? MinecraftClient.getInstance().getSession().getUsername() : "";
                com.google.gson.JsonObject reqBody1 = new com.google.gson.JsonObject();
                reqBody1.addProperty("p_server_address", CurrentServerHolder.getForQuery());
                reqBody1.addProperty("p_req_player", reqPlayer1);
                JsonObject obj = Net.postJson(SUPABASE_RPC_URL + "get_all_rankings_v3",
                        reqBody1.toString());

                if (obj.has("ok") && obj.get("ok").getAsBoolean()) {
                    JsonObject rankingsMap = obj.getAsJsonObject("rankings");
                    if (rankingsMap.has(track)) {
                        JsonArray rarr = rankingsMap.getAsJsonObject(track).getAsJsonArray("ranking");

                        List<Entry> tempFiltered = new ArrayList<>();
                        for (int i = 0; i < rarr.size(); i++) {
                            JsonObject o = rarr.get(i).getAsJsonObject();
                            Entry e = new Entry(
                                    safeString(o, "player", "Unknown"),
                                    safeString(o, "repTitle", ""),
                                    safeString(o, "repColor", "#55FFFF"),
                                    safeString(o, "time", "00:00.000"),
                                    safeLong(o, "timeMillis", 0L),
                                    safeString(o, "engineName", "UNKNOWN"),
                                    safeString(o, "bodyName", "UNKNOWN"),
                                    safeString(o, "tireName", "UNKNOWN"),
                                    safeString(o, "modes", "없음"),
                                    safeLong(o, "submittedAtMs", 0L),
                                    getAnyString(o, "serverAddress", "server_address", "UNKNOWN"),
                                    getAnyString(o, "kartSpecDebug", "kart_spec_debug", "없음")
                            );

                            boolean okTire = selectedTire.equalsIgnoreCase("ALL") || selectedTire.equalsIgnoreCase(e.tireName());
                            boolean okEngine = selectedEngine.equalsIgnoreCase("ALL") || selectedEngine.equalsIgnoreCase(normalizeEngine(e.engineName()));
                            Set<String> entryModes = parseModeSet(e.modes());
                            boolean okMode = selectedModes.isEmpty() ? entryModes.isEmpty() : entryModes.equals(selectedModes);
                            if (okTire && okEngine && okMode) tempFiltered.add(e);
                        }

                        int toFetch = Math.min(FETCH_LIMIT, tempFiltered.size() - currentOffset);
                        if (toFetch > 0) {
                            for(int i=0; i<toFetch; i++) ranking.add(tempFiltered.get(currentOffset + i));
                            currentOffset += toFetch;
                        } else {
                            hasMoreData = false;
                        }
                    } else {
                        hasMoreData = false;
                    }

                    populateFilterLists(obj.getAsJsonObject("rankings").has(track) ? obj.getAsJsonObject("rankings").getAsJsonObject(track).getAsJsonArray("ranking") : new JsonArray());
                } else {
                    String rawErr = safeString(obj, "error", "unknown error");
                    // ★ 에러 메시지 매핑 (싱글플레이 차단 포함)
                    error = rawErr.equals("SINGLEPLAY_RESTRICTED") ? safeString(obj, "message", "싱글플레이 랭킹 열람이 제한되어 있습니다.") : rawErr;
                }
            } catch (Exception e) {
                error = e.getMessage();
            } finally {
                isFetchingMore = false;
                loading = false;
                if (this.client != null) this.client.execute(this::repositionUiElements);
            }
        }).start();
    }

    private static String getAnyString(JsonObject o, String key1, String key2, String def) {
        if (o.has(key1) && !o.get(key1).isJsonNull()) return o.get(key1).getAsString();
        if (o.has(key2) && !o.get(key2).isJsonNull()) return o.get(key2).getAsString();
        return def;
    }

    private void populateFilterLists(JsonArray rarr) {
        LinkedHashSet<String> tireSet = new LinkedHashSet<>(); tireSet.add("ALL");
        Map<String, Integer> engineCounts = new HashMap<>();

        for (int i = 0; i < rarr.size(); i++) {
            JsonObject o = rarr.get(i).getAsJsonObject();
            String t = safeString(o, "tireName", "UNKNOWN");
            tireSet.add(t.isBlank() ? "UNKNOWN" : t.trim());
            String eng = normalizeEngine(safeString(o, "engineName", "UNKNOWN"));
            engineCounts.put(eng, engineCounts.getOrDefault(eng, 0) + 1);
        }

        tires.clear(); tires.addAll(tireSet); tires.removeIf(Objects::isNull);
        tires.sort((a, b) -> a.equals("ALL") ? -1 : b.equals("ALL") ? 1 : a.equalsIgnoreCase("UNKNOWN") ? 1 : b.equalsIgnoreCase("UNKNOWN") ? -1 : a.compareTo(b));

        engines.clear(); engines.add("ALL");
        List<String> sortedEngines = new ArrayList<>(engineCounts.keySet());
        sortedEngines.sort((a, b) -> {
            int countDiff = Integer.compare(engineCounts.get(b), engineCounts.get(a));
            if (countDiff != 0) return countDiff; return a.compareTo(b);
        });
        engines.addAll(sortedEngines);
    }

    private Text getTireToggleText() { return Text.literal("타이어 " + (tirePanelOpen ? "▾" : "▸") + " : " + (selectedTire.equalsIgnoreCase("ALL") ? "전체" : selectedTire)); }
    private Text getEngineToggleText() { return Text.literal("엔진 " + (enginePanelOpen ? "▾" : "▸") + " : " + (selectedEngine.equalsIgnoreCase("ALL") ? "전체" : selectedEngine)); }
    private Text getModeToggleText() {
        String show = selectedModes.isEmpty() ? "없음" : selectedModes.size() == 1 ? selectedModes.getFirst() : new ArrayList<>(selectedModes).getFirst() + " +" + (selectedModes.size() - 1);
        return Text.literal("모드 " + (modePanelOpen ? "▾" : "▸") + " : " + show);
    }

    private static String normalizeEngine(String engineName) {
        if (engineName == null) return "UNKNOWN"; String s = engineName.trim();
        if (s.startsWith("[") && s.endsWith("]") && s.length() >= 3) s = s.substring(1, s.length() - 1).trim();
        s = s.replace("엔진", "").replace("ENGINE", "").replace("engine", "").trim();
        return s.isBlank() ? "UNKNOWN" : s.toUpperCase();
    }

    private static Set<String> parseModeSet(String modesCsv) {
        if (modesCsv == null) return Collections.emptySet(); String s = modesCsv.trim();
        if (s.isBlank() || s.equals("없음")) return Collections.emptySet();
        LinkedHashSet<String> set = new LinkedHashSet<>();
        for (String p : s.split(",")) { String v = p.trim(); if (!v.isEmpty() && !v.equals("없음")) set.add(v); }
        return set;
    }

    private void renderHeader(DrawContext context) {
        int effectiveLeftW = getEffectiveSidebarWidth();
        int rightAreaX = effectiveLeftW == 0 ? OUTER_PAD : OUTER_PAD + effectiveLeftW + 8;
        int rightAreaW = this.width - OUTER_PAD - rightAreaX;
        int cx = rightAreaX + rightAreaW / 2;

        int x = rightAreaX, y = HEADER_TOP, w = rightAreaW, h = headerH;
        context.fill(x, y, x + w, y + h, 0xCC000000);
        drawRectBorder(context, x, y, w, h, 0xFF2A2A2A);

        String tr = (track == null || track.isBlank()) ? ModConfig.get().defaultTrack : track;
        context.drawCenteredTextWithShadow(this.textRenderer, "TRACK", cx, y + 10, 0xFFFFFF);

        float scale = Math.min(1.0f, (rightAreaW - 20) / (float)this.textRenderer.getWidth(tr));
        context.getMatrices().push();
        context.getMatrices().translate(cx, y + 24, 0);
        context.getMatrices().scale(scale, scale, 1.0f);
        context.drawCenteredTextWithShadow(this.textRenderer, tr, 0, 0, 0xFFDDAA);
        context.getMatrices().pop();

        // ★ 현재 서버 이름 렌더링
        if (globeBtn != null) {
            String currentServerName = ServerSelectModalScreen.getServerTitle(CurrentServerHolder.getForQuery());
            context.drawTextWithShadow(this.textRenderer, "§a" + currentServerName, globeBtn.getX() + globeBtn.getWidth() + 6, y + 17, 0xFFFFFF);
        }

        if (tirePanelOpen && tireToggleBtn != null) drawActiveButtonGlow(context, tireToggleBtn);
        if (enginePanelOpen && engineToggleBtn != null) drawActiveButtonGlow(context, engineToggleBtn);
        if (modePanelOpen && modeToggleBtn != null) drawActiveButtonGlow(context, modeToggleBtn);
    }

    private void drawActiveButtonGlow(DrawContext context, ButtonWidget btn) {
        context.fill(btn.getX() - 2, btn.getY() - 2, btn.getX() + btn.getWidth() + 2, btn.getY() + btn.getHeight(), 0x5533FF33);
        drawRectBorder(context, btn.getX() - 2, btn.getY() - 2, btn.getWidth() + 4, btn.getHeight() + 2, 0xFF66FF66);
    }

    private void ensureEngineScrollForSelection() {
        if (engines.isEmpty()) { engineScroll = 0; return; }
        int idx = engines.indexOf(selectedEngine); if (idx < 0) { engineScroll = 0; return; }
        int maxScroll = Math.max(0, engines.size() - Math.max(1, engineVisibleRows));
        if (idx < engineScroll) engineScroll = idx;
        else if (idx >= engineScroll + Math.max(1, engineVisibleRows)) engineScroll = idx - Math.max(1, engineVisibleRows) + 1;
        engineScroll = Math.max(0, Math.min(engineScroll, maxScroll));
    }

    private void renderTireDropdown(DrawContext context, int mouseX, int mouseY) {
        if (tireToggleBtn == null) return;
        tirePanelX = Math.max(5, Math.min(tireToggleBtn.getX(), this.width - TIRE_PANEL_W - 5));
        tirePanelY = tireToggleBtn.getY() + BTN_H + 8; tirePanelW = TIRE_PANEL_W;
        tirePanelH = Math.min(PANEL_PAD * 2 + Math.max(1, tires.size()) * ROW_H, 140);
        context.fill(tirePanelX, tirePanelY, tirePanelX + tirePanelW, tirePanelY + tirePanelH, 0xEE0B0B0B);
        drawRectBorder(context, tirePanelX, tirePanelY, tirePanelW, tirePanelH, 0xFF444444);
        int y = tirePanelY + PANEL_PAD;
        for (String t : tires) {
            boolean hover = mouseX >= tirePanelX && mouseX < tirePanelX + tirePanelW && mouseY >= y && mouseY < y + ROW_H;
            if (hover) context.fill(tirePanelX + 1, y, tirePanelX + tirePanelW - 1, y + ROW_H, 0xFF1C1C1C);
            if (t.equalsIgnoreCase(selectedTire)) context.fill(tirePanelX + 1, y, tirePanelX + tirePanelW - 1, y + ROW_H, 0xFF153015);
            context.drawTextWithShadow(this.textRenderer, t.equals("ALL") ? "전체" : t, tirePanelX + PANEL_PAD, y + (ROW_H - 8) / 2, 0xFFFFFF);
            y += ROW_H; if (y > tirePanelY + tirePanelH - PANEL_PAD) break;
        }
    }

    private void renderEngineDropdown(DrawContext context, int mouseX, int mouseY) {
        if (engineToggleBtn == null) return;
        enginePanelX = Math.max(5, Math.min(engineToggleBtn.getX(), this.width - ENGINE_PANEL_W - 5));
        enginePanelY = engineToggleBtn.getY() + BTN_H + 8; enginePanelW = ENGINE_PANEL_W;
        enginePanelH = Math.min(PANEL_PAD * 2 + Math.max(1, engines.size()) * ROW_H, ENGINE_PANEL_MAX_H);
        engineVisibleRows = Math.max(1, (enginePanelH - PANEL_PAD * 2) / ROW_H);
        int maxScroll = Math.max(0, engines.size() - engineVisibleRows);
        engineScroll = Math.max(0, Math.min(engineScroll, maxScroll));
        context.fill(enginePanelX, enginePanelY, enginePanelX + enginePanelW, enginePanelY + enginePanelH, 0xEE0B0B0B);
        drawRectBorder(context, enginePanelX, enginePanelY, enginePanelW, enginePanelH, 0xFF444444);
        int scrollBarW = (maxScroll > 0) ? 8 : 0; int listW = enginePanelW - scrollBarW; int y = enginePanelY + PANEL_PAD;
        for (int idx = engineScroll; idx < Math.min(engines.size(), engineScroll + engineVisibleRows); idx++) {
            boolean hover = mouseX >= enginePanelX && mouseX < enginePanelX + listW && mouseY >= y && mouseY < y + ROW_H;
            if (hover) context.fill(enginePanelX + 1, y, enginePanelX + listW - 1, y + ROW_H, 0xFF1C1C1C);
            if (engines.get(idx).equalsIgnoreCase(selectedEngine)) context.fill(enginePanelX + 1, y, enginePanelX + listW - 1, y + ROW_H, 0xFF153015);
            context.drawTextWithShadow(this.textRenderer, engines.get(idx).equals("ALL") ? "전체" : engines.get(idx), enginePanelX + PANEL_PAD, y + (ROW_H - 8) / 2, 0xFFFFFF);
            y += ROW_H;
        }
        if (maxScroll > 0) {
            int barX = enginePanelX + enginePanelW - scrollBarW;
            context.fill(barX, enginePanelY + 1, barX + scrollBarW, enginePanelY + enginePanelH - 1, 0xFF111111);
            int thumbH = Math.max(12, (int) ((enginePanelH - 2) * (engineVisibleRows / (float) engines.size())));
            int thumbY = enginePanelY + 1 + (int) (((enginePanelH - 2) - thumbH) * (engineScroll / (float) maxScroll));
            context.fill(barX + 1, thumbY, barX + scrollBarW - 1, thumbY + thumbH, 0xFF3A3A3A);
            drawRectBorder(context, barX + 1, thumbY, scrollBarW - 2, thumbH, 0xFF777777);
        }
    }

    private void renderModeDropdown(DrawContext context, int mouseX, int mouseY) {
        if (modeToggleBtn == null) return;
        modePanelW = MODE_PANEL_W;
        modePanelX = Math.max(5, Math.min(modeToggleBtn.getX() + modeToggleBtn.getWidth() - modePanelW, this.width - modePanelW - 5));
        modePanelY = modeToggleBtn.getY() + BTN_H + 8; modePanelH = PANEL_PAD * 2 + FIXED_MODES.size() * ROW_H;
        context.fill(modePanelX, modePanelY, modePanelX + modePanelW, modePanelY + modePanelH, 0xEE0B0B0B);
        drawRectBorder(context, modePanelX, modePanelY, modePanelW, modePanelH, 0xFF666666);
        int rowY = modePanelY + PANEL_PAD;
        for (String mode : FIXED_MODES) {
            boolean checked = selectedModes.contains(mode);
            boolean hoverRow = mouseX >= modePanelX && mouseX < modePanelX + modePanelW && mouseY >= rowY && mouseY < rowY + ROW_H;
            if (hoverRow) context.fill(modePanelX + 1, rowY, modePanelX + modePanelW - 1, rowY + ROW_H, 0xFF1E1E1E);
            context.drawTextWithShadow(this.textRenderer, mode, modePanelX + PANEL_PAD, rowY + (ROW_H - 8) / 2, 0xFFFFFF);
            int boxX = modePanelX + modePanelW - PANEL_PAD - CHECK_SIZE; int boxY = rowY + (ROW_H - CHECK_SIZE) / 2;
            boolean hoverBox = mouseX >= boxX && mouseX < boxX + CHECK_SIZE && mouseY >= boxY && mouseY < boxY + CHECK_SIZE;
            int bg = (hoverRow || hoverBox) ? 0xFF2A2A2A : 0xFF141414;
            context.fill(boxX, boxY, boxX + CHECK_SIZE, boxY + CHECK_SIZE, bg);
            drawRectBorder(context, boxX, boxY, CHECK_SIZE, CHECK_SIZE, 0xFFAAAAAA);
            if (checked) context.fill(boxX + 3, boxY + 3, boxX + CHECK_SIZE - 3, boxY + CHECK_SIZE - 3, 0xFF55FF55);
            rowY += ROW_H;
        }
    }

    // ★ 로딩 상태 등과 무관하게 패널을 최상단에 그려주는 헬퍼 메서드 추가
    private void renderPanels(DrawContext context, int mouseX, int mouseY) {
        if (tirePanelOpen || enginePanelOpen || modePanelOpen) {
            context.getMatrices().push();
            context.getMatrices().translate(0, 0, 400);
            if (tirePanelOpen) renderTireDropdown(context, mouseX, mouseY);
            if (enginePanelOpen) renderEngineDropdown(context, mouseX, mouseY);
            if (modePanelOpen) renderModeDropdown(context, mouseX, mouseY);
            context.getMatrices().pop();
        }
    }

    private void drawRectBorder(DrawContext context, int x, int y, int w, int h, int color) {
        context.fill(x, y, x + w, y + 1, color); context.fill(x, y + h - 1, x + w, y + h, color);
        context.fill(x, y, x + 1, y + h, color); context.fill(x + w - 1, y, x + w, y + h, color);
    }

    private void renderTooltip(DrawContext context, net.minecraft.text.Text text, int x, int y) {
        if (textRenderer == null) return;
        int tw = textRenderer.getWidth(text);
        int tx = x + 8;
        int ty = y - 16;
        if (tx + tw + 8 > this.width) tx = this.width - tw - 8;
        if (ty < 4) ty = y + 12;
        context.fill(tx - 4, ty - 4, tx + tw + 4, ty + 12, 0xF0100010);
        context.fill(tx - 3, ty - 3, tx + tw + 3, ty + 11, 0xF0100010);
        context.drawBorder(tx - 4, ty - 4, tw + 8, 16, 0xFF5000FF);
        context.drawTextWithShadow(textRenderer, text, tx, ty, 0xFFFFFF);
    }


    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (isNewUi() && sharedSidebar != null && sharedSidebar.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount)) {
            return true;
        }

        if (enginePanelOpen && mouseX >= enginePanelX && mouseX < enginePanelX + enginePanelW && mouseY >= enginePanelY && mouseY < enginePanelY + enginePanelH) {
            int maxScroll = Math.max(0, engines.size() - engineVisibleRows);
            if (verticalAmount > 0) engineScroll--; else if (verticalAmount < 0) engineScroll++;
            engineScroll = Math.max(0, Math.min(engineScroll, maxScroll));
            return true;
        }

        if (!loading && error == null) {
            if (mouseX >= tableX && mouseX <= tableX + tableW && mouseY >= tableTop && mouseY <= tableTop + tableH) {

                // ★ 펼쳐진 항목에 의한 동적 높이 보정을 포함한 스크롤 계산 로직
                int extraH = 0;
                if (selectedDetailEntry != null) {
                    boolean isSingle = "__singleplay__".equals(selectedDetailEntry.serverAddress());
                    extraH += 16 * 3;

                    boolean showTime = tableW > 250;
                    boolean showBody = tableW > 320;
                    boolean showEngine = tableW > 380;

                    int hiddenCount = 0;
                    if (!showTime) hiddenCount++;
                    if (!showBody) hiddenCount++;
                    if (!showEngine) hiddenCount++;
                    if (hiddenCount > 0) extraH += hiddenCount * 16 + 5;

                    if (isSingle && kartSpecExpanded) {
                        String specRaw = selectedDetailEntry.kartSpecDebug();
                        if (specRaw != null && !specRaw.isEmpty() && !specRaw.equals("없음")) {
                            int lines = 1;
                            if (specRaw.contains("speed")) lines++;
                            if (specRaw.contains("accel") || specRaw.contains("boost:")) lines++;
                            if (specRaw.contains("corner") || specRaw.contains("drift")) lines++;
                            if (specRaw.contains("gauge") || specRaw.contains("boosttime") || specRaw.contains("maxboostcount")) lines++;
                            if (specRaw.contains("defense")) lines++;
                            if (specRaw.contains("draft")) lines++;
                            extraH += lines * 16 + 10;
                        } else {
                            extraH += 16 + 10;
                        }
                    }
                    extraH += 26;
                }

                // ★ 스크롤 한계(maxScroll) 계산 시 여유 마진(+1) 부여로 하단 잘림 방지
                int adjustedCapacityRows = Math.max(1, (tableH - 24 - extraH) / ROW_H) + 1;
                int maxScroll = Math.max(0, ranking.size() - adjustedCapacityRows + 1);

                tableScroll = Math.max(0, Math.min(tableScroll - (int)Math.signum(verticalAmount) * 3, maxScroll));

                if (tableScroll >= maxScroll - 1 && hasMoreData && !isFetchingMore) {
                    fetchRankingData(false);
                }
                return true;
            }
        }

        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (isNewUi() && sharedSidebar != null && sharedSidebar.mouseClicked(mouseX, mouseY, button)) return true;

        if (tirePanelOpen) {
            if (mouseX < tirePanelX || mouseX >= tirePanelX + tirePanelW || mouseY < tirePanelY || mouseY >= tirePanelY + tirePanelH) {
                tirePanelOpen = false; if (tireToggleBtn != null) tireToggleBtn.setMessage(getTireToggleText()); repositionUiElements(); return true;
            }
            int y = tirePanelY + PANEL_PAD;
            for (String tire : tires) {
                if (mouseY >= y && mouseY < y + ROW_H) {
                    selectedTire = tire; playUiClick(); tirePanelOpen = false; fetchRankingData(true);
                    if (tireToggleBtn != null) tireToggleBtn.setMessage(getTireToggleText()); return true;
                }
                y += ROW_H; if (y > tirePanelY + tirePanelH - PANEL_PAD) break;
            }
            return true;
        }
        if (enginePanelOpen) {
            if (mouseX < enginePanelX || mouseX >= enginePanelX + enginePanelW || mouseY < enginePanelY || mouseY >= enginePanelY + enginePanelH) {
                enginePanelOpen = false; if (engineToggleBtn != null) engineToggleBtn.setMessage(getEngineToggleText()); repositionUiElements(); return true;
            }
            int y = enginePanelY + PANEL_PAD;
            for (int idx = engineScroll; idx < Math.min(engines.size(), engineScroll + engineVisibleRows); idx++) {
                if (mouseY >= y && mouseY < y + ROW_H) {
                    selectedEngine = engines.get(idx); playUiClick(); enginePanelOpen = false; fetchRankingData(true);
                    if (engineToggleBtn != null) engineToggleBtn.setMessage(getEngineToggleText()); return true;
                }
                y += ROW_H;
            }
            return true;
        }
        if (modePanelOpen) {
            if (mouseX < modePanelX || mouseX >= modePanelX + modePanelW || mouseY < modePanelY || mouseY >= modePanelY + modePanelH) {
                modePanelOpen = false; if (modeToggleBtn != null) modeToggleBtn.setMessage(getModeToggleText()); repositionUiElements(); return true;
            }
            int rowY = modePanelY + PANEL_PAD;
            for (String mode : FIXED_MODES) {
                if (mouseX >= modePanelX && mouseX < modePanelX + modePanelW && mouseY >= rowY && mouseY < rowY + ROW_H) {
                    if (selectedModes.contains(mode)) selectedModes.remove(mode); else selectedModes.add(mode);
                    playUiClick(); fetchRankingData(true);
                    if (modeToggleBtn != null) modeToggleBtn.setMessage(getModeToggleText()); return true;
                }
                rowY += ROW_H;
            }
            return true;
        }

        if (!loading && error == null && !ranking.isEmpty()) {
            if (mouseX >= tableX && mouseX <= tableX + tableW && mouseY >= tableTop + 24 && mouseY <= tableTop + tableH) {

                if (selectedDetailEntry != null && profileBtnScreenW > 0) {
                    if (isInside(mouseX, mouseY, profileBtnScreenX, profileBtnScreenY, profileBtnScreenW, profileBtnScreenH)) {
                        playUiClick();
                        if (this.client != null) {
                            this.client.setScreen(new PlayerProfileScreen(selectedDetailEntry.player(), this));
                        }
                        return true;
                    }
                }

                if (selectedDetailEntry != null && specBtnScreenW > 0) {
                    if (isInside(mouseX, mouseY, specBtnScreenX, specBtnScreenY, specBtnScreenW, specBtnScreenH)) {
                        playUiClick();
                        kartSpecExpanded = !kartSpecExpanded;
                        return true;
                    }
                }

                int startY = tableTop + 24;

                // ★ 스크롤 한계를 보정하여 아이템 갯수 계산 (렌더링 영역 벗어나는 버그 방지)
                int extraH = 0;
                if (selectedDetailEntry != null) {
                    boolean isSingle = "__singleplay__".equals(selectedDetailEntry.serverAddress());
                    extraH += 16 * 3;

                    boolean showTime = tableW > 250;
                    boolean showBody = tableW > 320;
                    boolean showEngine = tableW > 380;

                    int hiddenCount = 0;
                    if (!showTime) hiddenCount++;
                    if (!showBody) hiddenCount++;
                    if (!showEngine) hiddenCount++;
                    if (hiddenCount > 0) extraH += hiddenCount * 16 + 5;

                    if (isSingle && kartSpecExpanded) {
                        String specRaw = selectedDetailEntry.kartSpecDebug();
                        if (specRaw != null && !specRaw.isEmpty() && !specRaw.equals("없음")) {
                            int lines = 1;
                            if (specRaw.contains("speed")) lines++;
                            if (specRaw.contains("accel") || specRaw.contains("boost:")) lines++;
                            if (specRaw.contains("corner") || specRaw.contains("drift")) lines++;
                            if (specRaw.contains("gauge") || specRaw.contains("boosttime") || specRaw.contains("maxboostcount")) lines++;
                            if (specRaw.contains("defense")) lines++;
                            if (specRaw.contains("draft")) lines++;
                            extraH += lines * 16 + 10;
                        } else {
                            extraH += 16 + 10;
                        }
                    }
                    extraH += 26;
                }

                // ★ maxScroll 산출 시 여유 마진(+1) 부여
                int adjustedCapacityRows = Math.max(1, (tableH - 24 - extraH) / ROW_H) + 1;
                int maxScroll = Math.max(0, ranking.size() - adjustedCapacityRows + 1);

                tableScroll = Math.max(0, Math.min(tableScroll, maxScroll));
                int start = tableScroll;
                // end는 렌더링이 화면을 덮을 수 있도록 여유 있게 계산
                int end = Math.min(start + Math.max(1, (tableH - 24) / ROW_H) + 3, ranking.size());

                int currentY = startY;
                for (int i = start; i < end; i++) {
                    Entry e = ranking.get(i);
                    int itemH = ROW_H;

                    if (e == selectedDetailEntry) {
                        boolean isSingle = "__singleplay__".equals(e.serverAddress());
                        int localExpandH = 16 * 3;

                        boolean showTime = tableW > 250;
                        boolean showBody = tableW > 320;
                        boolean showEngine = tableW > 380;

                        int hiddenCount = 0;
                        if (!showTime) hiddenCount++;
                        if (!showBody) hiddenCount++;
                        if (!showEngine) hiddenCount++;
                        if (hiddenCount > 0) {
                            localExpandH += hiddenCount * 16 + 5;
                        }

                        if (isSingle) {
                            if (kartSpecExpanded) {
                                String specRaw = e.kartSpecDebug();
                                if (specRaw != null && !specRaw.isEmpty() && !specRaw.equals("없음")) {
                                    int lines = 1;
                                    if (specRaw.contains("speed")) lines++;
                                    if (specRaw.contains("accel") || specRaw.contains("boost:")) lines++;
                                    if (specRaw.contains("corner") || specRaw.contains("drift")) lines++;
                                    if (specRaw.contains("gauge") || specRaw.contains("boosttime") || specRaw.contains("maxboostcount")) lines++;
                                    if (specRaw.contains("defense")) lines++;
                                    if (specRaw.contains("draft")) lines++;
                                    localExpandH += lines * 16 + 10;
                                } else {
                                    localExpandH += 16 + 10;
                                }
                            }
                        }

                        localExpandH += 26;
                        itemH += localExpandH;
                    }

                    if (currentY > tableTop + tableH) break;

                    if (mouseY >= currentY - 2 && mouseY <= currentY + itemH - 2) {
                        playUiClick();
                        if (selectedDetailEntry == e) {
                            selectedDetailEntry = null;
                            kartSpecExpanded = false;
                        } else {
                            selectedDetailEntry = e;
                            kartSpecExpanded = false;
                            selectedProfileDesc = "불러오는 중...";
                            fetchProfileDesc(e.player());
                        }
                        return true;
                    }
                    currentY += itemH;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackground(context, mouseX, mouseY, delta);

        if (isNewUi() && sharedSidebar != null) sharedSidebar.render(context, mouseX, mouseY, delta);

        renderHeader(context);

        int effectiveLeftW = getEffectiveSidebarWidth();
        int rightAreaX = effectiveLeftW == 0 ? OUTER_PAD : OUTER_PAD + effectiveLeftW + 8;
        int rightAreaW = this.width - OUTER_PAD - rightAreaX;

        int colRankX   = tableX + (int)(tableW * 0.02);
        int colPlayerX = tableX + (int)(tableW * 0.15);
        int colTimeX   = tableX + (int)(tableW * 0.40);
        int colBodyX   = tableX + (int)(tableW * 0.60);
        int colEngineX = tableX + (int)(tableW * 0.85);

        boolean showTime   = tableW > 250; boolean showBody   = tableW > 320; boolean showEngine = tableW > 380;

        // ★ 로딩 중 조기 종료(return) 전에 무조건 패널을 그리도록 호출
        if (loading && ranking.isEmpty()) {
            int cx = rightAreaX + rightAreaW / 2;
            context.drawCenteredTextWithShadow(this.textRenderer, "불러오는 중...", cx, tableTop + 26, 0xFFFFFF);
            super.render(context, mouseX, mouseY, delta);
            renderPanels(context, mouseX, mouseY);
            return;
        }
        if (error != null && ranking.isEmpty()) {
            int cx = rightAreaX + rightAreaW / 2;
            // ★ 에러 메시지를 자연스럽게 표시
            String displayError = error.toLowerCase().contains("http") ? "이 버전은 서비스 종료 되었습니다. 최신 버전을 이용해 주세요." : error;
            context.drawCenteredTextWithShadow(this.textRenderer, "오류: " + displayError, cx, tableTop + 26, 0xFF5555);
            super.render(context, mouseX, mouseY, delta);
            renderPanels(context, mouseX, mouseY);
            return;
        }

        context.fill(tableX, tableTop, tableX + tableW, tableTop + tableH, 0x66000000);
        drawRectBorder(context, tableX, tableTop, tableW, tableH, 0xFF222222);

        float tableScale = Math.max(0.8f, Math.min(1.0f, tableW / 450.0f));

        context.getMatrices().push();
        context.getMatrices().scale(tableScale, tableScale, 1.0f);

        int headerRowY = tableTop + 8;
        int sHeadY = (int)(headerRowY / tableScale);

        context.drawTextWithShadow(this.textRenderer, "순위", (int)(colRankX / tableScale), sHeadY, 0xDDDDDD);
        context.drawTextWithShadow(this.textRenderer, "플레이어", (int)(colPlayerX / tableScale), sHeadY, 0xDDDDDD);
        if (showTime) context.drawTextWithShadow(this.textRenderer, "기록", (int)(colTimeX / tableScale), sHeadY, 0xDDDDDD);
        if (showBody) context.drawTextWithShadow(this.textRenderer, "카트바디", (int)(colBodyX / tableScale), sHeadY, 0xDDDDDD);
        if (showEngine) context.drawTextWithShadow(this.textRenderer, "엔진", (int)(colEngineX / tableScale), sHeadY, 0xDDDDDD);

        context.getMatrices().pop();

        context.enableScissor(tableX, tableTop + 24, tableX + tableW, tableTop + tableH);

        int startY = tableTop + 24;

        // ★ 스크롤 한계선(maxScroll) 보정 적용
        int extraH = 0;
        if (selectedDetailEntry != null) {
            boolean isSingle = "__singleplay__".equals(selectedDetailEntry.serverAddress());
            extraH += 16 * 3;

            int hiddenCount = 0;
            if (!showTime) hiddenCount++;
            if (!showBody) hiddenCount++;
            if (!showEngine) hiddenCount++;
            if (hiddenCount > 0) extraH += hiddenCount * 16 + 5;

            if (isSingle && kartSpecExpanded) {
                String specRaw = selectedDetailEntry.kartSpecDebug();
                if (specRaw != null && !specRaw.isEmpty() && !specRaw.equals("없음")) {
                    int lines = 1;
                    if (specRaw.contains("speed")) lines++;
                    if (specRaw.contains("accel") || specRaw.contains("boost:")) lines++;
                    if (specRaw.contains("corner") || specRaw.contains("drift")) lines++;
                    if (specRaw.contains("gauge") || specRaw.contains("boosttime") || specRaw.contains("maxboostcount")) lines++;
                    if (specRaw.contains("defense")) lines++;
                    if (specRaw.contains("draft")) lines++;
                    extraH += lines * 16 + 10;
                } else {
                    extraH += 16 + 10;
                }
            }
            extraH += 26;
        }

        // ★ maxScroll 산출 시 여유 마진(+1) 부여
        int adjustedCapacityRows = Math.max(1, (tableH - 24 - extraH) / ROW_H) + 1;
        int maxScroll = Math.max(0, ranking.size() - adjustedCapacityRows + 1);

        tableScroll = Math.max(0, Math.min(tableScroll, maxScroll));
        int start = tableScroll;
        // end는 화면이 잘리지 않게 충분히 길게 확보
        int end = Math.min(start + Math.max(1, (tableH - 24) / ROW_H) + 3, ranking.size());

        String myName = MinecraftClient.getInstance().getSession().getUsername();

        int currentY = startY;

        profileBtnScreenW = 0;
        specBtnScreenW = 0;

        for (int i = start; i < end; i++) {
            Entry e = ranking.get(i); int rank = i + 1;
            boolean isMe = e.player().equalsIgnoreCase(myName);
            boolean isExpanded = (e == selectedDetailEntry);

            int itemH = ROW_H;
            int localExpandH = 0;
            boolean isSingle = "__singleplay__".equals(e.serverAddress());

            int hiddenCount = 0;
            if (isExpanded) {
                localExpandH += 16 * 3;

                if (!showTime) hiddenCount++;
                if (!showBody) hiddenCount++;
                if (!showEngine) hiddenCount++;
                if (hiddenCount > 0) {
                    localExpandH += hiddenCount * 16 + 5;
                }

                if (isSingle) {
                    if (kartSpecExpanded) {
                        String specRaw = e.kartSpecDebug();
                        if (specRaw != null && !specRaw.isEmpty() && !specRaw.equals("없음")) {
                            int lines = 1;
                            if (specRaw.contains("speed")) lines++;
                            if (specRaw.contains("accel") || specRaw.contains("boost:")) lines++;
                            if (specRaw.contains("corner") || specRaw.contains("drift")) lines++;
                            if (specRaw.contains("gauge") || specRaw.contains("boosttime") || specRaw.contains("maxboostcount")) lines++;
                            if (specRaw.contains("defense")) lines++;
                            if (specRaw.contains("draft")) lines++;
                            localExpandH += lines * 16 + 10;
                        } else {
                            localExpandH += 16 + 10;
                        }
                    }
                }
                itemH += localExpandH + 26;
            }

            if (currentY > tableTop + tableH) break;

            int bgColor = 0x22000000;
            if (isExpanded) bgColor = 0x550B0B0B;
            else if (isMe) bgColor = 0x6644AA44;
            else if (rank == 1) bgColor = 0x44FFD700;
            else if (rank == 2) bgColor = 0x44C0C0C0;
            else if (rank == 3) bgColor = 0x44CD7F32;
            else if (((i - start) & 1) == 0) bgColor = 0x00000000;

            context.fill(tableX + 1, currentY - 2, tableX + tableW - 1, currentY + itemH - 2, bgColor);
            if (isExpanded) {
                drawRectBorder(context, tableX + 1, currentY - 2, tableW - 2, itemH, 0xFF444444);
            }

            if (!isExpanded && mouseX >= tableX && mouseX <= tableX + tableW && mouseY >= currentY - 2 && mouseY <= currentY + itemH - 2) {
                context.fill(tableX + 1, currentY - 2, tableX + tableW - 1, currentY + itemH - 2, 0x33FFFFFF);
            }

            context.getMatrices().push();
            context.getMatrices().scale(tableScale, tableScale, 1.0f);

            int sY = (int)((currentY + (ROW_H - 10 * tableScale) / 2) / tableScale);
            int sPlayerX = (int)(colPlayerX / tableScale);

            int rankColor = (rank == 1) ? 0xFFFFE066 : (rank == 2) ? 0xFFE6E6E6 : (rank == 3) ? 0xFFFFB36B : 0xFFFFFFFF;
            context.drawTextWithShadow(this.textRenderer, rank + "위", (int)(colRankX / tableScale), sY, rankColor);

            String repText = "";
            if (e.repTitle() != null && !e.repTitle().isEmpty()) { repText = " [" + e.repTitle() + "]"; }

            int nextColX = showTime ? colTimeX : (showBody ? colBodyX : (showEngine ? colEngineX : tableX + tableW));
            int maxPlayerW = Math.max(0, (int)((nextColX - colPlayerX - 5) / tableScale));

            if (repText.isEmpty()) {
                context.drawTextWithShadow(this.textRenderer, trimWithEllipsis(e.player(), maxPlayerW), sPlayerX, sY, 0xFFFFFF);
            } else {
                int maxNameW = Math.max(0, maxPlayerW - this.textRenderer.getWidth(repText));
                if (maxNameW <= 0) {
                    context.drawTextWithShadow(this.textRenderer, trimWithEllipsis(e.player() + repText, maxPlayerW), sPlayerX, sY, 0xFFFFFF);
                } else {
                    String trimmedName = trimWithEllipsis(e.player(), maxNameW);
                    context.drawTextWithShadow(this.textRenderer, trimmedName, sPlayerX, sY, 0xFFFFFF);
                    context.drawTextWithShadow(this.textRenderer, repText, sPlayerX + this.textRenderer.getWidth(trimmedName), sY, parseHex(e.repColor(), 0x55FFFF));
                }
            }

            boolean hiddenSomething = false;
            if (showTime) {
                int nextTimeColX = showBody ? colBodyX : (showEngine ? colEngineX : tableX + tableW);
                int maxTimeW = Math.max(0, (int)((nextTimeColX - colTimeX - 5) / tableScale));
                context.drawTextWithShadow(this.textRenderer, trimWithEllipsis(e.timeStr(), maxTimeW), (int)(colTimeX / tableScale), sY, 0xFFFFFF);
            } else hiddenSomething = true;

            if (showBody) {
                int nextBodyColX = showEngine ? colEngineX : tableX + tableW;
                int maxBodyW = Math.max(0, (int)((nextBodyColX - colBodyX - 5) / tableScale));
                String bodyLabel = TireUtil.composeBodyLabel(e.bodyName(), e.tireName());
                context.drawTextWithShadow(this.textRenderer, trimWithEllipsis(bodyLabel, maxBodyW), (int)(colBodyX / tableScale), sY, 0xFFFFFF);

                if (isSingle && isExpanded) {
                    String btnText = kartSpecExpanded ? "[-]" : "[+]";
                    int btnX = (int)(colBodyX / tableScale) + this.textRenderer.getWidth(trimWithEllipsis(bodyLabel, maxBodyW)) + 5;

                    specBtnScreenX = (int)(btnX * tableScale);
                    specBtnScreenY = (int)(sY * tableScale);
                    specBtnScreenW = (int)(this.textRenderer.getWidth(btnText) * tableScale);
                    specBtnScreenH = (int)(10 * tableScale);

                    boolean hoveringSpec = isInside(mouseX, mouseY, specBtnScreenX, specBtnScreenY, specBtnScreenW, specBtnScreenH);
                    int btnColor = kartSpecExpanded ? (hoveringSpec ? 0xFFFF7777 : 0xFFFF5555) : (hoveringSpec ? 0xFF77FF77 : 0xFF55FF55);

                    context.drawTextWithShadow(textRenderer, btnText, btnX, sY, btnColor);
                }
            } else hiddenSomething = true;

            if (showEngine) {
                int maxEngW = Math.max(0, (int)((tableX + tableW - colEngineX - 5) / tableScale));
                context.drawTextWithShadow(this.textRenderer, trimWithEllipsis(normalizeEngine(e.engineName()), maxEngW), (int)(colEngineX / tableScale), sY, 0xFFFFFF);
            } else hiddenSomething = true;

            if (hiddenSomething) {
                context.drawTextWithShadow(this.textRenderer, "+", (int)((tableX + tableW - 20) / tableScale), sY, 0xAAAAAA);
            }
            context.getMatrices().pop();

            if (isExpanded) {
                context.getMatrices().push();
                context.getMatrices().scale(tableScale, tableScale, 1.0f);

                int infoX = (int)((colPlayerX + 5) / tableScale);
                int infoY = sY + 20;
                int lineH = 16;
                int maxW = (int)((tableW - 50) / tableScale);

                context.drawTextWithShadow(textRenderer, trimWithEllipsis("§7" + selectedProfileDesc, maxW), infoX, infoY, 0xAAAAAA);
                infoY += lineH;
                infoY += 5;

                if (hiddenCount > 0) {
                    if (!showTime) {
                        context.drawTextWithShadow(textRenderer, "§8기록: §e" + e.timeStr(), infoX, infoY, 0xFFFFFF);
                        infoY += lineH;
                    }
                    if (!showBody) {
                        context.drawTextWithShadow(textRenderer, "§8카트: §f" + TireUtil.composeBodyLabel(e.bodyName(), e.tireName()), infoX, infoY, 0xFFFFFF);
                        infoY += lineH;
                    }
                    if (!showEngine) {
                        context.drawTextWithShadow(textRenderer, "§8엔진: §f" + normalizeEngine(e.engineName()), infoX, infoY, 0xFFFFFF);
                        infoY += lineH;
                    }
                }

                context.drawTextWithShadow(textRenderer, "§8모드: §f" + (e.modes() == null || e.modes().isEmpty() ? "없음" : e.modes()), infoX, infoY, 0xFFFFFF);
                infoY += lineH;
                String dateStr = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm").format(new java.util.Date(e.submittedAtMs()));
                context.drawTextWithShadow(textRenderer, "§8등록: §7" + dateStr, infoX, infoY, 0xAAAAAA);
                infoY += lineH;

                if (isSingle && kartSpecExpanded) {
                    String specRaw = e.kartSpecDebug();
                    if (specRaw == null || specRaw.isEmpty() || specRaw.equals("없음")) {
                        context.drawTextWithShadow(textRenderer, "§c스탯 정보 없음", infoX, infoY, 0xAAAAAA);
                        infoY += lineH;
                    } else {
                        String kartName = e.bodyName();
                        if (kartName == null || kartName.equals("UNKNOWN") || kartName.isEmpty()) kartName = "카트바디";
                        String headerStr = "§e----- " + kartName + " 스탯 -----";
                        context.drawTextWithShadow(textRenderer, headerStr, infoX, infoY, 0xFFFFFF);
                        infoY += lineH;

                        String[] specs = specRaw.split(",");
                        Map<String, String> specMap = new HashMap<>();
                        for (String sp : specs) {
                            String[] parts = sp.split(":");
                            if (parts.length == 2) specMap.put(parts[0].trim(), parts[1].trim());
                        }

                        List<String> displayLines = new ArrayList<>();
                        if (specMap.containsKey("speed"))
                            displayLines.add("§b스피드: §f" + specMap.get("speed"));
                        if (specMap.containsKey("accel") || specMap.containsKey("boost"))
                            displayLines.add("§b가속력: §7기본 §f" + specMap.getOrDefault("accel", "-") + " §8| §7부스터 §f" + specMap.getOrDefault("boost", "-"));
                        if (specMap.containsKey("corner") || specMap.containsKey("drift"))
                            displayLines.add("§b코너링: §7기본 §f" + specMap.getOrDefault("corner", "-") + " §8| §7탈출력 §f" + specMap.getOrDefault("drift", "-"));
                        if (specMap.containsKey("gauge") || specMap.containsKey("boosttime") || specMap.containsKey("maxboostcount"))
                            displayLines.add("§b부스터: §7충전 §f" + specMap.getOrDefault("gauge", "-") + " §8| §7지속 §f" + specMap.getOrDefault("boosttime", "-") + " §8| §7슬롯 §f" + specMap.getOrDefault("maxboostcount", "-"));
                        if (specMap.containsKey("defense"))
                            displayLines.add("§b충돌: §7방어력 §f" + specMap.get("defense"));
                        if (specMap.containsKey("draft"))
                            displayLines.add("§b드래프트: §f" + specMap.get("draft"));

                        int col1 = infoX;
                        int col2 = infoX + (int)(150 / tableScale);
                        for (int spIdx = 0; spIdx < displayLines.size(); spIdx++) {
                            int px = (spIdx % 2 == 0) ? col1 : col2;
                            int py = infoY + (spIdx / 2) * lineH;
                            context.drawTextWithShadow(textRenderer, displayLines.get(spIdx), px, py, 0xFFFFFF);
                        }
                        infoY += ((displayLines.size() + 1) / 2) * lineH;
                    }
                }
                context.getMatrices().pop();

                int btnW = 26;
                int btnH = 20;
                int btnX = tableX + 10;
                int btnY = currentY + itemH - 24;

                profileBtnScreenX = btnX;
                profileBtnScreenY = btnY;
                profileBtnScreenW = btnW;
                profileBtnScreenH = btnH;

                boolean hoverBtn = isInside(mouseX, mouseY, btnX, btnY, btnW, btnH);
                context.fill(btnX, btnY, btnX + btnW, btnY + btnH, hoverBtn ? 0xFF444444 : 0xFF222222);
                drawRectBorder(context, btnX, btnY, btnW, btnH, hoverBtn ? 0xFF888888 : 0xFF555555);
                context.drawCenteredTextWithShadow(textRenderer, "👤", btnX + btnW / 2, btnY + 6, 0xFFFFFF);
                // 툴팁
                if (hoverBtn) renderTooltip(context, net.minecraft.text.Text.literal("프로필 보기"), mouseX, mouseY);
            }

            currentY += itemH;
        }

        context.disableScissor();

        if (maxScroll > 0) {
            int barW = 6; int barX = tableX + tableW - barW - 2; int barY = tableTop + 24; int barH = tableH - 26;
            context.fill(barX, barY, barX + barW, barY + barH, 0x55000000);

            // ★ 스크롤 썸 높이 및 위치 계산 시 maxScroll 값의 변경에 따라 오작동하지 않게 안전하게 계산
            float scrollProgress = maxScroll > 0 ? (float) tableScroll / maxScroll : 0;
            int thumbH = Math.max(10, (int) (barH * ((float) rowsPerPage / ranking.size())));
            int thumbY = barY + (int) ((barH - thumbH) * scrollProgress);
            context.fill(barX, thumbY, barX + barW, thumbY + thumbH, 0xFF888888);
        }

        super.render(context, mouseX, mouseY, delta);

        // ★ 원래 있던 위치의 호출도 메서드로 깔끔하게 변경
        renderPanels(context, mouseX, mouseY);
    }
    @Override
    public void close() { if (this.client != null) this.client.setScreen(null); }

    @Override
    public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
        context.fill(0, 0, this.width, this.height, ModConfig.get().getBgColor());
    }

    @Override public boolean shouldPause() { return false; }

    public record Entry(String player, String repTitle, String repColor, String timeStr, long timeMillis, String engineName, String bodyName, String tireName, String modes, long submittedAtMs, String serverAddress, String kartSpecDebug) {}

    private static String safeString(JsonObject o, String key, String def) { return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : def; }
    private static long safeLong(JsonObject o, String key, long def) { return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsLong() : def; }
    private static int safeInt(JsonObject o, String key, int def) { return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsInt() : def; }

    public static final class ApiCache {
        private ApiCache() {}
        public static final class RankingPayload { public final List<Entry> ranking; public final List<String> engines; public RankingPayload(List<Entry> ranking, List<String> engines) { this.ranking = ranking; this.engines = engines; } }
        public static final class AllPayload { public final List<TrackSelectScreen.TrackEntry> tracks; public final Map<String, RankingPayload> rankingsByTrack; public final long fetchedAtMs; public final String serverAddress; public AllPayload(List<TrackSelectScreen.TrackEntry> tracks, Map<String, RankingPayload> rankingsByTrack, long fetchedAtMs, String serverAddress) { this.tracks = tracks; this.rankingsByTrack = rankingsByTrack; this.fetchedAtMs = fetchedAtMs; this.serverAddress = serverAddress; } }

        private static volatile AllPayload cachedAll = null; private static volatile boolean fetching = false; private static final List<Runnable> waiters = new ArrayList<>();

        public static boolean isAllReady() { return getAllIfReady() != null; }
        public static AllPayload getAllIfReady() { AllPayload p = cachedAll; if (p == null) return null; if (!CurrentServerHolder.getForQuery().equals(p.serverAddress)) return null; if (System.currentTimeMillis() - p.fetchedAtMs > ModConfig.get().getCacheTtlMs()) return null; return p; }
        public static void clearCache() { cachedAll = null; }

        public static void fetchAllAsync(boolean force, Consumer<AllPayload> onDone, Consumer<String> onError) {
            if (!force) { AllPayload fresh = getAllIfReady(); if (fresh != null) { onDone.accept(fresh); return; } }
            synchronized (ApiCache.class) { if (fetching) { waiters.add(() -> onDone.accept(getAllIfReady())); return; } fetching = true; }

            new Thread(() -> {
                String err = null; AllPayload payload = null;
                try {
                    String reqPlayer2 = (MinecraftClient.getInstance() != null
                            && MinecraftClient.getInstance().getSession() != null)
                            ? MinecraftClient.getInstance().getSession().getUsername() : "";
                    com.google.gson.JsonObject reqBody2 = new com.google.gson.JsonObject();
                    reqBody2.addProperty("p_server_address", CurrentServerHolder.getForQuery());
                    reqBody2.addProperty("p_req_player", reqPlayer2);
                    JsonObject res = Net.postJson(SUPABASE_RPC_URL + "get_all_rankings_v3",
                            reqBody2.toString());
                    if (!res.has("ok") || !res.get("ok").getAsBoolean()) {
                        String rawErr = safeString(res, "error", "unknown error");

                        // ★ 에러 메시지 매핑 (싱글플레이 차단 포함)
                        if (rawErr.equals("RESTRICTED_DEV_SERVER")) {
                            err = safeString(res, "message", "개발 서버 접근 권한이 없습니다.");
                        } else if (rawErr.equals("SINGLEPLAY_RESTRICTED")) {
                            err = safeString(res, "message", "싱글플레이 랭킹 열람이 제한되어 있습니다.");
                        } else {
                            err = rawErr;
                        }
                    }
                    else {
                        List<TrackSelectScreen.TrackEntry> tracks = new ArrayList<>();
                        if (res.has("tracks") && res.get("tracks").isJsonArray()) {
                            JsonArray arr = res.getAsJsonArray("tracks");
                            for (int i = 0; i < arr.size(); i++) { JsonObject o = arr.get(i).getAsJsonObject(); tracks.add(new TrackSelectScreen.TrackEntry(safeString(o, "track", "Unknown"), safeInt(o, "count", 0))); }
                            tracks.sort(Comparator.comparingInt(TrackSelectScreen.TrackEntry::count).reversed());
                        }

                        Map<String, RankingPayload> map = new HashMap<>();
                        if (res.has("rankings") && res.get("rankings").isJsonObject()) {
                            JsonObject robj = res.getAsJsonObject("rankings");
                            for (Map.Entry<String, JsonElement> it : robj.entrySet()) {
                                JsonObject v = it.getValue().getAsJsonObject();
                                List<String> engines = new ArrayList<>();
                                if (v.has("engines") && v.get("engines").isJsonArray()) {
                                    JsonArray earr = v.getAsJsonArray("engines");
                                    for (int j = 0; j < earr.size(); j++) { if (!earr.get(j).isJsonNull()) { String e = earr.get(j).getAsString(); if (e != null && !e.isBlank()) engines.add(e.trim().toUpperCase()); } }
                                }
                                List<Entry> list = new ArrayList<>();
                                if (v.has("ranking") && v.get("ranking").isJsonArray()) {
                                    JsonArray rarr = v.getAsJsonArray("ranking");
                                    for (int j = 0; j < rarr.size(); j++) {
                                        JsonObject o = rarr.get(j).getAsJsonObject();
                                        list.add(new Entry(
                                                safeString(o, "player", "Unknown"),
                                                safeString(o, "repTitle", ""),
                                                safeString(o, "repColor", "#55FFFF"),
                                                safeString(o, "time", "00:00.000"),
                                                safeLong(o, "timeMillis", 0L),
                                                safeString(o, "engineName", "UNKNOWN"),
                                                safeString(o, "bodyName", "UNKNOWN"),
                                                safeString(o, "tireName", "UNKNOWN"),
                                                safeString(o, "modes", "없음"),
                                                safeLong(o, "submittedAtMs", 0L),
                                                getAnyString(o, "serverAddress", "server_address", "UNKNOWN"),
                                                getAnyString(o, "kartSpecDebug", "kart_spec_debug", "없음")
                                        ));
                                    }
                                }
                                list.sort(Comparator.comparingLong(Entry::timeMillis));
                                map.put(it.getKey(), new RankingPayload(list, engines));
                            }
                        }
                        payload = new AllPayload(tracks, map, System.currentTimeMillis(), CurrentServerHolder.getForQuery());
                    }
                } catch (Exception e) { err = e.getMessage(); }

                final String ferr = err; final AllPayload fp = payload;
                MinecraftClient.getInstance().execute(() -> {
                    synchronized (ApiCache.class) { fetching = false; if (fp != null) cachedAll = fp; for (Runnable r : waiters) r.run(); waiters.clear(); }
                    if (ferr != null) onError.accept(ferr); else onDone.accept(fp);
                });
            }, "Supabase-getAll-Fetch").start();
        }
    }

    public static final class Net {
        private Net() {}
        public static JsonObject postJson(String url, String jsonBody) throws Exception {
            URI uri = URI.create(url);
            HttpURLConnection con = (HttpURLConnection) uri.toURL().openConnection();
            con.setRequestMethod("POST"); con.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            con.setRequestProperty("apikey", SUPABASE_KEY); con.setRequestProperty("Authorization", "Bearer " + SUPABASE_KEY);
            con.setDoOutput(true); con.getOutputStream().write(jsonBody.getBytes(StandardCharsets.UTF_8));
            try (InputStreamReader reader = new InputStreamReader(con.getInputStream(), StandardCharsets.UTF_8)) { return JsonParser.parseReader(reader).getAsJsonObject(); }
        }
    }
}