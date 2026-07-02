package com.example.rankinglog;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.sound.SoundEvents;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public class TrackSelectScreen extends Screen {

    private final Screen parent;
    private final String initialTrack;
    private final Consumer<String> onSelectTrack;

    private final List<TrackEntry> all = new ArrayList<>();
    private final List<TrackEntry> filtered = new ArrayList<>();

    // ★ 테마 시스템
    private final List<String> themes = new ArrayList<>();
    private final Map<String, List<TrackEntry>> themeToTracks = new HashMap<>();
    private final Map<String, String> themeExceptions = new HashMap<>();
    private String selectedTheme = "전체";

    // ★ 내 최고기록 매핑 (트랙 이름 -> 내 최고기록 시간 텍스트)
    private final Map<String, String> myBestTimes = new HashMap<>();

    // ★ 챔피언 정보 매핑
    private record ChampionInfo(String name, String timeStr) {}
    private final Map<String, ChampionInfo> trackChampions = new HashMap<>();

    // 스크롤 상태 변수
    private int themeScroll = 0;
    private int trackScroll = 0; // 페이지 대신 스크롤 사용

    private boolean loading = true;
    private String error = null;

    private TextFieldWidget searchBox;
    private ButtonWidget refreshBtn, closeBtn;

    private static final int OUTER_PAD = 12, HEADER_TOP = 10, HOVER_YELLOW = 0xFFFFEE88;

    public TrackSelectScreen(Screen parent, String initialTrack, Consumer<String> onSelectTrack) {
        super(Text.literal("트랙 선택"));
        this.parent = parent;
        this.initialTrack = initialTrack;
        this.onSelectTrack = onSelectTrack;
    }

    @Override
    protected void init() {
        int cx = this.width / 2;

        searchBox = new TextFieldWidget(this.textRenderer, cx - 160, HEADER_TOP + 26, 320, 18, Text.literal("검색"));
        searchBox.setMaxLength(64);
        searchBox.setChangedListener(s -> {
            trackScroll = 0;
            applySearch();
        });
        addDrawableChild(searchBox);

        int themeListW = 100;
        int trackListX = OUTER_PAD + themeListW + 8;
        int trackListW = this.width - OUTER_PAD - trackListX;
        int trackListCx = trackListX + trackListW / 2;

        closeBtn = ButtonWidget.builder(Text.literal("닫기"), b -> close()).dimensions(trackListCx - 75, this.height - 28, 60, 20).build();
        refreshBtn = ButtonWidget.builder(Text.literal("새로 고침"), b -> {
            playUiClick();
            error = null;
            fetchExceptionsAndLoad(true);
        }).dimensions(trackListCx - 5, this.height - 28, 80, 20).build();

        addDrawableChild(closeBtn);
        addDrawableChild(refreshBtn);

        setInitialFocus(searchBox);

        fetchExceptionsAndLoad(false);
    }

    private void fetchExceptionsAndLoad(boolean force) {
        loading = true;
        new Thread(() -> {
            try {
                JsonObject req = new JsonObject();
                JsonObject res = RankingScreen.Net.postJson(RankingScreen.SUPABASE_RPC_URL + "get_track_theme_exceptions", req.toString());

                themeExceptions.clear();
                if (res != null && res.has("ok") && res.get("ok").getAsBoolean() && res.has("data")) {
                    JsonArray dataArr = res.getAsJsonArray("data");
                    for (JsonElement el : dataArr) {
                        JsonObject obj = el.getAsJsonObject();
                        themeExceptions.put(obj.get("track_name").getAsString(), obj.get("theme_name").getAsString());
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }

            if (this.client != null) {
                this.client.execute(() -> {
                    if (force || !RankingScreen.ApiCache.isAllReady()) {
                        RankingScreen.ApiCache.fetchAllAsync(force, p -> {
                            loading = false;
                            loadFromCache();
                        }, err -> {
                            loading = false;
                            error = err;
                        });
                    } else {
                        loading = false;
                        loadFromCache();
                    }
                });
            }
        }).start();
    }

    private void playUiClick() {
        if (this.client == null) return;
        this.client.getSoundManager().play(PositionedSoundInstance.master(SoundEvents.UI_BUTTON_CLICK, 1.0f));
    }

    private String extractTheme(String trackName) {
        if (trackName == null) return "기타";
        if (themeExceptions.containsKey(trackName)) return themeExceptions.get(trackName);

        String clean = trackName.replaceAll("\\[.*?\\]", "").trim();
        if (themeExceptions.containsKey(clean)) return themeExceptions.get(clean);
        if (clean.isEmpty()) return "기타";

        String[] words = clean.split("\\s+");
        if (words.length > 0) return words[0];

        return "기타";
    }

    private void loadFromCache() {
        all.clear();
        filtered.clear();
        themes.clear();
        themeToTracks.clear();
        myBestTimes.clear();
        trackChampions.clear();
        trackScroll = 0;
        themeScroll = 0;

        RankingScreen.ApiCache.AllPayload p = RankingScreen.ApiCache.getAllIfReady();
        if (p != null && p.tracks != null) {
            all.addAll(p.tracks);
            all.sort(Comparator.comparingInt(TrackEntry::count).reversed());

            Map<String, Integer> themeTrackCount = new HashMap<>();

            for (TrackEntry te : all) {
                String theme = extractTheme(te.track());
                themeToTracks.computeIfAbsent(theme, k -> new ArrayList<>()).add(te);
                themeTrackCount.put(theme, themeTrackCount.getOrDefault(theme, 0) + 1);
            }

            themes.addAll(themeToTracks.keySet());
            themes.sort((t1, t2) -> Integer.compare(themeTrackCount.get(t2), themeTrackCount.get(t1)));

            themes.add(0, "전체");
            themeToTracks.put("전체", new ArrayList<>(all));

            if (this.initialTrack != null && !this.initialTrack.isBlank()) {
                String targetTheme = extractTheme(this.initialTrack);
                if (themes.contains(targetTheme)) {
                    this.selectedTheme = targetTheme;
                    int themeIdx = themes.indexOf(selectedTheme);
                    int listTop = 10 + 58;
                    int listH = Math.max(80, this.height - 46 - listTop);
                    int visibleThemes = (listH - 8) / 18;
                    int maxThemeScroll = Math.max(0, themes.size() - visibleThemes);
                    this.themeScroll = Math.max(0, Math.min(themeIdx - (visibleThemes / 2), maxThemeScroll));
                }

                List<TrackEntry> currentList = themeToTracks.getOrDefault(selectedTheme, all);
                int trackIndex = -1;
                for (int i = 0; i < currentList.size(); i++) {
                    if (currentList.get(i).track().equals(this.initialTrack)) {
                        trackIndex = i;
                        break;
                    }
                }
                if (trackIndex >= 0) {
                    int themeListW = 100;
                    int trackListX = 12 + themeListW + 8;
                    int trackListW = this.width - 12 - trackListX;
                    int padding = 6;
                    int cardH = 64;
                    int listTop = 10 + 58;
                    int listH = Math.max(80, this.height - 46 - listTop);

                    int cols = Math.max(1, (trackListW - padding * 2 - 10) / 140);
                    int targetRow = trackIndex / cols;
                    int visibleRows = (listH - padding * 2) / (cardH + padding);
                    int totalRows = (currentList.size() + cols - 1) / cols;
                    int maxTrackScroll = Math.max(0, totalRows - visibleRows);

                    this.trackScroll = Math.max(0, Math.min(targetRow, maxTrackScroll));
                }
            }

            if (p.rankingsByTrack != null) {
                String myName = this.client != null && this.client.getSession() != null ? this.client.getSession().getUsername() : "";
                for (var entry : p.rankingsByTrack.entrySet()) {
                    String trackName = entry.getKey();
                    long minMs = Long.MAX_VALUE;
                    String bestStr = "없음";
                    ChampionInfo champ = null;

                    if (entry.getValue().ranking != null && !entry.getValue().ranking.isEmpty()) {
                        RankingScreen.Entry first = entry.getValue().ranking.get(0);
                        champ = new ChampionInfo(first.player(), first.timeStr());

                        for (RankingScreen.Entry e : entry.getValue().ranking) {
                            if (e.player() != null && e.player().equalsIgnoreCase(myName)) {
                                if (e.timeMillis() < minMs) {
                                    minMs = e.timeMillis();
                                    bestStr = e.timeStr();
                                }
                            }
                        }
                    }
                    myBestTimes.put(trackName, bestStr);
                    if (champ != null) {
                        trackChampions.put(trackName, champ);
                    }
                }
            }

            applySearch();
        }
    }

    private String trimWithEllipsis(String text, int maxWidth) {
        if (maxWidth <= 0) return "";
        if (this.textRenderer.getWidth(text) <= maxWidth) return text;
        int ellipsisWidth = this.textRenderer.getWidth("...");
        if (maxWidth <= ellipsisWidth) return "";
        return this.textRenderer.trimToWidth(Text.literal(text), maxWidth - ellipsisWidth).getString() + "...";
    }

    @Override
    public void close() {
        if (this.client != null) this.client.setScreen(parent);
    }

    private void applySearch() {
        filtered.clear();
        List<TrackEntry> source = themeToTracks.getOrDefault(selectedTheme, all);
        String q = searchBox == null ? "" : searchBox.getText().trim().toLowerCase();

        if (q.isEmpty()) {
            filtered.addAll(source);
        } else {
            for (TrackEntry e : source) {
                if (e.track().toLowerCase().contains(q)) filtered.add(e);
            }
        }
        trackScroll = 0;
    }

    @Override
    public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
        context.fill(0, 0, this.width, this.height, ModConfig.get().getBgColor());
    }

    private void drawRectBorder(DrawContext context, int x, int y, int w, int h, int color) {
        context.fill(x, y, x + w, y + 1, color);
        context.fill(x, y + h - 1, x + w, y + h, color);
        context.fill(x, y, x + 1, y + h, color);
        context.fill(x + w - 1, y, x + w, y + h, color);
    }

    // ★ 긴 텍스트 마퀴(스크롤) 애니메이션 렌더링 헬퍼
    private void drawScrollingText(DrawContext context, String text, int x, int y, int maxW, int color, float scale) {
        int rawTextW = this.textRenderer.getWidth(text);
        int scaledTextW = (int)(rawTextW * scale);

        if (scaledTextW > maxW) {
            int overflow = scaledTextW - maxW;
            long time = System.currentTimeMillis() / 20L;
            int cycleLength = overflow + 100;
            int cyclePos = (int) (time % (cycleLength * 2));
            int offset = 0;

            if (cyclePos < cycleLength) {
                offset = Math.max(0, cyclePos - 50);
                if (offset > overflow) offset = overflow;
            } else {
                offset = Math.max(0, (cycleLength * 2 - cyclePos) - 50);
                if (offset > overflow) offset = overflow;
            }

            context.enableScissor(x, y, x + maxW, y + (int)(this.textRenderer.fontHeight * scale) + 4);
            context.getMatrices().push();
            context.getMatrices().translate(x - offset, y, 0);
            context.getMatrices().scale(scale, scale, 1.0f);
            context.drawTextWithShadow(this.textRenderer, text, 0, 0, color);
            context.getMatrices().pop();
            context.disableScissor();
        } else {
            context.getMatrices().push();
            context.getMatrices().translate(x, y, 0);
            context.getMatrices().scale(scale, scale, 1.0f);
            context.drawTextWithShadow(this.textRenderer, text, 0, 0, color);
            context.getMatrices().pop();
        }
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackground(context, mouseX, mouseY, delta);
        int cx = this.width / 2;
        int headerX = OUTER_PAD;
        int headerY = HEADER_TOP;
        int headerW = this.width - OUTER_PAD * 2;
        int headerH = 52;

        context.fill(headerX, headerY, headerX + headerW, headerY + headerH, 0xCC000000);
        drawRectBorder(context, headerX, headerY, headerW, headerH, 0xFF2A2A2A);
        context.drawCenteredTextWithShadow(this.textRenderer, "트랙 선택", cx, headerY + 8, 0xFFFFFF);

        int listTop = HEADER_TOP + 58;
        int listH = Math.max(80, this.height - 46 - listTop);

        // ================= 왼쪽 테마 목록 박스 =================
        int themeListX = OUTER_PAD;
        int themeListW = 100;
        context.fill(themeListX, listTop, themeListX + themeListW, listTop + listH, 0x55000000);
        drawRectBorder(context, themeListX, listTop, themeListW, listH, 0xFF222222);

        int visibleThemes = (listH - 8) / 18;
        int maxThemeScroll = Math.max(0, themes.size() - visibleThemes);
        if (themeScroll > maxThemeScroll) themeScroll = maxThemeScroll;

        int ty = listTop + 4;
        for (int i = themeScroll; i < Math.min(themeScroll + visibleThemes, themes.size()); i++) {
            String theme = themes.get(i);
            boolean isSelected = theme.equals(selectedTheme);
            boolean hover = mouseX >= themeListX && mouseX <= themeListX + themeListW && mouseY >= ty && mouseY < ty + 18;

            if (isSelected) context.fill(themeListX + 1, ty, themeListX + themeListW - 1, ty + 18, 0xFF333333);
            else if (hover) context.fill(themeListX + 1, ty, themeListX + themeListW - 1, ty + 18, 0xFF222222);

            int color = isSelected ? 0xFF55FF55 : (hover ? HOVER_YELLOW : 0xFFDDDDDD);
            context.drawTextWithShadow(this.textRenderer, theme, themeListX + 10, ty + 5, color);
            ty += 18;
        }

        if (maxThemeScroll > 0) {
            int barH = listH - 8;
            int thumbH = Math.max(10, (int)(barH * ((float)visibleThemes / themes.size())));
            int thumbY = listTop + 4 + (int)((barH - thumbH) * ((float)themeScroll / maxThemeScroll));
            context.fill(themeListX + themeListW - 4, listTop + 4, themeListX + themeListW - 2, listTop + 4 + barH, 0x33FFFFFF);
            context.fill(themeListX + themeListW - 4, thumbY, themeListX + themeListW - 2, thumbY + thumbH, 0xAAFFFFFF);
        }

        // ================= 오른쪽 트랙 카드 그리드 박스 =================
        int trackListX = themeListX + themeListW + 8;
        int trackListW = this.width - OUTER_PAD - trackListX;
        int trackListCx = trackListX + trackListW / 2;

        context.fill(trackListX, listTop, trackListX + trackListW, listTop + listH, 0x66000000);
        drawRectBorder(context, trackListX, listTop, trackListW, listH, 0xFF222222);

        if (loading) {
            context.drawCenteredTextWithShadow(this.textRenderer, "불러오는 중...", trackListCx, listTop + 24, 0xFFFFFF);
            super.render(context, mouseX, mouseY, delta);
            return;
        }
        if (error != null) {
            String displayError = error.toLowerCase().contains("http") ? "이 버전은 서비스 종료 되었습니다. 최신 버전을 이용해 주세요." : error;
            context.drawCenteredTextWithShadow(this.textRenderer, "오류: " + displayError, trackListCx, listTop + 24, 0xFF5555);
            super.render(context, mouseX, mouseY, delta);
            return;
        }
        if (all.isEmpty() || filtered.isEmpty()) {
            context.drawCenteredTextWithShadow(this.textRenderer, "트랙이 없습니다.", trackListCx, listTop + 24, 0xFFFFFF);
            super.render(context, mouseX, mouseY, delta);
            return;
        }

        int padding = 6;
        int cardH = 64;
        int cols = Math.max(1, (trackListW - padding * 2 - 10) / 140);
        int cardW = (trackListW - padding * 2 - (cols - 1) * padding - 10) / cols;

        int visibleRows = (listH - padding * 2) / (cardH + padding);
        int totalRows = (filtered.size() + cols - 1) / cols;
        int maxTrackScroll = Math.max(0, totalRows - visibleRows);

        if (trackScroll > maxTrackScroll) trackScroll = maxTrackScroll;

        for (int row = 0; row < visibleRows; row++) {
            for (int col = 0; col < cols; col++) {
                int index = (trackScroll + row) * cols + col;
                if (index >= filtered.size()) continue;

                TrackEntry te = filtered.get(index);
                String bestTime = myBestTimes.getOrDefault(te.track(), "없음");
                ChampionInfo champ = trackChampions.get(te.track());

                int cX = trackListX + padding + col * (cardW + padding);
                int cY = listTop + padding + row * (cardH + padding);

                boolean hover = mouseX >= cX && mouseX <= cX + cardW && mouseY >= cY && mouseY <= cY + cardH;
                boolean isCur = te.track().equals(initialTrack);

                int bgColor = hover ? 0x88333333 : 0x88111111;
                int borderColor = isCur ? 0xFF55FF55 : (hover ? HOVER_YELLOW : 0xFF444444);
                context.fill(cX, cY, cX + cardW, cY + cardH, bgColor);
                drawRectBorder(context, cX, cY, cardW, cardH, borderColor);

                int maxTextW = cardW - 10;

                // ★ 1. 트랙 이름 표시 (1.1배 크기)
                int trackColor = isCur ? 0xFF55FF55 : (hover ? HOVER_YELLOW : 0xFFFFFFFF);
                drawScrollingText(context, te.track(), cX + 5, cY + 5, maxTextW, trackColor, 1.1f);

                // ★ 2. 챔피언 정보 표시 (골드 색상)
                int lineY = cY + 22;
                if (champ != null) {
                    String champLine = "👑 §6" + champ.name() + " §f(" + champ.timeStr() + ")";
                    drawScrollingText(context, champLine, cX + 5, lineY, maxTextW, 0xFFFFFF, 1.0f);
                } else {
                    drawScrollingText(context, "👑 챔피언 없음", cX + 5, lineY, maxTextW, 0xAAAAAA, 1.0f);
                }

                // ★ 3. 일반 정보 (나의 베스트)
                lineY += 14;
                drawScrollingText(context, "나의 베스트: §e" + bestTime, cX + 5, lineY, maxTextW, 0xAAAAAA, 1.0f);

                // ★ 4. 일반 정보 (기록 수)
                lineY += 12;
                drawScrollingText(context, "기록 수: " + te.count() + "개", cX + 5, lineY, maxTextW, 0xAAAAAA, 1.0f);
            }
        }

        if (maxTrackScroll > 0) {
            int barH = listH - padding * 2;
            int thumbH = Math.max(10, (int)(barH * ((float)visibleRows / totalRows)));
            int thumbY = listTop + padding + (int)((barH - thumbH) * ((float)trackScroll / maxTrackScroll));
            int barX = trackListX + trackListW - 8;

            context.fill(barX, listTop + padding, barX + 4, listTop + padding + barH, 0x33FFFFFF);
            context.fill(barX, thumbY, barX + 4, thumbY + thumbH, 0xAAFFFFFF);
        }

        super.render(context, mouseX, mouseY, delta);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        int listTop = HEADER_TOP + 58;
        int listH = Math.max(80, this.height - 46 - listTop);

        int themeListX = OUTER_PAD;
        int themeListW = 100;
        if (mouseX >= themeListX && mouseX <= themeListX + themeListW && mouseY >= listTop && mouseY <= listTop + listH) {
            int visibleThemes = (listH - 8) / 18;
            int maxThemeScroll = Math.max(0, themes.size() - visibleThemes);
            if (verticalAmount > 0) themeScroll--;
            else if (verticalAmount < 0) themeScroll++;

            if (themeScroll < 0) themeScroll = 0;
            if (themeScroll > maxThemeScroll) themeScroll = maxThemeScroll;
            return true;
        }

        int trackListX = themeListX + themeListW + 8;
        int trackListW = this.width - OUTER_PAD - trackListX;
        if (mouseX >= trackListX && mouseX <= trackListX + trackListW && mouseY >= listTop && mouseY <= listTop + listH) {
            int padding = 6;
            int cardH = 64;
            int cols = Math.max(1, (trackListW - padding * 2 - 10) / 140);
            int visibleRows = (listH - padding * 2) / (cardH + padding);
            int totalRows = (filtered.size() + cols - 1) / cols;
            int maxTrackScroll = Math.max(0, totalRows - visibleRows);

            if (verticalAmount > 0) trackScroll--;
            else if (verticalAmount < 0) trackScroll++;

            if (trackScroll < 0) trackScroll = 0;
            if (trackScroll > maxTrackScroll) trackScroll = maxTrackScroll;
            return true;
        }

        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        int listTop = HEADER_TOP + 58;
        int listH = Math.max(80, this.height - 46 - listTop);

        int themeListX = OUTER_PAD;
        int themeListW = 100;
        int visibleThemes = (listH - 8) / 18;
        int ty = listTop + 4;

        for (int i = themeScroll; i < Math.min(themeScroll + visibleThemes, themes.size()); i++) {
            if (mouseY >= ty && mouseY < ty + 18 && mouseX >= themeListX && mouseX <= themeListX + themeListW) {
                playUiClick();
                selectedTheme = themes.get(i);
                trackScroll = 0;
                applySearch();
                return true;
            }
            ty += 18;
        }

        int trackListX = themeListX + themeListW + 8;
        int trackListW = this.width - OUTER_PAD - trackListX;
        int padding = 6;
        int cardH = 64;
        int cols = Math.max(1, (trackListW - padding * 2 - 10) / 140);
        int cardW = (trackListW - padding * 2 - (cols - 1) * padding - 10) / cols;
        int visibleRows = (listH - padding * 2) / (cardH + padding);

        for (int row = 0; row < visibleRows; row++) {
            for (int col = 0; col < cols; col++) {
                int index = (trackScroll + row) * cols + col;
                if (index >= filtered.size()) continue;

                int cX = trackListX + padding + col * (cardW + padding);
                int cY = listTop + padding + row * (cardH + padding);

                if (mouseX >= cX && mouseX <= cX + cardW && mouseY >= cY && mouseY <= cY + cardH) {
                    playUiClick();
                    String selectedTrack = filtered.get(index).track();
                    if (onSelectTrack != null) onSelectTrack.accept(selectedTrack);
                    close();
                    return true;
                }
            }
        }

        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    public static record TrackEntry(String track, int count) {}
}