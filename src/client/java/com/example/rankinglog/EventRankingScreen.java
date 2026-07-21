package com.example.rankinglog;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ConfirmScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class EventRankingScreen extends Screen {

    private static List<RankingEntry> cachedRanking = null;
    private static long cachedAt = 0;
    private static String cachedEventId = null;

    private boolean isCacheValid() {
        if (cachedRanking == null || cachedEventId == null || !cachedEventId.equals(eventInfo.eventID())) return false;
        return System.currentTimeMillis() - cachedAt <= ModConfig.get().getCacheTtlMs();
    }

    public static void clearCache() {
        cachedRanking = null;
        cachedAt = 0;
        cachedEventId = null;
    }

    private static final int OUTER_PAD = 12;
    private static final int ROW_H = 26;

    private final Screen parent;
    private final EventOptionSelectScreen.EventEntry eventInfo;
    private final List<RankingEntry> ranking = new ArrayList<>();

    private int tableScroll = 0;
    private boolean loading = true;
    private String error = null;

    private SharedSidebar sharedSidebar;

    private ButtonWidget refreshBtn;
    private ButtonWidget joinBtn;
    private ButtonWidget backBtn;
    private ButtonWidget searchBtn;

    private boolean checkingJoin = true;
    private boolean alreadyJoined = false;
    private String joinedEventID = null;

    // ★ 아코디언을 위한 상태 및 프로필 버튼 좌표 추적 변수
    private RankingEntry selectedDetailEntry = null;
    private String selectedProfileDesc = "불러오는 중...";
    private int profileBtnScreenX, profileBtnScreenY, profileBtnScreenW, profileBtnScreenH;

    public record RankingEntry(String player, String repTitle, String repColor, String time, String engineName, String bodyName, String modes, String tireName, long submittedAtMs) {}

    public EventRankingScreen(Screen parent, EventOptionSelectScreen.EventEntry eventInfo) {
        super(Text.literal("이벤트 랭킹"));
        this.parent = parent;
        this.eventInfo = eventInfo;
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
        try {
            if (hex.startsWith("#")) hex = hex.substring(1);
            return 0xFF000000 | Integer.parseInt(hex, 16);
        } catch (Exception e) { return fallback; }
    }

    private void playUiClick() {
        if (client != null) client.getSoundManager().play(PositionedSoundInstance.master(SoundEvents.UI_BUTTON_CLICK, 1.0f));
    }

    private String trimWithEllipsis(String text, int maxWidth) {
        if (maxWidth <= 0) return "";
        if (textRenderer.getWidth(text) <= maxWidth) return text;
        int ellipsisWidth = textRenderer.getWidth("...");
        if (maxWidth <= ellipsisWidth) return "";
        return textRenderer.trimToWidth(Text.literal(text), maxWidth - ellipsisWidth).getString() + "...";
    }

    // ★ 충돌 체크를 위한 Helper 메서드
    private boolean isInside(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx <= x + w && my >= y && my <= y + h;
    }

    private void fetchProfileDesc(String playerName) {
        new Thread(() -> {
            try {
                JsonObject req = new JsonObject();
                req.addProperty("p_player", playerName);
                JsonObject obj = RankingScreen.Net.postJson(RankingScreen.SUPABASE_RPC_URL + "get_profile", req.toString());
                if (obj.has("ok") && obj.get("ok").getAsBoolean() && obj.has("description")) {
                    String desc = obj.get("description").getAsString();
                    selectedProfileDesc = desc.trim().isEmpty() ? "작성된 소개글이 없습니다." : desc;
                } else {
                    selectedProfileDesc = "작성된 소개글이 없습니다.";
                }
            } catch (Exception ex) {
                selectedProfileDesc = "정보를 불러오지 못했습니다.";
            }
        }).start();
    }

    @Override
    public void close() { if (this.client != null) this.client.setScreen(null); }

    @Override
    protected void init() {
        super.init();
        if (isNewUi()) {
            sharedSidebar = new SharedSidebar(
                    this, "EVENT", null, false,
                    catId -> { if (client != null) client.setScreen(new TmiRankingScreen(new MainMenuScreen(), catId)); },
                    () -> { loading = true; error = null; ranking.clear(); cachedRanking = null; cachedEventId = null; tableScroll = 0; selectedDetailEntry = null; fetchRanking(); checkPlayerJoined(); },
                    this::rebuildUI
            );
        }

        if (isCacheValid()) {
            ranking.clear(); ranking.addAll(cachedRanking); loading = false; tableScroll = 0;
        } else {
            loading = true; tableScroll = 0; fetchRanking();
        }
        checkPlayerJoined();
        rebuildUI();
    }

    private void rebuildUI() {
        clearChildren();
        int effectiveLeftW = getEffectiveSidebarWidth();
        int rightAreaX = effectiveLeftW == 0 ? OUTER_PAD : OUTER_PAD + effectiveLeftW + 8;
        int rightAreaW = this.width - OUTER_PAD - rightAreaX;
        int bottomY = this.height - 28;
        boolean isSmall = rightAreaW < 420;
        int iconBtnSize = 20;

        searchBtn = addDrawableChild(ButtonWidget.builder(Text.literal("🔍"), b -> { playUiClick(); if (this.client != null) this.client.setScreen(new RiderFindScreen(this)); })
                .dimensions(rightAreaX, bottomY, iconBtnSize, 20).tooltip(net.minecraft.client.gui.tooltip.Tooltip.of(Text.literal("라이더 찾기"))).build());

        backBtn = addDrawableChild(ButtonWidget.builder(Text.literal("⏴"), b -> { playUiClick();
                    if (ModConfig.get().showMainScreen) {
                        if (this.client != null) this.client.setScreen(new EventOptionSelectScreen(this));
                    }
                    else{
                        Objects.requireNonNull(this.client).setScreen(new EventOptionSelectScreen(this));
                    } })
                .dimensions(rightAreaX + rightAreaW - (iconBtnSize * 2) - 5, bottomY, iconBtnSize, 20).tooltip(net.minecraft.client.gui.tooltip.Tooltip.of(Text.literal("뒤로 가기"))).build());

        refreshBtn = addDrawableChild(ButtonWidget.builder(Text.literal("🔄"), b -> {
                    playUiClick(); loading = true; error = null; ranking.clear(); cachedRanking = null; cachedEventId = null; tableScroll = 0; selectedDetailEntry = null; fetchRanking(); checkPlayerJoined(); })
                .dimensions(rightAreaX + rightAreaW - iconBtnSize, bottomY, iconBtnSize, 20).tooltip(net.minecraft.client.gui.tooltip.Tooltip.of(Text.literal("새로 고침"))).build());

        int joinBtnW = isSmall ? 50 : 60;
        joinBtn = addDrawableChild(ButtonWidget.builder(Text.literal("확인 중..."), b -> openJoinConfirm()).dimensions(rightAreaX + rightAreaW - joinBtnW, 25, joinBtnW, 20).build());

        updateJoinButtonState();
    }

    private void updateJoinButtonState() {
        if (joinBtn == null) return;
        if (checkingJoin) {
            joinBtn.setMessage(Text.literal("로딩중"));
            joinBtn.active = false;
        } else if (alreadyJoined) {
            String currentID = eventInfo.eventID();
            boolean isIncluded = false;
            if (currentID != null && joinedEventID != null) {
                for (String id : joinedEventID.split(",")) {
                    if (id.trim().equals(currentID)) {
                        isIncluded = true;
                        break;
                    }
                }
            }
            joinBtn.setMessage(Text.literal(isIncluded ? "참여 완료" : "참여 불가"));
            joinBtn.active = false;
        } else {
            joinBtn.setMessage(Text.literal("참여"));
            joinBtn.active = true;
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (isNewUi() && sharedSidebar != null && sharedSidebar.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount)) {
            return true;
        }

        if (!loading && error == null && !ranking.isEmpty()) {
            int effectiveLeftW = getEffectiveSidebarWidth();
            int rightAreaX = effectiveLeftW == 0 ? OUTER_PAD : OUTER_PAD + effectiveLeftW + 8;
            int rightAreaW = this.width - OUTER_PAD - rightAreaX;
            int tableTop = 70;
            int tableX = isNewUi() ? rightAreaX : OUTER_PAD + 8;
            int tableW = isNewUi() ? rightAreaW : this.width - (OUTER_PAD + 8) * 2;
            int tableH = Math.max(80, this.height - 46 - tableTop);

            if (mouseX >= tableX && mouseX <= tableX + tableW && mouseY >= tableTop && mouseY <= tableTop + tableH) {

                // ★ 펼쳐진 항목에 의한 동적 높이 보정을 포함한 스크롤 계산 로직
                int extraH = 0;
                if (selectedDetailEntry != null) {
                    extraH += 16 * 3; // 자기소개, 모드, 날짜 기본 높이
                    boolean showTime = tableW > 250;
                    boolean showBody = tableW > 320;
                    boolean showEngine = tableW > 380;

                    int hiddenCount = 0;
                    if (!showTime) hiddenCount++;
                    if (!showBody) hiddenCount++;
                    if (!showEngine) hiddenCount++;
                    if (hiddenCount > 0) extraH += hiddenCount * 16 + 5;

                    extraH += 26; // 버튼 여백
                }

                // ★ 마진 1행 추가
                int adjustedCapacityRows = Math.max(1, (tableH - 24 - extraH) / ROW_H) + 1;
                int maxScroll = Math.max(0, ranking.size() - adjustedCapacityRows + 1);

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

        if (!loading && error == null && !ranking.isEmpty()) {
            int effectiveLeftW = getEffectiveSidebarWidth();
            int rightAreaX = effectiveLeftW == 0 ? OUTER_PAD : OUTER_PAD + effectiveLeftW + 8;
            int rightAreaW = this.width - OUTER_PAD - rightAreaX;

            int tableTop = 70;
            int tableX = isNewUi() ? rightAreaX : OUTER_PAD + 8;
            int tableW = isNewUi() ? rightAreaW : this.width - (OUTER_PAD + 8) * 2;
            int tableH = Math.max(80, this.height - 46 - tableTop);

            // 리스트 영역 클릭 감지
            if (mouseX >= tableX && mouseX <= tableX + tableW && mouseY >= tableTop + 24 && mouseY <= tableTop + tableH) {

                // ★ 1. 인라인 아코디언 내부의 [프로필 가기] 버튼 클릭 처리
                if (selectedDetailEntry != null && profileBtnScreenW > 0) {
                    if (isInside(mouseX, mouseY, profileBtnScreenX, profileBtnScreenY, profileBtnScreenW, profileBtnScreenH)) {
                        playUiClick();
                        if (this.client != null) {
                            this.client.setScreen(new PlayerProfileScreen(selectedDetailEntry.player(), this));
                        }
                        return true;
                    }
                }

                int startY = tableTop + 24;

                // ★ 스크롤 한계를 보정하여 아이템 갯수 계산
                int extraH = 0;
                if (selectedDetailEntry != null) {
                    extraH += 16 * 3;
                    boolean showTime = tableW > 250;
                    boolean showBody = tableW > 320;
                    boolean showEngine = tableW > 380;

                    int hiddenCount = 0;
                    if (!showTime) hiddenCount++;
                    if (!showBody) hiddenCount++;
                    if (!showEngine) hiddenCount++;
                    if (hiddenCount > 0) extraH += hiddenCount * 16 + 5;

                    extraH += 26;
                }

                int adjustedCapacityRows = Math.max(1, (tableH - 24 - extraH) / ROW_H) + 1;
                int maxScroll = Math.max(0, ranking.size() - adjustedCapacityRows + 1); // ★ 마진 1행 추가

                tableScroll = Math.max(0, Math.min(tableScroll, maxScroll));
                int start = tableScroll;
                // end를 넉넉하게 렌더링하도록 변경
                int end = Math.min(start + Math.max(1, (tableH - 24) / ROW_H) + 3, ranking.size());

                int currentY = startY;

                for (int i = start; i < end; i++) {
                    RankingEntry r = ranking.get(i);
                    int itemH = ROW_H;

                    // 펼쳐진 항목 높이 계산 (아코디언 동적 높이 적용)
                    if (r == selectedDetailEntry) {
                        int localExpandH = 16 * 3; // 자기소개, 모드, 날짜 기본 높이
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

                        localExpandH += 26; // 프로필 버튼 여백
                        itemH += localExpandH;
                    }

                    if (currentY > tableTop + tableH) break;

                    // ★ 2. 리스트 항목(Row) 자체를 클릭했을 때의 처리
                    if (mouseY >= currentY - 2 && mouseY <= currentY + itemH - 2) {
                        playUiClick();
                        if (selectedDetailEntry == r) {
                            selectedDetailEntry = null; // 펼쳐진 곳 또 클릭하면 닫기
                        } else {
                            selectedDetailEntry = r;
                            selectedProfileDesc = "불러오는 중...";
                            fetchProfileDesc(r.player());
                        }
                        return true;
                    }
                    currentY += itemH;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private void checkPlayerJoined() {
        checkingJoin = true; updateJoinButtonState();
        new Thread(() -> {
            try {
                JsonObject req = new JsonObject();
                req.addProperty("p_player", MinecraftClient.getInstance().getSession().getUsername());
                JsonObject obj = RankingScreen.Net.postJson(RankingScreen.SUPABASE_RPC_URL + "check_event_join", req.toString());
                if (obj.has("joined")) {
                    alreadyJoined = obj.get("joined").getAsBoolean();
                    joinedEventID = (alreadyJoined && obj.has("eventID")) ? obj.get("eventID").getAsString() : null;
                }
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                checkingJoin = false;
                if (this.client != null) this.client.execute(this::updateJoinButtonState);
            }
        }).start();
    }

    private void openJoinConfirm() {
        if (this.client == null) return;
        this.client.setScreen(new ConfirmScreen(confirmed -> {
            if (confirmed) joinEvent();
            this.client.setScreen(this);
        }, Text.literal("주의!"), Text.literal("정말로 이 이벤트를 참여하시겠습니까?\n이벤트가 종료되기 전 까지 이벤트를 변경할 수 없습니다."), Text.literal("참여합니다"), Text.literal("다시 생각해볼게요")));
    }

    private void joinEvent() {
        checkingJoin = true; updateJoinButtonState();
        new Thread(() -> {
            try {
                var session = MinecraftClient.getInstance().getSession();
                JsonObject req = new JsonObject();
                req.addProperty("action", "join_event");
                req.addProperty("p_player", session.getUsername());
                req.addProperty("p_event_id", eventInfo.eventID());
                req.addProperty("p_token", session.getAccessToken());

                JsonObject obj = RankingScreen.Net.postJson("https://wmlcwmfabuziancpxdoq.supabase.co/functions/v1/smart-endpoint", req.toString());
                if (obj.has("ok") && obj.get("ok").getAsBoolean()) {
                    alreadyJoined = true;
                    joinedEventID = eventInfo.eventID();
                } else {
                    String err = obj.has("error") ? obj.get("error").getAsString() : "unknown";
                    if (this.client != null && this.client.player != null) {
                        this.client.player.sendMessage(Text.literal("이벤트 참가 실패: " + err), false);
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                checkingJoin = false;
                if (this.client != null) this.client.execute(this::updateJoinButtonState);
            }
        }).start();
    }

    private void fetchRanking() {
        new Thread(() -> {
            List<RankingEntry> fetchedRanking = new ArrayList<>();
            String fetchError = null;
            try {
                JsonObject req = new JsonObject();
                req.addProperty("p_event_id", eventInfo.eventID());
                JsonObject obj = RankingScreen.Net.postJson(RankingScreen.SUPABASE_RPC_URL + "get_event_ranking", req.toString());

                if (obj.has("ok") && obj.get("ok").getAsBoolean()) {
                    JsonArray arr = obj.getAsJsonArray("ranking");
                    for (int i = 0; i < arr.size(); i++) {
                        JsonObject o = arr.get(i).getAsJsonObject();
                        fetchedRanking.add(new RankingEntry(
                                o.get("player").getAsString(),
                                o.has("repTitle") ? o.get("repTitle").getAsString() : "",
                                o.has("repColor") ? o.get("repColor").getAsString() : "#55FFFF",
                                o.get("time").getAsString(),
                                o.get("engineName").getAsString(),
                                o.get("bodyName").getAsString(),
                                o.get("modes").getAsString(),
                                o.get("tireName").getAsString(),
                                o.has("submittedAtMs") ? o.get("submittedAtMs").getAsLong() : 0L
                        ));
                    }
                }
            } catch (Exception e) {
                fetchError = e.getMessage();
            }

            final String finalErr = fetchError;

            if (this.client != null) {
                this.client.execute(() -> {
                    error = finalErr;
                    ranking.clear();
                    ranking.addAll(fetchedRanking);
                    cachedRanking = new ArrayList<>(ranking);
                    cachedAt = System.currentTimeMillis();
                    cachedEventId = eventInfo.eventID();
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
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackground(context, mouseX, mouseY, delta);

        if (isNewUi() && sharedSidebar != null) {
            sharedSidebar.render(context, mouseX, mouseY, delta);
        }

        int effectiveLeftW = getEffectiveSidebarWidth();
        int rightAreaX = effectiveLeftW == 0 ? OUTER_PAD : OUTER_PAD + effectiveLeftW + 8;
        int rightAreaW = this.width - OUTER_PAD - rightAreaX;
        int cx = rightAreaX + rightAreaW / 2;

        context.fill(rightAreaX, 10, rightAreaX + rightAreaW, 60, 0xCC000000);
        drawRectBorder(context, rightAreaX, 10, rightAreaW, 50, 0xFF2A2A2A);

        float headerScale = Math.max(0.8f, Math.min(1.0f, rightAreaW / 450.0f));

        String trackStr = "TRACK: " + eventInfo.track();
        String nameStr = "§6§l" + eventInfo.name();
        String periodStr = "§7기간: " + eventInfo.startDate() + " ~ " + eventInfo.endDate();

        int maxAvailableW = Math.max(20, rightAreaW - 140);
        int maxTextW = Math.max(Math.max(textRenderer.getWidth(trackStr), textRenderer.getWidth(nameStr)), textRenderer.getWidth(periodStr));

        float textScale = headerScale;
        if (maxTextW > maxAvailableW) {
            textScale = Math.max(0.55f, (float) maxAvailableW / maxTextW);
        }
        int safeHeaderW = (int) (maxAvailableW / textScale);

        context.getMatrices().push();
        context.getMatrices().translate(cx, 18, 0);
        context.getMatrices().scale(textScale, textScale, 1.0f);
        context.drawCenteredTextWithShadow(textRenderer, trimWithEllipsis(trackStr, safeHeaderW), 0, 0, 0xAAAAAA);
        context.getMatrices().pop();

        context.getMatrices().push();
        context.getMatrices().translate(cx, 30, 0);
        context.getMatrices().scale(textScale, textScale, 1.0f);
        context.drawCenteredTextWithShadow(textRenderer, trimWithEllipsis(nameStr, safeHeaderW), 0, 0, 0xFFDDAA);
        context.getMatrices().pop();

        context.getMatrices().push();
        context.getMatrices().translate(cx, 44, 0);
        context.getMatrices().scale(textScale, textScale, 1.0f);
        context.drawCenteredTextWithShadow(textRenderer, trimWithEllipsis(periodStr, safeHeaderW), 0, 0, 0xBBBBBB);
        context.getMatrices().pop();

        int tableTop = 70;
        int tableW = isNewUi() ? rightAreaW : this.width - (OUTER_PAD + 8) * 2;
        int tableX = isNewUi() ? rightAreaX : OUTER_PAD + 8;
        int tableH = Math.max(80, this.height - 46 - tableTop);

        context.fill(tableX, tableTop, tableX + tableW, tableTop + tableH, 0x66000000);
        drawRectBorder(context, tableX, tableTop, tableW, tableH, 0xFF222222);

        if (loading) {
            float scale = Math.max(0.8f, Math.min(1.0f, rightAreaW / 450.0f));
            context.getMatrices().push();
            context.getMatrices().scale(scale, scale, 1.0f);
            context.drawCenteredTextWithShadow(textRenderer, "데이터를 불러오는 중...", (int)(cx / scale), (int)((tableTop + 40) / scale), 0xFFFFFF);
            context.getMatrices().pop();
            super.render(context, mouseX, mouseY, delta);
            return;
        }
        if (error != null) {
            float scale = Math.max(0.8f, Math.min(1.0f, rightAreaW / 450.0f));
            context.getMatrices().push();
            context.getMatrices().scale(scale, scale, 1.0f);
            context.drawCenteredTextWithShadow(textRenderer, "오류: " + error, (int)(cx / scale), (int)((tableTop + 40) / scale), 0xFF5555);
            context.getMatrices().pop();
            super.render(context, mouseX, mouseY, delta);
            return;
        }
        if (ranking.isEmpty()) {
            float scale = Math.max(0.8f, Math.min(1.0f, rightAreaW / 450.0f));
            context.getMatrices().push();
            context.getMatrices().scale(scale, scale, 1.0f);
            context.drawCenteredTextWithShadow(textRenderer, "등록된 기록이 없습니다.", (int)(cx / scale), (int)((tableTop + 40) / scale), 0xAAAAAA);
            context.getMatrices().pop();
            super.render(context, mouseX, mouseY, delta);
            return;
        }

        int rX = tableX + (int)(tableW * 0.02);
        int pX = tableX + (int)(tableW * 0.15);
        int tmX = tableX + (int)(tableW * 0.40);
        int bX = tableX + (int)(tableW * 0.60);
        int eX = tableX + (int)(tableW * 0.80);

        boolean showTime   = tableW > 250;
        boolean showBody   = tableW > 320;
        boolean showEngine = tableW > 380;

        float scale = Math.max(0.8f, Math.min(1.0f, rightAreaW / 450.0f));
        context.getMatrices().push();
        context.getMatrices().scale(scale, scale, 1.0f);
        int sHeadY = (int)((tableTop + 8) / scale);

        context.drawTextWithShadow(textRenderer, "순위", (int)(rX / scale), sHeadY, 0xDDDDDD);
        context.drawTextWithShadow(textRenderer, "플레이어", (int)(pX / scale), sHeadY, 0xDDDDDD);
        if (showTime) context.drawTextWithShadow(textRenderer, "기록", (int)(tmX / scale), sHeadY, 0xDDDDDD);
        if (showBody) context.drawTextWithShadow(textRenderer, "카트바디", (int)(bX / scale), sHeadY, 0xDDDDDD);
        if (showEngine) context.drawTextWithShadow(textRenderer, "엔진", (int)(eX / scale), sHeadY, 0xDDDDDD);
        context.getMatrices().pop();

        String myName = MinecraftClient.getInstance().getSession().getUsername();

        // ★ 스크롤 한계선(maxScroll) 보정 적용
        int extraH = 0;
        if (selectedDetailEntry != null) {
            extraH += 16 * 3;

            int hiddenCount = 0;
            if (!showTime) hiddenCount++;
            if (!showBody) hiddenCount++;
            if (!showEngine) hiddenCount++;
            if (hiddenCount > 0) extraH += hiddenCount * 16 + 5;

            extraH += 26;
        }

        int adjustedCapacityRows = Math.max(1, (tableH - 24 - extraH) / ROW_H) + 1;
        int maxScroll = Math.max(0, ranking.size() - adjustedCapacityRows + 1); // ★ 마진 1행 추가

        tableScroll = Math.max(0, Math.min(tableScroll, maxScroll));
        int start = tableScroll;
        // end를 넉넉하게 렌더링하도록 변경
        int end = Math.min(start + Math.max(1, (tableH - 24) / ROW_H) + 3, ranking.size());

        // ★ Scissor (클리핑 영역) 활성화
        context.enableScissor(tableX, tableTop + 24, tableX + tableW, tableTop + tableH);

        int startY = tableTop + 24;
        int currentY = startY;

        // 렌더링 전 수동 버튼 좌표 초기화
        profileBtnScreenW = 0;

        for (int i = start; i < end; i++) {
            RankingEntry r = ranking.get(i);
            int rank = i + 1;
            boolean isMe = r.player().equalsIgnoreCase(myName);
            boolean isExpanded = (r == selectedDetailEntry);

            // 높이 계산
            int itemH = ROW_H;
            int localExpandH = 0;
            int hiddenCount = 0;

            if (isExpanded) {
                localExpandH += 16 * 3;
                if (!showTime) hiddenCount++;
                if (!showBody) hiddenCount++;
                if (!showEngine) hiddenCount++;
                if (hiddenCount > 0) {
                    localExpandH += hiddenCount * 16 + 5;
                }
                localExpandH += 26;
                itemH += localExpandH;
            }

            // 영역 벗어나면 렌더링 생략
            if (currentY > tableTop + tableH) break;

            // 행 배경 렌더링
            int bgColor = 0x22000000;
            if (isExpanded) bgColor = 0x550B0B0B; // 펼쳐진 항목 강조
            else if (isMe) bgColor = 0x6644AA44;
            else if (rank == 1) bgColor = 0x44FFD700;
            else if (rank == 2) bgColor = 0x44C0C0C0;
            else if (rank == 3) bgColor = 0x44CD7F32;
            else if (((i - start) & 1) == 0) bgColor = 0x00000000;

            context.fill(tableX + 1, currentY - 2, tableX + tableW - 1, currentY + itemH - 2, bgColor);
            if (isExpanded) {
                drawRectBorder(context, tableX + 1, currentY - 2, tableW - 2, itemH, 0xFF444444);
            }

            // 호버 렌더링
            if (!isExpanded && mouseX >= tableX && mouseX <= tableX + tableW && mouseY >= currentY - 2 && mouseY <= currentY + itemH - 2) {
                context.fill(tableX + 1, currentY - 2, tableX + tableW - 1, currentY + itemH - 2, 0x33FFFFFF);
            }

            context.getMatrices().push();
            context.getMatrices().scale(scale, scale, 1.0f);

            int cardCenterY = (int)(currentY / scale) + (int)(ROW_H / scale) / 2;
            int sY = cardCenterY - 4;
            int sPx = (int)(pX / scale);

            int rankColor = (rank == 1) ? 0xFFFFE066 : (rank == 2) ? 0xFFE6E6E6 : (rank == 3) ? 0xFFFFB36B : 0xFFFFFF;
            context.drawTextWithShadow(textRenderer, rank + "위", (int)(rX / scale), sY, rankColor);

            int headSize = 16;
            int headX = sPx;
            int headY = cardCenterY - 8;

            Identifier headTex = RankingScreen.SkinLoader.getSkin(r.player(), headSize);
            if (headTex != null) {
                context.drawTexture(RenderLayer::getGuiTextured, headTex, headX, headY, 0.0F, 0.0F, headSize, headSize, headSize, headSize);
            } else {
                context.fill(headX, headY, headX + headSize, headY + headSize, 0xFF555555);
            }

            int textStartX = headX + headSize + 4;
            String repText = "";
            if (r.repTitle() != null && !r.repTitle().isEmpty()) { repText = "[" + r.repTitle() + "]"; }

            int nextColX = showTime ? tmX : (showBody ? bX : (showEngine ? eX : tableX + tableW));
            int maxPlayerW = Math.max(0, (int)((nextColX - pX - 25) / scale));

            if (repText.isEmpty()) {
                context.drawTextWithShadow(textRenderer, trimWithEllipsis(r.player(), maxPlayerW), textStartX, sY, 0xFFFFFF);
            } else {
                context.drawTextWithShadow(textRenderer, trimWithEllipsis(r.player(), maxPlayerW), textStartX, headY + 1, 0xFFFFFF);
                context.getMatrices().push();
                context.getMatrices().translate(headX + headSize + 4, headY + 10, 0);
                context.getMatrices().scale(0.75f, 0.75f, 1.0f);
                context.drawTextWithShadow(textRenderer, repText, 0, 0, parseHex(r.repColor(), 0x55FFFF));
                context.getMatrices().pop();
            }

            boolean hiddenSomething = false;
            if (showTime) {
                int nextTimeColX = showBody ? bX : (showEngine ? eX : tableX + tableW);
                int maxTimeW = Math.max(0, (int)((nextTimeColX - tmX - 5) / scale));
                context.drawTextWithShadow(textRenderer, trimWithEllipsis(r.time(), maxTimeW), (int)(tmX / scale), sY, 0xFFFFFF);
            } else hiddenSomething = true;

            if (showBody) {
                int nextBodyColX = showEngine ? eX : tableX + tableW;
                int maxBodyW = Math.max(0, (int)((nextBodyColX - bX - 5) / scale));
                String bodyLabel = TireUtil.composeBodyLabel(r.bodyName(), r.tireName());
                context.drawTextWithShadow(textRenderer, trimWithEllipsis(bodyLabel, maxBodyW), (int)(bX / scale), sY, 0xFFFFFF);
            } else hiddenSomething = true;

            if (showEngine) {
                int maxEngW = Math.max(0, (int)((tableX + tableW - eX - 5) / scale));
                String engLabel = r.engineName() == null ? "UNKNOWN" : r.engineName().toUpperCase();
                context.drawTextWithShadow(textRenderer, trimWithEllipsis(engLabel, maxEngW), (int)(eX / scale), sY, 0xFFFFFF);
            } else hiddenSomething = true;

            if (hiddenSomething) {
                context.drawTextWithShadow(textRenderer, "+", (int)((tableX + tableW - 20) / scale), sY, 0xAAAAAA);
            }

            context.getMatrices().pop();

            // ★ 인라인 아코디언 내용 렌더링
            if (isExpanded) {
                context.getMatrices().push();
                context.getMatrices().scale(scale, scale, 1.0f);

                int infoX = (int)((pX + 5) / scale);
                int infoY = sY + 20;
                int lineH = 16;
                int maxW = (int)((tableW - 50) / scale);

                context.drawTextWithShadow(textRenderer, trimWithEllipsis("§7" + selectedProfileDesc, maxW), infoX, infoY, 0xAAAAAA);
                infoY += lineH;
                infoY += 5; // 여백 추가

                // 화면 축소로 인해 가려진 요소들을 렌더링 최상단으로 끌어와 표시
                if (hiddenCount > 0) {
                    if (!showTime) {
                        context.drawTextWithShadow(textRenderer, "§8기록: §e" + r.time(), infoX, infoY, 0xFFFFFF);
                        infoY += lineH;
                    }
                    if (!showBody) {
                        context.drawTextWithShadow(textRenderer, "§8카트: §f" + TireUtil.composeBodyLabel(r.bodyName(), r.tireName()), infoX, infoY, 0xFFFFFF);
                        infoY += lineH;
                    }
                    if (!showEngine) {
                        context.drawTextWithShadow(textRenderer, "§8엔진: §f" + (r.engineName() == null ? "UNKNOWN" : r.engineName().toUpperCase()), infoX, infoY, 0xFFFFFF);
                        infoY += lineH;
                    }
                }

                context.drawTextWithShadow(textRenderer, "§8모드: §f" + (r.modes() == null || r.modes().isEmpty() ? "없음" : r.modes()), infoX, infoY, 0xFFFFFF);
                infoY += lineH;
                String dateStr = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm").format(new java.util.Date(r.submittedAtMs()));
                context.drawTextWithShadow(textRenderer, "§8등록: §7" + dateStr, infoX, infoY, 0xAAAAAA);
                infoY += lineH;

                context.getMatrices().pop();

                // ★ 수동으로 [프로필 가기] 버튼 그리기 및 좌표 저장
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

            // ★ 스크롤 위치 계산 시 안전하게 보정
            float scrollProgress = maxScroll > 0 ? (float) tableScroll / maxScroll : 0;
            int rowsPerPageSafe = Math.max(1, (tableH - 24) / ROW_H);
            int thumbH = Math.max(10, (int) (barH * ((float) rowsPerPageSafe / ranking.size())));
            int thumbY = barY + (int) ((barH - thumbH) * scrollProgress);

            context.fill(barX, thumbY, barX + barW, thumbY + thumbH, 0xFF888888);
        }

        super.render(context, mouseX, mouseY, delta);
    }

    @Override
    public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
        context.fill(0, 0, this.width, this.height, ModConfig.get().getBgColor());
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}