package com.example.rankinglog;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;

import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

public class PlayerProfileScreen extends Screen {

    private final String playerName;
    private final Screen parent;
    private final List<RecordRow> records = new ArrayList<>();

    private boolean loading = true;
    private String error = null;

    // ★ 페이지네이션 대신 스크롤 상태 사용
    private int tableScroll = 0;

    private ButtonWidget backBtn, refreshBtn, globeBtn;

    private static final int OUTER_PAD = 12, HEADER_TOP = 10, ROW_H = 18;
    private static final int HEADER_H = 85;
    private static final int HOVER_YELLOW = 0xFFFFEE88;

    private boolean isMe;
    private boolean isEditing = false;
    private String profileDescription = "";

    private boolean showAchvList = false;
    private int achvListScroll = 0;
    private ButtonWidget achvListBtn;
    private ButtonWidget achvListCloseBtn;
    private TextFieldWidget achvSearchBox;
    private static final int ACHV_CARD_H = 45;

    private int achvFilterStatus = 0;
    private int achvFilterType = 0;

    private Achievement selectedDetailAchievement = null;

    private SharedSidebar sharedSidebar;

    public record Achievement(String id, String full, String simple, String desc, String color, int targetMs, int targetCount, String targetTrack, String targetKart) {}

    private final List<Achievement> profileAchievements = new ArrayList<>();
    private final List<Achievement> missingAchievements = new ArrayList<>();
    private String repAchieveId = "";
    private String repAchieveSimple = "";
    private String repAchieveColor = "#55FFFF";

    // ★ 디테일 레코드 및 아코디언용 상태
    private RecordRow selectedDetailRecord = null;
    private TextFieldWidget descField;
    private ButtonWidget editBtn, saveBtn;

    // ★ 스탯 정보 토글용
    private boolean kartSpecExpanded = false;
    private int specBtnScreenX, specBtnScreenY, specBtnScreenW, specBtnScreenH;

    public PlayerProfileScreen(String playerName, Screen parent) {
        super(Text.literal("프로필"));
        this.playerName = (playerName == null) ? "" : playerName.trim();
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

    private int parseHex(String hex, int fallback) {
        if (hex == null || hex.isEmpty()) return fallback;
        try { if (hex.startsWith("#")) hex = hex.substring(1); return 0xFF000000 | Integer.parseInt(hex, 16); } catch (Exception e) { return fallback; }
    }

    private void playUiClick() { if (this.client != null) this.client.getSoundManager().play(net.minecraft.client.sound.PositionedSoundInstance.master(net.minecraft.sound.SoundEvents.UI_BUTTON_CLICK, 1.0f)); }

    private String trimWithEllipsis(String text, int maxWidth) {
        if (maxWidth <= 0) return "";
        if (this.textRenderer.getWidth(text) <= maxWidth) return text;
        int ellipsisWidth = this.textRenderer.getWidth("...");
        if (maxWidth <= ellipsisWidth) return "";
        return this.textRenderer.trimToWidth(Text.literal(text), maxWidth - ellipsisWidth).getString() + "...";
    }

    // ★ 버튼 충돌 체크 헬퍼
    private boolean isInside(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx <= x + w && my >= y && my <= y + h;
    }

    @Override
    public void close() { if (this.client != null) this.client.setScreen(null); }

    @Override
    protected void init() {
        if (isNewUi()) {
            sharedSidebar = new SharedSidebar(
                    this, "PROFILE", null, false,
                    catId -> {
                        if (this.client != null) {
                            this.client.setScreen(new TmiRankingScreen(new MainMenuScreen(), catId));
                        }
                    },
                    () -> loadAllData(true),
                    this::repositionUiElements
            );
        }

        int iconBtnSize = 20;
        String myName = this.client != null && this.client.getSession() != null ? this.client.getSession().getUsername() : ""; this.isMe = this.playerName.equalsIgnoreCase(myName);

        backBtn = ButtonWidget.builder(Text.literal("⏴"), b -> { playUiClick(); this.close(); }).dimensions(0, 0, iconBtnSize, 20).tooltip(net.minecraft.client.gui.tooltip.Tooltip.of(Text.literal("뒤로 가기"))).build(); addDrawableChild(backBtn);
        refreshBtn = ButtonWidget.builder(Text.literal("🔄"), b -> { playUiClick(); loadAllData(true); }).dimensions(0, 0, iconBtnSize, 20).tooltip(net.minecraft.client.gui.tooltip.Tooltip.of(Text.literal("새로 고침"))).build(); addDrawableChild(refreshBtn);

        globeBtn = ButtonWidget.builder(Text.literal("🌐"), b -> {
            playUiClick();
            if (this.client != null) this.client.setScreen(new ServerSelectModalScreen(this));
        }).dimensions(0, 0, 28, 20).tooltip(net.minecraft.client.gui.tooltip.Tooltip.of(Text.literal("서버 변경"))).build();
        addDrawableChild(globeBtn);

        descField = new TextFieldWidget(this.textRenderer, 0, 0, 100, 16, Text.literal("자기 소개"));
        descField.setMaxLength(60); descField.setVisible(false); addDrawableChild(descField);

        achvSearchBox = new TextFieldWidget(this.textRenderer, 0, 0, 100, 16, Text.literal("업적 검색"));
        achvSearchBox.setMaxLength(30); achvSearchBox.setVisible(false);
        achvSearchBox.setChangedListener(s -> { achvListScroll = 0; });
        addDrawableChild(achvSearchBox);

        editBtn = ButtonWidget.builder(Text.literal("수정"), b -> { playUiClick(); isEditing = true; descField.setText(profileDescription); updateProfileUIVisibility(); }).dimensions(0, 0, 36, 16).build(); addDrawableChild(editBtn);
        saveBtn = ButtonWidget.builder(Text.literal("저장"), b -> { playUiClick(); saveProfileDescription(descField.getText()); }).dimensions(0, 0, 36, 16).build(); addDrawableChild(saveBtn);

        achvListBtn = ButtonWidget.builder(Text.literal("업적 목록"), b -> {
            playUiClick(); showAchvList = true; achvListScroll = 0; selectedDetailRecord = null; updateProfileUIVisibility();
        }).dimensions(0, 0, 70, 16).build(); addDrawableChild(achvListBtn);

        achvListCloseBtn = ButtonWidget.builder(Text.literal("X"), b -> {
            playUiClick(); showAchvList = false; selectedDetailAchievement = null; updateProfileUIVisibility();
        }).dimensions(0, 0, 20, 20).build(); achvListCloseBtn.visible = false; addDrawableChild(achvListCloseBtn);

        repositionUiElements();
        updateProfileUIVisibility();

        if (!RankingScreen.ApiCache.isAllReady()) {
            loadAllData(false);
        } else if (loading && records.isEmpty() && profileAchievements.isEmpty()) {
            loadAllData(false);
        }
    }

    private void repositionUiElements() {
        int effectiveLeftW = getEffectiveSidebarWidth();
        int rightAreaX = effectiveLeftW == 0 ? OUTER_PAD : OUTER_PAD + effectiveLeftW + 8;
        int rightAreaW = this.width - OUTER_PAD - rightAreaX;
        int cx = rightAreaX + rightAreaW / 2;
        int cy = this.height / 2;
        int bottomY = this.height - 28;
        int iconBtnSize = 20;

        if (backBtn != null) backBtn.setPosition(rightAreaX, bottomY);
        if (refreshBtn != null) refreshBtn.setPosition(rightAreaX + rightAreaW - iconBtnSize, bottomY);
        if (globeBtn != null) globeBtn.setPosition(rightAreaX + rightAreaW - iconBtnSize - 32, bottomY);

        int splitX = rightAreaX + (int)(rightAreaW * 0.25);
        int rightPadX = splitX + 10;
        int rightPadY = HEADER_TOP + 8;
        int maxDescWidth = rightAreaW - (splitX - rightAreaX) - 20;

        if (descField != null) { descField.setX(rightPadX); descField.setY(rightPadY + 11); descField.setWidth(maxDescWidth); }
        if (editBtn != null) { editBtn.setPosition(rightPadX + 75, rightPadY - 4); }
        if (saveBtn != null) { saveBtn.setPosition(rightPadX + 75, rightPadY - 4); }

        if (achvListBtn != null) { achvListBtn.setPosition(rightPadX + 60, rightPadY + 30); }

        if (achvListCloseBtn != null) {
            int tableX = rightAreaX + 8;
            int tableY = HEADER_TOP + HEADER_H + 10;
            int tableW = rightAreaW - 16;
            achvListCloseBtn.setPosition(tableX + tableW - 24, tableY + 4);

            if (achvSearchBox != null) {
                int searchW = Math.max(60, Math.min(140, tableW / 3));
                achvSearchBox.setX(tableX + tableW - 28 - searchW);
                achvSearchBox.setY(tableY + 6);
                achvSearchBox.setWidth(searchW);
            }
        }
    }

    private void loadAllData(boolean forceRefreshCache) {
        loading = true; error = null; records.clear(); profileAchievements.clear(); missingAchievements.clear();
        repAchieveId = ""; repAchieveSimple = ""; repAchieveColor = "#55FFFF"; showAchvList = false; tableScroll = 0;
        selectedDetailRecord = null; selectedDetailAchievement = null; kartSpecExpanded = false;
        updateProfileUIVisibility();

        AtomicInteger tasksCompleted = new AtomicInteger(0);

        new Thread(() -> {
            try {
                JsonObject req = new JsonObject(); req.addProperty("p_player", this.playerName);
                JsonObject obj = RankingScreen.Net.postJson(RankingScreen.SUPABASE_RPC_URL + "get_profile", req.toString());

                if (obj.has("ok") && obj.get("ok").getAsBoolean()) {
                    profileDescription = obj.has("description") ? obj.get("description").getAsString() : "";
                    repAchieveId = obj.has("repId") ? obj.get("repId").getAsString() : "";
                    repAchieveSimple = obj.has("repSimple") ? obj.get("repSimple").getAsString() : "";
                    repAchieveColor = obj.has("repColor") ? obj.get("repColor").getAsString() : "#55FFFF";

                    if (obj.has("achievements")) {
                        for (JsonElement elem : obj.getAsJsonArray("achievements")) {
                            JsonObject a = elem.getAsJsonObject();
                            profileAchievements.add(new Achievement(
                                    a.get("id").getAsString(), a.get("full").getAsString(), a.get("simple").getAsString(),
                                    a.has("desc") ? a.get("desc").getAsString() : "설명이 없습니다.",
                                    a.has("color") ? a.get("color").getAsString() : "#55FFFF",
                                    a.has("targetMs") ? a.get("targetMs").getAsInt() : 0,
                                    a.has("targetCount") ? a.get("targetCount").getAsInt() : 0,
                                    a.has("targetTrack") ? a.get("targetTrack").getAsString() : "",
                                    a.has("targetKart") ? a.get("targetKart").getAsString() : ""
                            ));
                        }
                    }
                    if (obj.has("missing")) {
                        for (JsonElement elem : obj.getAsJsonArray("missing")) {
                            JsonObject a = elem.getAsJsonObject();
                            missingAchievements.add(new Achievement(
                                    a.get("id").getAsString(), a.get("full").getAsString(), a.get("simple").getAsString(),
                                    a.has("desc") ? a.get("desc").getAsString() : "설명이 없습니다.",
                                    a.has("color") ? a.get("color").getAsString() : "#55FFFF",
                                    a.has("targetMs") ? a.get("targetMs").getAsInt() : 0,
                                    a.has("targetCount") ? a.get("targetCount").getAsInt() : 0,
                                    a.has("targetTrack") ? a.get("targetTrack").getAsString() : "",
                                    a.has("targetKart") ? a.get("targetKart").getAsString() : ""
                            ));
                        }
                    }
                }
            } catch (Exception e) { e.printStackTrace(); } finally { checkCompletion(tasksCompleted); }
        }).start();

        Runnable onCacheDone = () -> { loadRecordsFromCache(); checkCompletion(tasksCompleted); };
        if (forceRefreshCache || !RankingScreen.ApiCache.isAllReady()) { RankingScreen.ApiCache.fetchAllAsync(true, p -> onCacheDone.run(), err -> { error = err; checkCompletion(tasksCompleted); }); } else { onCacheDone.run(); }
    }

    private void checkCompletion(AtomicInteger tasksCompleted) {
        if (tasksCompleted.incrementAndGet() >= 2) {
            if (this.client != null) { this.client.execute(() -> { loading = false; updateProfileUIVisibility(); }); }
        }
    }

    private void updateProfileUIVisibility() {
        repositionUiElements();

        if (editBtn != null) editBtn.visible = isMe && !isEditing && !loading;
        if (saveBtn != null) saveBtn.visible = isMe && isEditing && !loading;
        if (descField != null) descField.setVisible(isEditing);

        if (achvListBtn != null) achvListBtn.visible = !loading && (!profileAchievements.isEmpty() || !missingAchievements.isEmpty()) && !showAchvList;
        if (achvListCloseBtn != null) achvListCloseBtn.visible = showAchvList;
        if (achvSearchBox != null) achvSearchBox.setVisible(showAchvList);

        if (backBtn != null) backBtn.visible = true;
        if (refreshBtn != null) refreshBtn.visible = true;
        if (globeBtn != null) globeBtn.visible = true;
    }

    private void saveRepAchieve(String targetId) {
        loading = true; updateProfileUIVisibility();
        new Thread(() -> {
            try {
                var session = net.minecraft.client.MinecraftClient.getInstance().getSession();
                JsonObject req = new JsonObject();
                req.addProperty("action", "update_rep_achieve");
                req.addProperty("p_player", this.playerName);
                req.addProperty("p_rep_id", targetId);
                req.addProperty("p_token", session.getAccessToken());

                JsonObject obj = RankingScreen.Net.postJson("https://wmlcwmfabuziancpxdoq.supabase.co/functions/v1/swift-responder", req.toString());

                if (obj.has("ok") && obj.get("ok").getAsBoolean()) {
                    repAchieveId = targetId;
                    if (targetId.isEmpty()) { repAchieveSimple = ""; repAchieveColor = "#55FFFF"; }
                    else { for (Achievement a : profileAchievements) { if (a.id().equals(targetId)) { repAchieveSimple = a.simple(); repAchieveColor = a.color(); break; } } }
                }
            } catch (Exception e) { e.printStackTrace(); } finally { if (this.client != null) { this.client.execute(() -> { loading = false; updateProfileUIVisibility(); }); } }
        }).start();
    }

    private void saveProfileDescription(String newDesc) {
        loading = true; isEditing = false; updateProfileUIVisibility();
        new Thread(() -> {
            try {
                var session = net.minecraft.client.MinecraftClient.getInstance().getSession();
                JsonObject req = new JsonObject();
                req.addProperty("action", "update_profile");
                req.addProperty("p_player", this.playerName);
                req.addProperty("p_desc", newDesc);
                req.addProperty("p_token", session.getAccessToken());

                JsonObject obj = RankingScreen.Net.postJson("https://wmlcwmfabuziancpxdoq.supabase.co/functions/v1/swift-responder", req.toString());
                if (obj.has("ok") && obj.get("ok").getAsBoolean()) { profileDescription = newDesc; }
            } catch (Exception e) { e.printStackTrace(); } finally { if (this.client != null) { this.client.execute(() -> { loading = false; updateProfileUIVisibility(); }); } }
        }).start();
    }

    private void loadRecordsFromCache() {
        records.clear(); RankingScreen.ApiCache.AllPayload all = RankingScreen.ApiCache.getAllIfReady(); if (all == null) return;
        for (var entry : all.rankingsByTrack.entrySet()) {
            String track = entry.getKey();
            for (RankingScreen.Entry e : entry.getValue().ranking) {
                if (e.player() == null) continue;
                if (e.player().equalsIgnoreCase(playerName)) {
                    records.add(new RecordRow(
                            e.submittedAtMs(), track, safeText(e.timeStr(), "??:??.???"), e.timeMillis(),
                            safeText(e.bodyName(), "UNKNOWN"), safeText(e.tireName(), "UNKNOWN"),
                            safeText(e.engineName(), "UNKNOWN"), safeText(e.modes(), "없음"),
                            e.serverAddress(), e.kartSpecDebug()
                    ));
                }
            }
        }
        records.sort(Comparator.comparingLong(RecordRow::submittedAtMs).reversed()); tableScroll = 0;
    }

    private static String safeText(String s, String def) { if (s == null) return def; String t = s.trim(); return t.isBlank() ? def : t; }

    @Override
    public void resize(MinecraftClient client, int width, int height) {
        super.resize(client, width, height);
        updateProfileUIVisibility();
    }

    private int getCardHeight(Achievement ach, int tableW) {
        if (selectedDetailAchievement != ach) return ACHV_CARD_H;

        int extraH = 5;
        if (this.textRenderer != null) {
            List<OrderedText> lines = this.textRenderer.wrapLines(Text.literal(ach.desc()), tableW - 30);
            extraH += lines.size() * 14;
        }
        extraH += 5;
        if (ach.targetMs() > 0) extraH += 14;
        if (ach.targetCount() > 0) extraH += 14;
        if (ach.targetTrack() != null && !ach.targetTrack().isEmpty()) extraH += 14;
        if (ach.targetKart() != null && !ach.targetKart().isEmpty()) extraH += 14;
        extraH += 15;

        return ACHV_CARD_H + extraH;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (isNewUi() && sharedSidebar != null && sharedSidebar.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount)) {
            return true;
        }

        int effectiveLeftW = getEffectiveSidebarWidth();
        int rightAreaX = effectiveLeftW == 0 ? OUTER_PAD : OUTER_PAD + effectiveLeftW + 8;
        int rightAreaW = this.width - OUTER_PAD - rightAreaX;
        int tableY = HEADER_TOP + HEADER_H + 10;
        int tableW = rightAreaW - 16;
        int tableX = rightAreaX + 8;
        int tableBottom = this.height - 46;
        int tableH = Math.max(120, tableBottom - tableY);

        if (showAchvList) {
            int listH = tableH - 60;
            if (mouseX >= tableX && mouseX <= tableX + tableW && mouseY >= tableY && mouseY <= tableBottom) {
                List<Achievement> displayAchvs = getFilteredAchievements();
                int totalH = 0;
                for (Achievement ach : displayAchvs) {
                    totalH += getCardHeight(ach, tableW) + 6;
                }

                int maxScroll = Math.max(0, totalH - listH);

                if (verticalAmount > 0) achvListScroll -= 20;
                else if (verticalAmount < 0) achvListScroll += 20;

                if (achvListScroll < 0) achvListScroll = 0;
                if (achvListScroll > maxScroll) achvListScroll = maxScroll;
                return true;
            }
        } else if (!loading && error == null && !records.isEmpty()) {
            if (mouseX >= tableX && mouseX <= tableX + tableW && mouseY >= tableY && mouseY <= tableBottom) {

                // ★ 펼쳐진 항목 높이 스크롤 한계 보정
                int extraH = 0;
                if (selectedDetailRecord != null) {
                    extraH += 16 * 2;
                    boolean showTime = tableW > 250;
                    boolean showBody = tableW > 320;
                    boolean showEngine = tableW > 380;

                    int hiddenCount = 0;
                    if (!showTime) hiddenCount++;
                    if (!showBody) hiddenCount++;
                    if (!showEngine) hiddenCount++;
                    if (hiddenCount > 0) extraH += hiddenCount * 16 + 5;

                    boolean isSingle = "__singleplay__".equals(selectedDetailRecord.serverAddress());
                    if (isSingle && kartSpecExpanded) {
                        String specRaw = selectedDetailRecord.kartSpecDebug();
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
                    extraH += 10; // 여백 추가
                }

                int adjustedCapacityRows = Math.max(1, (tableH - 24 - extraH) / ROW_H) + 1;
                int maxScroll = Math.max(0, records.size() - adjustedCapacityRows + 1); // ★ 마진 1행 추가

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

        int effectiveLeftW = getEffectiveSidebarWidth();
        int rightAreaX = effectiveLeftW == 0 ? OUTER_PAD : OUTER_PAD + effectiveLeftW + 8;
        int rightAreaW = this.width - OUTER_PAD - rightAreaX;
        int tableY = HEADER_TOP + HEADER_H + 10;
        int tableX = rightAreaX + 8;
        int tableW = rightAreaW - 16;

        if (showAchvList && !loading && error == null) {

            if (achvSearchBox != null && achvSearchBox.isMouseOver(mouseX, mouseY)) {
                achvSearchBox.mouseClicked(mouseX, mouseY, button);
                achvSearchBox.setFocused(true);
                this.setFocused(achvSearchBox);
                return true;
            }

            int currentX = tableX + 10;
            int row1Y = tableY + 8;

            String[] t1Labels = {"전체 (" + (profileAchievements.size() + missingAchievements.size()) + ")",
                    "달성 (" + profileAchievements.size() + ")",
                    "미달성 (" + missingAchievements.size() + ")"};
            for (int i = 0; i < 3; i++) {
                int tw1 = textRenderer.getWidth(t1Labels[i]) + 16;
                if (mouseX >= currentX && mouseX <= currentX + tw1 && mouseY >= row1Y && mouseY <= row1Y + 16) {
                    achvFilterStatus = i; achvListScroll = 0; playUiClick(); return true;
                }
                currentX += tw1 + 6;
            }

            currentX = tableX + 10;
            int row2Y = tableY + 28;
            String[] t2Labels = {"전체", "기록", "누적", "스페셜"};
            for (int i = 0; i < 4; i++) {
                int tw2 = textRenderer.getWidth(t2Labels[i]) + 16;
                if (mouseX >= currentX && mouseX <= currentX + tw2 && mouseY >= row2Y && mouseY <= row2Y + 16) {
                    achvFilterType = i; achvListScroll = 0; playUiClick(); return true;
                }
                currentX += tw2 + 6;
            }

            int listY = tableY + 54;
            int tableBottom = this.height - 46;
            int tableH = Math.max(120, tableBottom - tableY);
            int listH = tableH - 60;

            if (mouseY >= listY && mouseY <= listY + listH) {
                List<Achievement> displayAchvs = getFilteredAchievements();
                int currentCardY = listY - achvListScroll;

                for (int i = 0; i < displayAchvs.size(); i++) {
                    Achievement ach = displayAchvs.get(i);
                    boolean isAchieved = profileAchievements.contains(ach);
                    int cardH = getCardHeight(ach, tableW);

                    if (mouseY >= currentCardY && mouseY <= currentCardY + cardH) {

                        if (isAchieved && isMe) {
                            int repBtnW = 75;
                            int repBtnH = 14;
                            int repBtnX = tableX + tableW - 25 - repBtnW;
                            int repBtnY = currentCardY + 6;
                            if (mouseX >= repBtnX && mouseX <= repBtnX + repBtnW && mouseY >= repBtnY && mouseY <= repBtnY + repBtnH) {
                                playUiClick();
                                String targetId = ach.id().equals(repAchieveId) ? "" : ach.id();
                                saveRepAchieve(targetId);
                                return true;
                            }
                        }

                        if (mouseX >= tableX + 10 && mouseX <= tableX + tableW - 15) {
                            playUiClick();
                            if (selectedDetailAchievement == ach) {
                                selectedDetailAchievement = null;
                            } else {
                                selectedDetailAchievement = ach;
                            }
                            updateProfileUIVisibility();
                            return true;
                        }
                    }
                    currentCardY += cardH + 6;
                }
            }
        }

        // ★ 랭킹 기록 리스트 (아코디언 클릭 감지)
        if (!loading && !showAchvList && error == null && !records.isEmpty()) {
            int tableBottom = this.height - 46;
            int tableH = Math.max(120, tableBottom - tableY);

            if (mouseX >= tableX && mouseX <= tableX + tableW && mouseY >= tableY + 24 && mouseY <= tableY + tableH) {

                // 카트 스탯 토글 버튼 [+] 클릭 체크
                if (selectedDetailRecord != null && specBtnScreenW > 0) {
                    if (isInside(mouseX, mouseY, specBtnScreenX, specBtnScreenY, specBtnScreenW, specBtnScreenH)) {
                        playUiClick();
                        kartSpecExpanded = !kartSpecExpanded;
                        return true;
                    }
                }

                // ★ 스크롤 한계 보정 후 클릭 갱신
                int extraH = 0;
                if (selectedDetailRecord != null) {
                    extraH += 16 * 2;
                    boolean showTime = tableW > 250;
                    boolean showBody = tableW > 320;
                    boolean showEngine = tableW > 380;

                    int hiddenCount = 0;
                    if (!showTime) hiddenCount++;
                    if (!showBody) hiddenCount++;
                    if (!showEngine) hiddenCount++;
                    if (hiddenCount > 0) extraH += hiddenCount * 16 + 5;

                    boolean isSingle = "__singleplay__".equals(selectedDetailRecord.serverAddress());
                    if (isSingle && kartSpecExpanded) {
                        String specRaw = selectedDetailRecord.kartSpecDebug();
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
                    extraH += 10;
                }

                int adjustedCapacityRows = Math.max(1, (tableH - 24 - extraH) / ROW_H) + 1;
                int maxScroll = Math.max(0, records.size() - adjustedCapacityRows + 1); // ★ 마진 1행 추가

                tableScroll = Math.max(0, Math.min(tableScroll, maxScroll));
                int startY = tableY + 24;
                int start = tableScroll;
                int end = Math.min(start + Math.max(1, (tableH - 24) / ROW_H) + 3, records.size()); // end 넉넉하게 계산

                int currentY = startY;

                for (int i = start; i < end; i++) {
                    RecordRow r = records.get(i);
                    int itemH = ROW_H;

                    // 펼쳐진 항목 높이 계산
                    if (r == selectedDetailRecord) {
                        int expandH = 16 * 2;
                        boolean showTime = tableW > 250;
                        boolean showBody = tableW > 320;
                        boolean showEngine = tableW > 380;

                        int hiddenCount = 0;
                        if (!showTime) hiddenCount++;
                        if (!showBody) hiddenCount++;
                        if (!showEngine) hiddenCount++;
                        if (hiddenCount > 0) expandH += hiddenCount * 16 + 5;

                        boolean isSingle = "__singleplay__".equals(r.serverAddress());
                        if (isSingle && kartSpecExpanded) {
                            String specRaw = r.kartSpecDebug();
                            if (specRaw != null && !specRaw.isEmpty() && !specRaw.equals("없음")) {
                                int lines = 1;
                                if (specRaw.contains("speed")) lines++;
                                if (specRaw.contains("accel") || specRaw.contains("boost:")) lines++;
                                if (specRaw.contains("corner") || specRaw.contains("drift")) lines++;
                                if (specRaw.contains("gauge") || specRaw.contains("boosttime") || specRaw.contains("maxboostcount")) lines++;
                                if (specRaw.contains("defense")) lines++;
                                if (specRaw.contains("draft")) lines++;
                                expandH += lines * 16 + 10;
                            } else {
                                expandH += 16 + 10;
                            }
                        }
                        itemH += expandH + 10;
                    }

                    if (currentY > tableY + tableH) break;

                    if (mouseY >= currentY - 2 && mouseY <= currentY + itemH - 2) {
                        playUiClick();
                        if (selectedDetailRecord == r) {
                            selectedDetailRecord = null;
                            kartSpecExpanded = false;
                        } else {
                            selectedDetailRecord = r;
                            kartSpecExpanded = false;
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
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (showAchvList && achvSearchBox != null && achvSearchBox.keyPressed(keyCode, scanCode, modifiers)) return true;
        if (isEditing && descField != null && descField.keyPressed(keyCode, scanCode, modifiers)) return true;
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char chr, int modifiers) {
        if (showAchvList && achvSearchBox != null && achvSearchBox.charTyped(chr, modifiers)) return true;
        if (isEditing && descField != null && descField.charTyped(chr, modifiers)) return true;
        return super.charTyped(chr, modifiers);
    }

    @Override protected void applyBlur() { }
    @Override public void blur() { }

    @Override
    public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
        context.fill(0, 0, this.width, this.height, ModConfig.get().getBgColor());
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

        int headerX = rightAreaX; int headerY = HEADER_TOP; int headerW = rightAreaW;

        context.fill(headerX, headerY, headerX + headerW, headerY + HEADER_H, 0xCC000000); drawRectBorder(context, headerX, headerY, headerW, HEADER_H, 0xFF2A2A2A);
        int splitX = headerX + (int)(headerW * 0.25); context.fill(splitX, headerY, splitX + 1, headerY + HEADER_H, 0xFF2A2A2A);

        int leftBoxW = splitX - headerX;
        int leftBoxCenterX = headerX + leftBoxW / 2;

        context.getMatrices().push(); context.getMatrices().scale(2.0f, 2.0f, 1.0f); context.drawCenteredTextWithShadow(this.textRenderer, "👤", (int)(leftBoxCenterX / 2.0f), (int)((headerY + 15) / 2.0f), 0xFFFFFF); context.getMatrices().pop();

        int nameW = this.textRenderer.getWidth(playerName);
        int repW = repAchieveSimple.isEmpty() ? 0 : this.textRenderer.getWidth(" [" + repAchieveSimple + "]");
        float nameScale = Math.min(1.0f, (leftBoxW - 10) / (float)(nameW + repW));

        context.getMatrices().push();
        context.getMatrices().translate(leftBoxCenterX, headerY + 45, 0);
        context.getMatrices().scale(nameScale, nameScale, 1.0f);
        if (repAchieveSimple.isEmpty()) {
            context.drawCenteredTextWithShadow(this.textRenderer, playerName, 0, 0, 0xFFDDAA);
        } else {
            int repColorInt = parseHex(repAchieveColor, 0x55FFFF); String repText = " [" + repAchieveSimple + "]";
            int totalW = nameW + repW; int startX = -totalW / 2;
            context.drawTextWithShadow(this.textRenderer, playerName, startX, 0, 0xFFDDAA);
            context.drawTextWithShadow(this.textRenderer, repText, startX + nameW, 0, repColorInt);
        }
        context.getMatrices().pop();

        String sub = (loading) ? "불러오는 중..." : (error != null ? "오류 발생" : "총 기록: " + records.size());
        context.drawCenteredTextWithShadow(this.textRenderer, sub, leftBoxCenterX, headerY + 65, 0xBBBBBB);

        int rightPadX = splitX + 10;
        int rightPadY = headerY + 8;
        int maxDescWidth = headerW - (splitX - headerX) - 20;

        context.drawTextWithShadow(this.textRenderer, "§e[ 자기 소개 ]", rightPadX, rightPadY, 0xFFFFFF);

        if (loading) { context.drawTextWithShadow(this.textRenderer, "불러오는 중...", rightPadX, rightPadY + 14, 0xAAAAAA); }
        else if (!isEditing) {
            String displayDesc = profileDescription.trim().isEmpty() ? "아직 작성된 소개글이 없습니다." : profileDescription;
            context.drawTextWithShadow(this.textRenderer, trimWithEllipsis(displayDesc, maxDescWidth), rightPadX, rightPadY + 14, 0xAAAAAA);
        }

        context.drawTextWithShadow(this.textRenderer, "§b[ 업적 ]", rightPadX, rightPadY + 34, 0xFFFFFF);

        if (loading) { context.drawTextWithShadow(this.textRenderer, "불러오는 중...", rightPadX, rightPadY + 48, 0xAAAAAA); }
        else {
            int currentX = rightPadX; int currentY = rightPadY + 48; int maxRight = headerX + headerW - 10;
            if (profileAchievements.isEmpty()) {
                context.drawTextWithShadow(this.textRenderer, "달성한 업적이 없습니다.", rightPadX, rightPadY + 48, 0xAAAAAA);
            }
            else {
                int displayCount = 0;
                for (int i = 0; i < profileAchievements.size(); i++) {
                    if (displayCount >= 5) { context.drawTextWithShadow(this.textRenderer, "§b+", currentX, currentY, 0xFFFFFF); break; }
                    Achievement ach = profileAchievements.get(i);
                    String text = "[" + ach.full() + "]";

                    int w = this.textRenderer.getWidth(text);
                    if (w > maxRight - rightPadX) {
                        text = trimWithEllipsis(text, maxRight - rightPadX);
                        w = this.textRenderer.getWidth(text);
                    }

                    if (currentX + w > maxRight) { currentX = rightPadX; currentY += 12; }
                    int customColor = parseHex(ach.color(), 0x55FFFF);
                    context.drawTextWithShadow(this.textRenderer, text, currentX, currentY, customColor);
                    currentX += w + 4; displayCount++;
                }
            }
        }

        int tableX = rightAreaX + 8; int tableY = headerY + HEADER_H + 10; int tableW = rightAreaW - 16; int tableBottom = this.height - 46; int tableH = Math.max(120, tableBottom - tableY);

        if (showAchvList) {
            context.fill(tableX, tableY, tableX + tableW, tableY + tableH, 0xEE000000);
            drawRectBorder(context, tableX, tableY, tableW, tableH, 0xFF444444);

            int currentX = tableX + 10;
            int row1Y = tableY + 8;

            String[] t1Labels = {"전체 (" + (profileAchievements.size() + missingAchievements.size()) + ")",
                    "달성 (" + profileAchievements.size() + ")",
                    "미달성 (" + missingAchievements.size() + ")"};
            for (int i = 0; i < 3; i++) {
                int tw1 = textRenderer.getWidth(t1Labels[i]) + 16;
                boolean isHover = mouseX >= currentX && mouseX <= currentX + tw1 && mouseY >= row1Y && mouseY <= row1Y + 16;
                int bgColor = (achvFilterStatus == i) ? 0xFF335533 : (isHover ? 0xFF333333 : 0xFF1A1A1A);
                int borderColor = (achvFilterStatus == i) ? 0xFF55FF55 : 0xFF444444;
                context.fill(currentX, row1Y, currentX + tw1, row1Y + 16, bgColor);
                drawRectBorder(context, currentX, row1Y, tw1, 16, borderColor);
                context.drawTextWithShadow(textRenderer, t1Labels[i], currentX + 8, row1Y + 4, (achvFilterStatus == i) ? 0xFF55FF55 : 0xAAAAAA);
                currentX += tw1 + 6;
            }

            currentX = tableX + 10;
            int row2Y = tableY + 28;

            String[] t2Labels = {"전체", "기록", "누적", "스페셜"};
            for (int i = 0; i < 4; i++) {
                int tw2 = textRenderer.getWidth(t2Labels[i]) + 16;
                boolean isHover = mouseX >= currentX && mouseX <= currentX + tw2 && mouseY >= row2Y && mouseY <= row2Y + 16;
                int bgColor = (achvFilterType == i) ? 0xFF333355 : (isHover ? 0xFF333333 : 0xFF1A1A1A);
                int borderColor = (achvFilterType == i) ? 0xFF5555FF : 0xFF444444;
                context.fill(currentX, row2Y, currentX + tw2, row2Y + 16, bgColor);
                drawRectBorder(context, currentX, row2Y, tw2, 16, borderColor);
                context.drawTextWithShadow(textRenderer, t2Labels[i], currentX + 8, row2Y + 4, (achvFilterType == i) ? 0xFF5555FF : 0xAAAAAA);
                currentX += tw2 + 6;
            }

            int listY = tableY + 54;
            int listH = tableH - 60;

            List<Achievement> displayAchvs = getFilteredAchievements();

            if (displayAchvs.isEmpty()) {
                context.drawCenteredTextWithShadow(this.textRenderer, "조건에 맞는 업적이 없습니다.", tableX + tableW / 2, tableY + tableH / 2 + 10, 0xAAAAAA);
            } else {
                context.enableScissor(tableX, listY, tableX + tableW, listY + listH);
                context.getMatrices().push();
                context.getMatrices().translate(0, -achvListScroll, 0);

                int currentCardY = listY;
                int totalH = 0;

                for (int i = 0; i < displayAchvs.size(); i++) {
                    Achievement ach = displayAchvs.get(i);
                    boolean isAchieved = profileAchievements.contains(ach);
                    int cardH = getCardHeight(ach, tableW);

                    if (currentCardY + cardH >= listY + achvListScroll && currentCardY <= listY + listH + achvListScroll) {

                        boolean isHoverCard = mouseX >= tableX + 10 && mouseX <= tableX + tableW - 15 && mouseY >= currentCardY - achvListScroll && mouseY <= currentCardY + cardH - achvListScroll;

                        context.fill(tableX + 10, currentCardY, tableX + tableW - 15, currentCardY + cardH, isHoverCard ? 0xFF2A2A2A : 0xFF1A1A1A);
                        int borderColor = isAchieved ? parseHex(ach.color(), 0x55FFFF) : 0xFF444444;
                        drawRectBorder(context, tableX + 10, currentCardY, tableW - 25, cardH, isHoverCard ? 0xFFFFFFFF : borderColor);

                        int colorInt = isAchieved ? parseHex(ach.color(), 0x55FFFF) : 0xFFAAAAAA;
                        context.drawTextWithShadow(textRenderer, ach.full(), tableX + 15, currentCardY + 6, colorInt);

                        String simpleTag = "[" + ach.simple() + "]";
                        int achNameW = textRenderer.getWidth(ach.full());
                        context.drawTextWithShadow(textRenderer, simpleTag, tableX + 15 + achNameW + 5, currentCardY + 6, colorInt);

                        if (isAchieved && isMe) {
                            int repBtnW = 75;
                            int repBtnH = 14;
                            int repBtnX = tableX + tableW - 25 - repBtnW;
                            int repBtnY = currentCardY + 6;

                            boolean isRepHover = mouseX >= repBtnX && mouseX <= repBtnX + repBtnW && mouseY >= repBtnY - achvListScroll && mouseY <= repBtnY + repBtnH - achvListScroll;
                            boolean isCurrentRep = ach.id().equals(repAchieveId);

                            int btnBg = isCurrentRep ? 0xFF335533 : (isRepHover ? 0xFF444444 : 0xFF222222);
                            int btnBorder = isCurrentRep ? 0xFF55FF55 : 0xFF666666;
                            context.fill(repBtnX, repBtnY, repBtnX + repBtnW, repBtnY + repBtnH, btnBg);
                            drawRectBorder(context, repBtnX, repBtnY, repBtnW, repBtnH, btnBorder);

                            String btnText = isCurrentRep ? "대표 설정됨" : "대표로 설정";
                            int textColor = isCurrentRep ? 0xFF55FF55 : 0xFFCCCCCC;
                            context.drawCenteredTextWithShadow(textRenderer, btnText, repBtnX + repBtnW / 2, repBtnY + 3, textColor);
                        }

                        AchvProgress prog = getProgress(ach, isAchieved);

                        if (!prog.text.isEmpty()) {
                            context.drawTextWithShadow(textRenderer, prog.text, tableX + 15, currentCardY + 20, 0xCCCCCC);
                        }

                        if (ach.targetMs() > 0 || ach.targetCount() > 0) {
                            int barX = tableX + 15;
                            int barY = currentCardY + 33;
                            int barW = tableW - 35;
                            int barH = 5;
                            context.fill(barX, barY, barX + barW, barY + barH, 0xFF333333);
                            int fillW = (int) (barW * (prog.pct / 100.0));
                            if (fillW > 0) context.fill(barX, barY, barX + fillW, barY + barH, isAchieved ? 0xFF55FF55 : 0xFF00E5FF);
                        }

                        if (selectedDetailAchievement == ach) {
                            int ly = currentCardY + ACHV_CARD_H + 5;
                            List<OrderedText> lines = textRenderer.wrapLines(Text.literal(ach.desc()), tableW - 30);
                            for (OrderedText line : lines) {
                                context.drawTextWithShadow(textRenderer, line, tableX + 15, ly, 0xAAAAAA);
                                ly += 14;
                            }
                            ly += 5;
                            if (ach.targetMs() > 0) {
                                context.drawTextWithShadow(textRenderer, "§8목표 기록: §f" + formatMillis(ach.targetMs()), tableX + 15, ly, 0xFFFFFF);
                                ly += 14;
                            }
                            if (ach.targetCount() > 0) {
                                context.drawTextWithShadow(textRenderer, "§8목표 누적 횟수: §f" + ach.targetCount() + "회", tableX + 15, ly, 0xFFFFFF);
                                ly += 14;
                            }
                            if (ach.targetTrack() != null && !ach.targetTrack().isEmpty()) {
                                context.drawTextWithShadow(textRenderer, "§8지정 트랙: §f" + ach.targetTrack(), tableX + 15, ly, 0xFFFFFF);
                                ly += 14;
                            }
                            if (ach.targetKart() != null && !ach.targetKart().isEmpty()) {
                                context.drawTextWithShadow(textRenderer, "§8지정 카트: §f" + ach.targetKart(), tableX + 15, ly, 0xFFFFFF);
                                ly += 14;
                            }
                            context.drawTextWithShadow(textRenderer, isAchieved ? "§a[ 달성 완료 ]" : "§c[ 미달성 ]", tableX + 15, ly + 5, 0xFFFFFF);
                        }
                    }
                    currentCardY += cardH + 6;
                    totalH += cardH + 6;
                }

                context.getMatrices().pop();
                context.disableScissor();

                int maxScroll = Math.max(0, totalH - listH);
                if (maxScroll > 0) {
                    int thumbH = Math.max(10, (int)(listH * ((float)listH / totalH)));
                    int thumbY = listY + (int)((listH - thumbH) * ((float)achvListScroll / maxScroll));
                    context.fill(tableX + tableW - 8, listY, tableX + tableW - 4, listY + listH, 0x33FFFFFF);
                    context.fill(tableX + tableW - 8, thumbY, tableX + tableW - 4, thumbY + thumbH, 0xAAFFFFFF);
                }
            }
        } else {
            // ★ 랭킹 기록 리스트 렌더링
            context.fill(tableX, tableY, tableX + tableW, tableY + tableH, 0x66000000);
            drawRectBorder(context, tableX, tableY, tableW, tableH, 0xFF222222);

            if (!loading && error == null && !records.isEmpty()) {
                float tableScale = Math.max(0.8f, Math.min(1.0f, tableW / 450.0f));
                boolean showTime   = tableW > 250;
                boolean showBody   = tableW > 320;
                boolean showEngine = tableW > 380;

                int colTrack = tableX + (int)(tableW * 0.05);
                int colTime = tableX + (int)(tableW * 0.40);
                int colBody = tableX + (int)(tableW * 0.60);
                int colEngine = tableX + (int)(tableW * 0.80);

                context.getMatrices().push();
                context.getMatrices().scale(tableScale, tableScale, 1.0f);
                int headerRowY = tableY + 8;
                int sHeadY = (int)(headerRowY / tableScale);

                context.drawTextWithShadow(textRenderer, "트랙", (int)(colTrack / tableScale), sHeadY, 0xDDDDDD);
                if (showTime) context.drawTextWithShadow(textRenderer, "기록", (int)(colTime / tableScale), sHeadY, 0xDDDDDD);
                if (showBody) context.drawTextWithShadow(textRenderer, "카트바디", (int)(colBody / tableScale), sHeadY, 0xDDDDDD);
                if (showEngine) context.drawTextWithShadow(textRenderer, "엔진", (int)(colEngine / tableScale), sHeadY, 0xDDDDDD);
                context.getMatrices().pop();

                // 리스트 클리핑 활성화
                context.enableScissor(tableX, tableY + 24, tableX + tableW, tableY + tableH);

                // ★ 스크롤 한계선 보정 로직
                int extraH = 0;
                if (selectedDetailRecord != null) {
                    extraH += 16 * 2;
                    int hiddenCount = 0;
                    if (!showTime) hiddenCount++;
                    if (!showBody) hiddenCount++;
                    if (!showEngine) hiddenCount++;
                    if (hiddenCount > 0) extraH += hiddenCount * 16 + 5;

                    boolean isSingle = "__singleplay__".equals(selectedDetailRecord.serverAddress());
                    if (isSingle && kartSpecExpanded) {
                        String specRaw = selectedDetailRecord.kartSpecDebug();
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
                    extraH += 10;
                }

                int adjustedCapacityRows = Math.max(1, (tableH - 24 - extraH) / ROW_H) + 1;
                int maxScroll = Math.max(0, records.size() - adjustedCapacityRows + 1); // ★ 마진 1행 추가
                tableScroll = Math.max(0, Math.min(tableScroll, maxScroll));

                int start = tableScroll;
                int end = Math.min(start + Math.max(1, (tableH - 24) / ROW_H) + 3, records.size()); // end 넉넉하게 산출

                int startY = tableY + 24;
                int currentY = startY;
                specBtnScreenW = 0; // 초기화

                for (int i = start; i < end; i++) {
                    RecordRow r = records.get(i);
                    boolean isExpanded = (r == selectedDetailRecord);
                    boolean isSingle = "__singleplay__".equals(r.serverAddress());

                    int itemH = ROW_H;
                    int expandH = 0;
                    int hiddenCount = 0;

                    if (isExpanded) {
                        expandH += 16 * 2; // 모드, 등록 날짜
                        if (!showTime) hiddenCount++;
                        if (!showBody) hiddenCount++;
                        if (!showEngine) hiddenCount++;
                        if (hiddenCount > 0) expandH += hiddenCount * 16 + 5;

                        if (isSingle && kartSpecExpanded) {
                            String specRaw = r.kartSpecDebug();
                            if (specRaw != null && !specRaw.isEmpty() && !specRaw.equals("없음")) {
                                int lines = 1; // 헤더
                                if (specRaw.contains("speed")) lines++;
                                if (specRaw.contains("accel") || specRaw.contains("boost:")) lines++;
                                if (specRaw.contains("corner") || specRaw.contains("drift")) lines++;
                                if (specRaw.contains("gauge") || specRaw.contains("boosttime") || specRaw.contains("maxboostcount")) lines++;
                                if (specRaw.contains("defense")) lines++;
                                if (specRaw.contains("draft")) lines++;
                                expandH += lines * 16 + 10;
                            } else {
                                expandH += 16 + 10;
                            }
                        }
                        itemH += expandH + 10; // 아래 여백
                    }

                    if (currentY > tableY + tableH) break;

                    int bgColor = 0x00000000;
                    if (isExpanded) bgColor = 0x550B0B0B;
                    else if (((i - start) & 1) == 1) bgColor = 0x22000000;

                    context.fill(tableX + 1, currentY, tableX + tableW - 1, currentY + itemH, bgColor);
                    if (isExpanded) {
                        drawRectBorder(context, tableX + 1, currentY, tableW - 2, itemH, 0xFF444444);
                    }

                    boolean hover = !isExpanded && mouseX >= tableX && mouseX <= tableX + tableW && mouseY >= currentY && mouseY < currentY + itemH;
                    if (hover) {
                        context.fill(tableX + 1, currentY, tableX + tableW - 1, currentY + itemH, 0x33FFFFFF);
                    }

                    context.getMatrices().push();
                    context.getMatrices().scale(tableScale, tableScale, 1.0f);

                    int sY = (int)((currentY + (ROW_H - 8 * tableScale) / 2) / tableScale);
                    int trackColor = hover ? HOVER_YELLOW : 0xFFFFFF;

                    int nextColX = showTime ? colTime : (showBody ? colBody : (showEngine ? colEngine : tableX + tableW));
                    int maxTrackW = Math.max(0, (int)((nextColX - colTrack - 5) / tableScale));
                    context.drawTextWithShadow(textRenderer, trimWithEllipsis(r.track(), maxTrackW), (int)(colTrack / tableScale), sY, trackColor);

                    boolean hiddenSomething = false;
                    if (showTime) {
                        int nextTimeColX = showBody ? colBody : (showEngine ? colEngine : tableX + tableW);
                        int maxTimeW = Math.max(0, (int)((nextTimeColX - colTime - 5) / tableScale));
                        context.drawTextWithShadow(textRenderer, trimWithEllipsis(r.timeStr(), maxTimeW), (int)(colTime / tableScale), sY, 0xFFFFFF);
                    } else hiddenSomething = true;

                    if (showBody) {
                        int nextBodyColX = showEngine ? colEngine : tableX + tableW;
                        int maxBodyW = Math.max(0, (int)((nextBodyColX - colBody - 5) / tableScale));
                        String bodyLabel = TireUtil.composeBodyLabel(r.bodyName(), r.tireName());
                        context.drawTextWithShadow(textRenderer, trimWithEllipsis(bodyLabel, maxBodyW), (int)(colBody / tableScale), sY, 0xFFFFFF);

                        // 카트바디 스탯 [+] 버튼 렌더링
                        if (isSingle && isExpanded) {
                            String btnText = kartSpecExpanded ? "[-]" : "[+]";
                            int btnX = (int)(colBody / tableScale) + textRenderer.getWidth(trimWithEllipsis(bodyLabel, maxBodyW)) + 5;

                            specBtnScreenX = (int)(btnX * tableScale);
                            specBtnScreenY = (int)(sY * tableScale);
                            specBtnScreenW = (int)(textRenderer.getWidth(btnText) * tableScale);
                            specBtnScreenH = (int)(10 * tableScale);

                            boolean hoveringSpec = isInside(mouseX, mouseY, specBtnScreenX, specBtnScreenY, specBtnScreenW, specBtnScreenH);
                            int btnColor = kartSpecExpanded ? (hoveringSpec ? 0xFFFF7777 : 0xFFFF5555) : (hoveringSpec ? 0xFF77FF77 : 0xFF55FF55);

                            context.drawTextWithShadow(textRenderer, btnText, btnX, sY, btnColor);
                        }
                    } else hiddenSomething = true;

                    if (showEngine) {
                        int maxEngW = Math.max(0, (int)((tableX + tableW - colEngine - 5) / tableScale));
                        String eng = normalizeEngine(r.engineName());
                        context.drawTextWithShadow(textRenderer, trimWithEllipsis(eng, maxEngW), (int)(colEngine / tableScale), sY, 0xFFFFFF);
                    } else hiddenSomething = true;

                    if (hiddenSomething) {
                        context.drawTextWithShadow(textRenderer, "+", (int)((tableX + tableW - 20) / tableScale), sY, 0xAAAAAA);
                    }
                    context.getMatrices().pop();

                    // ★ 아코디언 내용 렌더링 (프로필 정보/버튼 제외)
                    if (isExpanded) {
                        context.getMatrices().push();
                        context.getMatrices().scale(tableScale, tableScale, 1.0f);

                        int infoX = (int)(colTime / tableScale);
                        int infoY = sY + 20;
                        int lineH = 16;

                        // 화면 축소로 가려진 정보 렌더링
                        if (hiddenCount > 0) {
                            if (!showTime) {
                                context.drawTextWithShadow(textRenderer, "§8기록: §e" + r.timeStr(), infoX, infoY, 0xFFFFFF);
                                infoY += lineH;
                            }
                            if (!showBody) {
                                context.drawTextWithShadow(textRenderer, "§8카트: §f" + TireUtil.composeBodyLabel(r.bodyName(), r.tireName()), infoX, infoY, 0xFFFFFF);
                                infoY += lineH;
                            }
                            if (!showEngine) {
                                context.drawTextWithShadow(textRenderer, "§8엔진: §f" + normalizeEngine(r.engineName()), infoX, infoY, 0xFFFFFF);
                                infoY += lineH;
                            }
                        }

                        context.drawTextWithShadow(textRenderer, "§8모드: §f" + (r.modes() == null || r.modes().isEmpty() ? "없음" : r.modes()), infoX, infoY, 0xFFFFFF);
                        infoY += lineH;
                        String dateStr = formatDateTimeFull(r.submittedAtMs());
                        context.drawTextWithShadow(textRenderer, "§8등록: §7" + dateStr, infoX, infoY, 0xAAAAAA);
                        infoY += lineH;

                        // 스탯 정보 렌더링
                        if (isSingle && kartSpecExpanded) {
                            String specRaw = r.kartSpecDebug();
                            if (specRaw == null || specRaw.isEmpty() || specRaw.equals("없음")) {
                                context.drawTextWithShadow(textRenderer, "§c스탯 정보 없음", infoX, infoY, 0xAAAAAA);
                            } else {
                                String kartName = r.bodyName() == null || r.bodyName().equals("UNKNOWN") ? "카트바디" : r.bodyName();
                                context.drawTextWithShadow(textRenderer, "§e----- " + kartName + " 스탯 -----", infoX, infoY, 0xFFFFFF);
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
                            }
                        }
                        context.getMatrices().pop();
                    }
                    currentY += itemH;
                }

                context.disableScissor();

                // ★ 스크롤바 렌더링 시 scrollProgress를 활용하여 안전하게 바닥까지 이동하도록 수정
                if (maxScroll > 0) {
                    int barW = 6; int barX = tableX + tableW - barW - 2; int barY = tableY + 24; int barH = tableH - 26;
                    context.fill(barX, barY, barX + barW, barY + barH, 0x55000000);

                    float scrollProgress = maxScroll > 0 ? (float) tableScroll / maxScroll : 0;
                    int rowsPerPageSafe = Math.max(1, (tableH - 24) / ROW_H);
                    int thumbH = Math.max(10, (int) (barH * ((float) rowsPerPageSafe / records.size())));
                    int thumbY = barY + (int) ((barH - thumbH) * scrollProgress);
                    context.fill(barX, thumbY, barX + barW, thumbY + thumbH, 0xFF888888);
                }

            } else {
                if (loading) context.drawCenteredTextWithShadow(this.textRenderer, "불러오는 중...", rightAreaX + rightAreaW / 2, tableY + 30, 0xFFFFFF);
                else if (error != null) context.drawCenteredTextWithShadow(this.textRenderer, "오류 발생", rightAreaX + rightAreaW / 2, tableY + 30, 0xFF5555);
                else context.drawCenteredTextWithShadow(this.textRenderer, "기록이 없습니다.", rightAreaX + rightAreaW / 2, tableY + 30, 0xFFFFFF);
            }
        }

        super.render(context, mouseX, mouseY, delta);
    }

    private List<Achievement> getFilteredAchievements() {
        List<Achievement> allAchvs = new ArrayList<>();
        if (achvFilterStatus == 0 || achvFilterStatus == 1) allAchvs.addAll(profileAchievements);
        if (achvFilterStatus == 0 || achvFilterStatus == 2) allAchvs.addAll(missingAchievements);

        List<Achievement> displayAchvs = new ArrayList<>();
        String query = achvSearchBox != null ? achvSearchBox.getText().toLowerCase().trim() : "";

        for (Achievement ach : allAchvs) {
            boolean isRecord = ach.targetMs() > 0;
            boolean isCount = ach.targetCount() > 0;
            boolean isSpecial = !isRecord && !isCount;

            if (achvFilterType == 1 && !isRecord) continue;
            if (achvFilterType == 2 && !isCount) continue;
            if (achvFilterType == 3 && !isSpecial) continue;

            if (!query.isEmpty()) {
                if (!ach.full().toLowerCase().contains(query) &&
                        !ach.simple().toLowerCase().contains(query) &&
                        !ach.desc().toLowerCase().contains(query)) {
                    continue;
                }
            }

            displayAchvs.add(ach);
        }
        return displayAchvs;
    }

    private record AchvProgress(double pct, String text) {}
    private AchvProgress getProgress(Achievement ach, boolean isAchieved) {
        String tTrack = ach.targetTrack() != null ? ach.targetTrack().replace("'", "").replace("\"", "").trim() : "";
        String tKart = ach.targetKart() != null ? ach.targetKart().replace("'", "").replace("\"", "").trim() : "";
        int tCount = ach.targetCount();
        int tMs = ach.targetMs();

        if (isAchieved) {
            if (tCount > 0) return new AchvProgress(100.0, "진행도: " + tCount + " / " + tCount + "회");
            if (tMs > 0) return new AchvProgress(100.0, "최고 기록: 달성 (목표: " + formatMillis(tMs) + ")");
            return new AchvProgress(100.0, "");
        }

        if (tCount > 0) {
            long currentCount = records.stream().filter(r -> {
                if (!tTrack.isEmpty() && !tTrack.equals(r.track().trim())) return false;
                if (!tKart.isEmpty() && !tKart.equals(r.bodyName().trim())) return false;
                return true;
            }).count();
            double pct = Math.min(currentCount, tCount) * 100.0 / tCount;
            return new AchvProgress(pct, "진행도: " + currentCount + " / " + tCount + "회");
        } else if (tMs > 0) {
            long pb = records.stream().filter(r -> {
                if (!tTrack.isEmpty() && !tTrack.equals(r.track().trim())) return false;
                if (!tKart.isEmpty() && !tKart.equals(r.bodyName().trim())) return false;
                return true;
            }).mapToLong(RecordRow::timeMillis).min().orElse(Long.MAX_VALUE);
            if (pb != Long.MAX_VALUE) {
                double pct = (pb <= tMs) ? 100.0 : (tMs * 100.0) / pb;
                return new AchvProgress(pct, String.format("최고 기록: %s (목표: %s)", formatMillis(pb), formatMillis(tMs)));
            } else {
                return new AchvProgress(0.0, "도전 기록 없음 (목표: " + formatMillis(tMs) + ")");
            }
        }
        return new AchvProgress(0.0, "");
    }

    private String formatMillis(long ms) {
        long m = ms / 60000, s = (ms % 60000) / 1000, mm = ms % 1000;
        return String.format("%02d:%02d.%03d", m, s, mm);
    }

    private static String formatDateTimeFull(long ms) { if (ms <= 0) return "알 수 없음"; return new SimpleDateFormat("yyyy-MM-dd HH:mm").format(new Date(ms)); }
    private static String normalizeEngine(String engineName) { if (engineName == null) return "UNKNOWN"; String s = engineName.trim(); if (s.startsWith("[\") && s.endsWith(\"]") && s.length() >= 3) s = s.substring(1, s.length() - 1).trim(); s = s.replace("엔진", "").replace("ENGINE", "").replace("engine", "").trim(); if (s.isBlank()) return "UNKNOWN"; return s.toUpperCase(); }

    private void drawRectBorder(DrawContext context, int x, int y, int w, int h, int color) {
        context.fill(x, y, x + w, y + 1, color);
        context.fill(x, y + h - 1, x + w, y + h, color);
        context.fill(x, y, x + 1, y + h, color);
        context.fill(x + w - 1, y, x + w, y + h, color);
    }

    @Override public boolean shouldPause() { return false; }

    private record RecordRow(long submittedAtMs, String track, String timeStr, long timeMillis, String bodyName, String tireName, String engineName, String modes, String serverAddress, String kartSpecDebug) {}
}