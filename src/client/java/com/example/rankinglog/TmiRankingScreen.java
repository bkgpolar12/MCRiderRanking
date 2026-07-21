package com.example.rankinglog;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

public class TmiRankingScreen extends Screen {

    private final Screen parent;

    private record TmiEntry(String player, String displayValue, String track, String timeStr, long timeMs, String engine, String body, String tire, String modes, String rankStr, long submittedAtMs, boolean isIndependent) {}
    private record TmiCategory(String id, String parent, String name, boolean isPlayer, boolean isRecentLayout, int chartType, List<TmiEntry> data) {}

    private record MainCat(String id, String icon, String name) {}
    private final List<MainCat> mainCats = List.of(
            new MainCat("PLAYER", "👤", "플레이어"),
            new MainCat("ENGINE", "⚙", "엔진"),
            new MainCat("KARTBODY", "🚙", "카트바디"),
            new MainCat("RECORD", "⏱", "기록")
    );
    private String selectedMainCat = "PLAYER";

    private final List<TmiCategory> categories = new ArrayList<>();
    private TmiCategory selectedCategory = null;
    private final List<TmiEntry> filteredData = new ArrayList<>();

    private boolean isDetailView = false;
    private boolean isGraphView = false;
    private int gridScrollY = 0;

    private int tableScroll = 0;
    private int currentOffset = 0;
    private static final int FETCH_LIMIT = 50;
    private boolean hasMoreData = true;
    private boolean isFetchingMore = false;

    private boolean loading = true;
    private String error = null;
    private long totalRecordsCount = 0;
    private long independentEventCount = 0;

    private int rowsPerPage = 10;

    private ButtonWidget closeBtn, refreshBtn, backToListBtn, toggleGraphBtn;
    private TextFieldWidget searchBox;

    private static final int HEADER_H = 45;

    // ★ 인라인 아코디언 및 수동 프로필 버튼을 위한 상태 변수
    private TmiEntry selectedDetailEntry = null;
    private String selectedProfileDesc = "불러오는 중...";
    private String selectedRepTitle = "";
    private String selectedRepColor = "#55FFFF";
    private int profileBtnScreenX, profileBtnScreenY, profileBtnScreenW, profileBtnScreenH;

    private static final int OUTER_PAD = 12;
    private static final int BTN_H = 18;
    private static final int ROW_H = 18;

    private SharedSidebar sharedSidebar;
    private double legacySidebarScroll = 0;

    private final int[] CHART_COLORS = {
            0xFFFFD700, 0xFFC0C0C0, 0xFFCD7F32, 0xFF5555FF, 0xFF55FF55,
            0xFFFF5555, 0xFFFFFF55, 0xFFFF55FF, 0xFF55FFFF, 0xFFAAAAAA
    };

    public TmiRankingScreen(Screen parent) {
        this(parent, "PLAYER");
    }

    public TmiRankingScreen(Screen parent, String startCat) {
        super(Text.literal("맠라랭 TMI"));
        this.parent = parent;
        this.selectedMainCat = startCat;
    }

    public void setSelectedMainCat(String cat) {
        this.selectedMainCat = cat;
        if (sharedSidebar != null) sharedSidebar.setActiveTmiCatId(cat);

        isDetailView = false;
        isGraphView = false;
        if (toggleGraphBtn != null) toggleGraphBtn.setMessage(Text.literal("📊 그래프 뷰"));
        selectedCategory = null;
        gridScrollY = 0;

        if (searchBox != null) searchBox.setText("");
        fetchTmiData(true);
        updateUIVisibility();
    }

    private boolean isNewUi() {
        try { return !ModConfig.get().useLegacyUi; }
        catch (Exception e) { return false; }
    }

    private int getLegacySidebarWidth() {
        return Math.max(100, Math.min(130, this.width / 5));
    }

    private int getEffectiveSidebarWidth() {
        if (!isNewUi()) return getLegacySidebarWidth();
        if (sharedSidebar == null) return 0;
        int sw = sharedSidebar.getCurrentWidth(this.width);
        return sw == 0 ? 20 : sw;
    }

    private String trimWithEllipsis(String text, int maxWidth) {
        if (maxWidth <= 0) return "";
        if (this.textRenderer.getWidth(text) <= maxWidth) return text;
        int ellipsisWidth = this.textRenderer.getWidth("...");
        if (maxWidth <= ellipsisWidth) return "";
        return this.textRenderer.trimToWidth(Text.literal(text), maxWidth - ellipsisWidth).getString() + "...";
    }

    // ★ 클릭 충돌 감지 헬퍼 메서드
    private boolean isInside(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx <= x + w && my >= y && my <= y + h;
    }

    @Override
    protected void init() {
        if (isNewUi()) {
            sharedSidebar = new SharedSidebar(
                    this, "TMI", selectedMainCat, true,
                    this::setSelectedMainCat, () -> fetchTmiData(true), this::repositionUiElements
            );
        }

        int iconBtnSize = 20;
        closeBtn = ButtonWidget.builder(Text.literal("⏴"), b -> {
            playUiClick();
            if (ModConfig.get().showMainScreen) {
                this.client.setScreen(null);

            }
            else{
                close();
            }
        }).dimensions(0, 0, iconBtnSize, 20).build();
        refreshBtn = ButtonWidget.builder(Text.literal("🔄"), b -> { playUiClick(); fetchTmiData(true); }).dimensions(0, 0, iconBtnSize, 20).build();
        addDrawableChild(closeBtn); addDrawableChild(refreshBtn);

        backToListBtn = ButtonWidget.builder(Text.literal("⏴ 목록으로"), b -> {
            playUiClick(); isDetailView = false; isGraphView = false;
            if (toggleGraphBtn != null) toggleGraphBtn.setMessage(Text.literal("📊 그래프 뷰"));
            selectedCategory = null;
            selectedDetailEntry = null; // 뒤로가기 시 아코디언 닫힘
            if (searchBox != null) searchBox.setText("");
            updateUIVisibility();
        }).dimensions(0, 0, 70, BTN_H).build();

        toggleGraphBtn = ButtonWidget.builder(Text.literal("📊 그래프 뷰"), b -> {
            playUiClick(); isGraphView = !isGraphView;
            b.setMessage(Text.literal(isGraphView ? "📝 리스트 뷰" : "📊 그래프 뷰"));
        }).dimensions(0, 0, 80, BTN_H).build();

        searchBox = new TextFieldWidget(this.textRenderer, 0, 0, 10, BTN_H, Text.literal("검색"));
        searchBox.setMaxLength(64); searchBox.setChangedListener(s -> { tableScroll = 0; updateFilteredData(); });

        addDrawableChild(backToListBtn); addDrawableChild(toggleGraphBtn); addDrawableChild(searchBox);

        repositionUiElements(); setInitialFocus(searchBox);
        fetchTmiData(true); updateUIVisibility();
    }

    private void repositionUiElements() {
        int effectiveLeftW = getEffectiveSidebarWidth();
        int rightAreaX = OUTER_PAD + effectiveLeftW + 8;
        int rightAreaW = this.width - OUTER_PAD - rightAreaX;

        int iconBtnSize = 20;
        if (closeBtn != null) closeBtn.setPosition(rightAreaX, this.height - 28);
        if (refreshBtn != null) refreshBtn.setPosition(rightAreaX + rightAreaW - iconBtnSize, this.height - 28);

        int headerY = OUTER_PAD;
        int btnY = headerY + (HEADER_H - BTN_H) / 2;
        int btnW = Math.min(150, (rightAreaW - 30) / 2);

        if (backToListBtn != null) backToListBtn.setPosition(rightAreaX + 10, btnY);
        if (toggleGraphBtn != null) toggleGraphBtn.setPosition(rightAreaX + 10 + 75, btnY);
        if (searchBox != null) { searchBox.setX(rightAreaX + 10 + 75 + 85); searchBox.setY(btnY); searchBox.setWidth(btnW - 80); }
    }

    private List<TmiCategory> getSubCategories() { return categories.stream().filter(c -> c.parent().equals(selectedMainCat)).toList(); }

    private void playUiClick() { if (this.client != null) this.client.getSoundManager().play(PositionedSoundInstance.master(SoundEvents.UI_BUTTON_CLICK, 1.0f)); }

    private void updateUIVisibility() {
        if (backToListBtn != null) backToListBtn.visible = isDetailView;

        if (toggleGraphBtn != null) {
            boolean hasGraph = selectedCategory != null && selectedCategory.chartType() > 0;
            toggleGraphBtn.visible = isDetailView && hasGraph;
        }

        if (searchBox != null) searchBox.setVisible(isDetailView);
        repositionUiElements();
    }

    private void updateFilteredData() {
        filteredData.clear();
        if (selectedCategory == null) return;
        String q = searchBox == null ? "" : searchBox.getText().trim().toLowerCase();
        for (TmiEntry e : selectedCategory.data()) {
            if (q.isEmpty() || e.player().toLowerCase().contains(q) || e.track().toLowerCase().contains(q) || e.engine().toLowerCase().contains(q) || e.body().toLowerCase().contains(q)) {
                filteredData.add(e);
            }
        }
    }

    private double extractNumericValue(TmiEntry e) {
        if (e.timeMs() > 0) return e.timeMs();
        String valStr = e.displayValue();
        if (valStr == null || valStr.isEmpty()) return 0;
        try {
            String numOnly = valStr.replaceAll("[^0-9.]", "");
            if (!numOnly.isEmpty()) return Double.parseDouble(numOnly);
        } catch (Exception ex) {}
        return 0;
    }

    private void fetchTmiData(boolean reset) {
        if (reset) {
            currentOffset = 0; filteredData.clear(); categories.clear(); selectedDetailEntry = null;
            totalRecordsCount = 0; independentEventCount = 0; hasMoreData = true; tableScroll = 0; loading = true;
            updateUIVisibility();
        }

        if (!hasMoreData || isFetchingMore) return;
        isFetchingMore = true; error = null;

        new Thread(() -> {
            try {
                JsonObject req = new JsonObject();
                req.addProperty("p_main_cat", selectedMainCat);
                req.addProperty("p_query", searchBox != null ? searchBox.getText() : "");
                req.addProperty("p_limit", FETCH_LIMIT);
                req.addProperty("p_offset", currentOffset);

                JsonObject res = RankingScreen.Net.postJson(RankingScreen.SUPABASE_RPC_URL + "get_tmi_rankings", "{}");

                if (res != null && res.has("ok") && res.get("ok").getAsBoolean()) {
                    if (res.has("totalRecords")) totalRecordsCount = res.get("totalRecords").getAsLong();
                    if (res.has("independentEventCount")) independentEventCount = res.get("independentEventCount").getAsLong();
                    if (res.has("categories")) {
                        JsonArray cats = res.getAsJsonArray("categories");
                        for (JsonElement cel : cats) {
                            JsonObject cObj = cel.getAsJsonObject();
                            String cId = cObj.get("id").getAsString();
                            String cParent = cObj.has("parent") ? cObj.get("parent").getAsString() : "PLAYER";
                            String cName = cObj.get("name").getAsString();
                            boolean isPlayer = cObj.has("isPlayer") && cObj.get("isPlayer").getAsBoolean();
                            boolean isRecentLayout = cObj.has("isRecentLayout") && cObj.get("isRecentLayout").getAsBoolean();
                            int chartType = cObj.has("chartType") ? cObj.get("chartType").getAsInt() : 0;

                            List<TmiEntry> entryList = new ArrayList<>();
                            if (cObj.has("data") && cObj.get("data").isJsonArray()) {
                                JsonArray dataArr = cObj.getAsJsonArray("data");
                                for (JsonElement el : dataArr) {
                                    JsonObject obj = el.getAsJsonObject();
                                    entryList.add(new TmiEntry(
                                            obj.has("player") && !obj.get("player").isJsonNull() ? obj.get("player").getAsString() : "Unknown",
                                            obj.has("displayValue") && !obj.get("displayValue").isJsonNull() ? obj.get("displayValue").getAsString() : "",
                                            obj.has("track") && !obj.get("track").isJsonNull() ? obj.get("track").getAsString() : "",
                                            obj.has("timeStr") && !obj.get("timeStr").isJsonNull() ? obj.get("timeStr").getAsString() : "",
                                            obj.has("timeMs") && !obj.get("timeMs").isJsonNull() ? obj.get("timeMs").getAsLong() : 0L,
                                            obj.has("engine") && !obj.get("engine").isJsonNull() ? obj.get("engine").getAsString() : "UNKNOWN",
                                            obj.has("body") && !obj.get("body").isJsonNull() ? obj.get("body").getAsString() : "UNKNOWN",
                                            obj.has("tire") && !obj.get("tire").isJsonNull() ? obj.get("tire").getAsString() : "UNKNOWN",
                                            obj.has("modes") && !obj.get("modes").isJsonNull() ? obj.get("modes").getAsString() : "없음",
                                            obj.has("rankStr") && !obj.get("rankStr").isJsonNull() ? obj.get("rankStr").getAsString() : "",
                                            obj.has("submittedAtMs") && !obj.get("submittedAtMs").isJsonNull() ? obj.get("submittedAtMs").getAsLong() : 0L,
                                            obj.has("isIndependent") && !obj.get("isIndependent").isJsonNull() && obj.get("isIndependent").getAsBoolean()
                                    ));
                                }
                            }
                            categories.add(new TmiCategory(cId, cParent, cName, isPlayer, isRecentLayout, chartType, entryList));
                        }
                        if (isDetailView && selectedCategory != null) updateFilteredData();
                    }
                } else { error = "데이터를 불러오지 못했습니다."; }
            } catch (Exception e) { error = "통신 오류: " + e.getMessage(); }

            isFetchingMore = false; hasMoreData = false;
            if (this.client != null) { this.client.execute(() -> { loading = false; repositionUiElements(); }); }
        }).start();
    }

    private void fetchProfileDesc(String playerName) {
        new Thread(() -> {
            try {
                JsonObject req = new JsonObject(); req.addProperty("p_player", playerName);
                JsonObject obj = RankingScreen.Net.postJson(RankingScreen.SUPABASE_RPC_URL + "get_profile", req.toString());
                if (obj.has("ok") && obj.get("ok").getAsBoolean()) {
                    String desc = obj.has("description") ? obj.get("description").getAsString() : "";
                    selectedProfileDesc = desc.trim().isEmpty() ? "작성된 소개글이 없습니다." : desc;
                    selectedRepTitle = obj.has("repSimple") ? obj.get("repSimple").getAsString() : "";
                    selectedRepColor = obj.has("repColor") ? obj.get("repColor").getAsString() : "#55FFFF";
                } else { selectedProfileDesc = "작성된 소개글이 없습니다."; }
            } catch (Exception ex) { selectedProfileDesc = "정보를 불러오지 못했습니다."; }
        }).start();
    }

    private int parseHex(String hex, int fallback) {
        if (hex == null || hex.isEmpty()) return fallback;
        try { if (hex.startsWith("#")) hex = hex.substring(1); return 0xFF000000 | Integer.parseInt(hex, 16); } catch (Exception e) { return fallback; }
    }

    // ★ 플레이어 스킨 헤드 텍스처 렌더링 공용 헬퍼
    private void drawPlayerHead(DrawContext context, String playerName, int x, int y, int size) {
        Identifier headTex = RankingScreen.SkinLoader.getSkin(playerName, size);
        if (headTex != null) {
            context.drawTexture(RenderLayer::getGuiTextured, headTex, x, y, 0.0F, 0.0F, size, size, size, size);
        } else {
            context.fill(x, y, x + size, y + size, 0xFF555555);
        }
    }

    private String formatDateTimeFull(long ms) {
        if (ms <= 0) return "알 수 없음"; return new SimpleDateFormat("yyyy-MM-dd HH:mm").format(new Date(ms));
    }

    private void drawRectBorder(DrawContext context, int x, int y, int w, int h, int color) {
        context.fill(x, y, x + w, y + 1, color); context.fill(x, y + h - 1, x + w, y + h, color);
        context.fill(x, y, x + 1, y + h, color); context.fill(x + w - 1, y, x + w, y + h, color);
    }

    // ★ 커스텀 NoticeModal 스타일 툴팁 렌더링 헬퍼 메서드
    private void renderCustomTooltip(DrawContext context, String text, int mouseX, int mouseY) {
        int tw = this.textRenderer.getWidth(text);
        int th = 20;
        int tx = mouseX + 12;
        int ty = mouseY - 12;

        // 화면 밖으로 벗어나지 않게 보정
        if (tx + tw + 12 > this.width) tx = mouseX - tw - 12;
        if (ty + th > this.height) ty = mouseY - th - 5;

        context.getMatrices().push();
        context.getMatrices().translate(0, 0, 500); // UI 최상단으로 올리기

        // NoticeModal 스타일의 어두운 배경 및 회색 테두리
        context.fill(tx, ty, tx + tw + 12, ty + th, 0xEE0B0B0B);
        drawRectBorder(context, tx, ty, tw + 12, th, 0xFF444444);

        context.drawTextWithShadow(this.textRenderer, text, tx + 6, ty + 6, 0xFFFFAA); // 노란빛으로 강조
        context.getMatrices().pop();
    }

    private void drawPieChart(DrawContext context, int cx, int cy, int radius, List<Double> values, List<Integer> colors) {
        double total = values.stream().mapToDouble(v -> v).sum();
        if (total == 0) return;

        double[] startAngles = new double[values.size()];
        double[] endAngles = new double[values.size()];
        double currentAngle = -Math.PI / 2;

        for (int i = 0; i < values.size(); i++) {
            startAngles[i] = currentAngle;
            double sweepAngle = (values.get(i) / total) * 2 * Math.PI;
            currentAngle += sweepAngle;
            endAngles[i] = currentAngle;
        }

        for (int y = -radius; y <= radius; y++) {
            int xBound = (int) Math.sqrt(radius * radius - y * y);
            if (xBound < 0) continue;

            int currentStartX = -xBound;
            int currentColor = -1;

            for (int x = -xBound; x <= xBound; x++) {
                double angle = Math.atan2(y, x);
                if (angle < -Math.PI / 2) angle += 2 * Math.PI;

                int colorIdx = 0;
                for (int i = 0; i < values.size(); i++) {
                    if (angle >= startAngles[i] && angle <= endAngles[i] + 0.0001) {
                        colorIdx = i;
                        break;
                    }
                }
                int pxColor = colors.get(colorIdx);

                if (currentColor != pxColor) {
                    if (currentColor != -1) {
                        context.fill(cx + currentStartX, cy + y, cx + x, cy + y + 1, currentColor);
                    }
                    currentStartX = x;
                    currentColor = pxColor;
                }
            }
            if (currentColor != -1) {
                context.fill(cx + currentStartX, cy + y, cx + xBound + 1, cy + y + 1, currentColor);
            }
        }
    }

    @Override
    public void close() { if (this.client != null) this.client.setScreen(null); }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (isNewUi() && sharedSidebar != null && sharedSidebar.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount)) return true;

        if (!isNewUi() && mouseX >= OUTER_PAD && mouseX <= OUTER_PAD + getLegacySidebarWidth() && mouseY >= OUTER_PAD + 24 && mouseY <= this.height - OUTER_PAD) {
            int sbH = this.height - OUTER_PAD * 2;
            int totalLegacyH = 43 + (mainCats.size() * 35);
            int maxScroll = Math.max(0, totalLegacyH - (sbH - 24));
            legacySidebarScroll = Math.max(0, Math.min(legacySidebarScroll - verticalAmount * 20, maxScroll));
            return true;
        }

        if (!loading && error == null) {
            int effectiveLeftW = getEffectiveSidebarWidth();
            int rightAreaX = OUTER_PAD + effectiveLeftW + 8;
            int rightAreaW = this.width - OUTER_PAD - rightAreaX;
            int tableTop = isDetailView ? (OUTER_PAD + HEADER_H + 8) : (OUTER_PAD + 4);
            int tableBottom = this.height - 46;
            int tableH = Math.max(80, tableBottom - tableTop);

            if (mouseX >= rightAreaX && mouseX <= rightAreaX + rightAreaW && mouseY >= tableTop && mouseY <= tableTop + tableH) {
                if (!isDetailView) {
                    List<TmiCategory> subs = getSubCategories();
                    int columns = 2; int gap = 12; int boxH = 145;
                    int totalRows = (int) Math.ceil(subs.size() / (double)columns);
                    int maxGridScroll = Math.max(0, totalRows * (boxH + gap) - gap - tableH);
                    gridScrollY = Math.max(0, Math.min(gridScrollY - (int)(verticalAmount * 30), maxGridScroll));
                    return true;
                } else if (selectedCategory != null) {

                    // ★ 스크롤 한계 보정
                    int extraH = 0;
                    if (selectedCategory.isRecentLayout() && selectedDetailEntry != null) {
                        extraH += 16 * 3;
                        boolean showTrack  = rightAreaW > 220;
                        boolean showTime   = rightAreaW > 280;
                        boolean showBody   = rightAreaW > 350;
                        boolean showEngine = rightAreaW > 420;
                        int hiddenCount = 0;
                        if (!showTrack) hiddenCount++;
                        if (!showTime) hiddenCount++;
                        if (!showBody) hiddenCount++;
                        if (!showEngine) hiddenCount++;
                        if (hiddenCount > 0) extraH += hiddenCount * 16 + 5;
                        extraH += 26;
                    }

                    int adjustedCapacityRows = Math.max(1, (tableH - 26 - extraH) / ROW_H) + 1;
                    int maxScroll = Math.max(0, filteredData.size() - adjustedCapacityRows + 1); // ★ +1: 마지막 카드 하단 여유공간

                    tableScroll = Math.max(0, Math.min(tableScroll - (int)Math.signum(verticalAmount) * 3, maxScroll));
                    if (tableScroll >= maxScroll && hasMoreData && !isFetchingMore) fetchTmiData(false);
                    return true;
                }
            }
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
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
    public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
        context.fill(0, 0, this.width, this.height, ModConfig.get().getBgColor());
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackground(context, mouseX, mouseY, delta);

        if (isNewUi() && sharedSidebar != null) {
            sharedSidebar.render(context, mouseX, mouseY, delta);
        } else {
            int sbX = OUTER_PAD; int sbY = OUTER_PAD; int sbH = this.height - OUTER_PAD * 2;
            int curSidebarW = getLegacySidebarWidth();
            context.fill(sbX, sbY, sbX + curSidebarW, sbY + sbH, 0xCC000000);
            drawRectBorder(context, sbX, sbY, curSidebarW, sbH, 0xFF2A2A2A);

            int contentTop = sbY + 24;
            int totalLegacyH = 43 + (mainCats.size() * 35);
            int maxScroll = Math.max(0, totalLegacyH - (sbH - 24));
            legacySidebarScroll = Math.max(0, Math.min(legacySidebarScroll, maxScroll));

            context.enableScissor(sbX, contentTop, sbX + curSidebarW, sbY + sbH);
            context.getMatrices().push();
            context.getMatrices().translate(0, -legacySidebarScroll, 0);

            int sy = contentTop + 20;
            context.drawCenteredTextWithShadow(this.textRenderer, "📊 맠라랭 TMI", sbX + curSidebarW / 2, sy, 0xFFDDAA);
            sy += 14;
            context.getMatrices().push(); context.getMatrices().scale(0.7f, 0.7f, 1.0f);
            context.drawCenteredTextWithShadow(this.textRenderer, "누가 제일 마크라이더에 진심일까?", (int)((sbX + curSidebarW / 2) / 0.7f), (int)(sy / 0.7f), 0xBBBBBB);
            context.getMatrices().pop();
            sy += 25;

            double adjustedY = mouseY + legacySidebarScroll;
            for (MainCat mc : mainCats) {
                boolean isSel = mc.id().equals(selectedMainCat);
                boolean hover = mouseX >= sbX && mouseX <= sbX + curSidebarW && adjustedY >= sy && adjustedY <= sy + 30;
                int bgColor = isSel ? 0xFF335533 : (hover ? 0xFF333333 : 0x00000000);
                context.fill(sbX + 4, sy, sbX + curSidebarW - 4, sy + 30, bgColor);
                if (isSel) drawRectBorder(context, sbX + 4, sy, curSidebarW - 8, 30, 0xFF55FF55);
                else if (hover) drawRectBorder(context, sbX + 4, sy, curSidebarW - 8, 30, 0xFF444444);
                context.drawTextWithShadow(this.textRenderer, mc.icon() + " " + mc.name(), sbX + 15, sy + 11, isSel ? 0xFF55FF55 : 0xAAAAAA);
                sy += 35;
            }

            context.getMatrices().pop();
            context.disableScissor();

            if (maxScroll > 0) {
                int barW = 4; int barX = sbX + curSidebarW - barW - 2; int barY = contentTop + 2; int barH = sbH - 24 - 4;
                context.fill(barX, barY, barX + barW, barY + barH, 0x55000000);
                int thumbH = Math.max(10, (int) (barH * ((float) (sbH - 24) / totalLegacyH)));
                int thumbY = barY + (int) ((barH - thumbH) * (legacySidebarScroll / maxScroll));
                context.fill(barX, thumbY, barX + barW, thumbY + thumbH, 0xFF888888);
            }
        }

        int effectiveLeftW = getEffectiveSidebarWidth();
        int rightAreaX = OUTER_PAD + effectiveLeftW + 8;
        int rightAreaW = this.width - OUTER_PAD - rightAreaX;
        int headerY = OUTER_PAD;
        int tableTop;

        if (isDetailView) {
            context.fill(rightAreaX, headerY, rightAreaX + rightAreaW, headerY + HEADER_H, 0xCC000000);
            drawRectBorder(context, rightAreaX, headerY, rightAreaW, HEADER_H, 0xFF2A2A2A);

            if (selectedMainCat.equals("RECORD")) {
                String recordTxt = "총 누적 기록: " + totalRecordsCount + "개";
                int textW = this.textRenderer.getWidth(recordTxt);
                float scale = Math.min(1.0f, (rightAreaW - 20) / (float)textW);
                float recordStartX = rightAreaX + rightAreaW - textW * scale - 10;

                if (selectedCategory != null && selectedCategory.id().equals("EVENT_RECORDS")) {
                    String indepTxt = "§l이벤트 독립 기록: " + independentEventCount + "개   ";
                    int indepW = this.textRenderer.getWidth(indepTxt);
                    float indepScale = Math.min(scale, Math.min(1.0f, (recordStartX - rightAreaX - 10) / (float)indepW));
                    float indepStartX = recordStartX - indepW * indepScale;
                    context.getMatrices().push();
                    context.getMatrices().translate(indepStartX, headerY + 16, 0);
                    context.getMatrices().scale(indepScale, indepScale, 1.0f);
                    context.drawTextWithShadow(this.textRenderer, indepTxt, 0, 0, 0xFFFFAA);
                    context.getMatrices().pop();
                }

                context.getMatrices().push();
                context.getMatrices().translate(recordStartX, headerY + 16, 0);
                context.getMatrices().scale(scale, scale, 1.0f);
                context.drawTextWithShadow(this.textRenderer, recordTxt, 0, 0, 0xAAAAAA);
                context.getMatrices().pop();
            }
            tableTop = headerY + HEADER_H + 8;
        } else {
            tableTop = OUTER_PAD + 4;
        }

        super.render(context, mouseX, mouseY, delta);

        int tableBottom = this.height - 46;
        int tableH = Math.max(80, tableBottom - tableTop);
        rowsPerPage = Math.max(1, (tableH - 26) / ROW_H);

        if (loading) { context.drawCenteredTextWithShadow(this.textRenderer, "불러오는 중...", rightAreaX + rightAreaW / 2, tableTop + 26, 0xFFFFFF); return; }
        if (error != null) { context.drawCenteredTextWithShadow(this.textRenderer, "오류: " + error, rightAreaX + rightAreaW / 2, tableTop + 26, 0xFF5555); return; }

        if (!isDetailView) {
            List<TmiCategory> subs = getSubCategories();
            if (subs.isEmpty()) {
                context.drawCenteredTextWithShadow(this.textRenderer, "표시할 카테고리가 없습니다.", rightAreaX + rightAreaW / 2, tableTop + 26, 0xFFFFFF);
                return;
            }

            int columns = 2; int gap = 12; int boxW = (rightAreaW - gap) / 2; int boxH = 145;
            int totalRows = (int) Math.ceil(subs.size() / (double)columns);
            int maxGridScroll = Math.max(0, totalRows * (boxH + gap) - gap - tableH);
            gridScrollY = Math.max(0, Math.min(gridScrollY, maxGridScroll));

            context.enableScissor(rightAreaX, tableTop, rightAreaX + rightAreaW, tableTop + tableH);
            context.getMatrices().push();
            context.getMatrices().translate(0, -gridScrollY, 0);

            for (int i = 0; i < subs.size(); i++) {
                TmiCategory cat = subs.get(i);
                int col = i % columns;
                int row = i / columns;
                int x = rightAreaX + col * (boxW + gap);
                int y = tableTop + row * (boxH + gap);

                boolean hover = mouseX >= x && mouseX <= x + boxW && mouseY >= tableTop && mouseY <= tableTop + tableH
                        && (mouseY + gridScrollY) >= y && (mouseY + gridScrollY) <= y + boxH;

                context.fill(x, y, x + boxW, y + boxH, hover ? 0x882A2A2A : 0x66000000);
                drawRectBorder(context, x, y, boxW, boxH, hover ? 0xFF66FF66 : 0xFF444444);

                String catTitle = "📌 " + cat.name();
                int maxTitleW = boxW - 20;
                int titleW = textRenderer.getWidth(catTitle);
                float titleScale = 1.0f;
                if (titleW > maxTitleW && maxTitleW > 0) {
                    titleScale = (float) maxTitleW / titleW;
                }

                context.getMatrices().push();
                context.getMatrices().translate(x + 10, y + 10, 0);
                context.getMatrices().scale(titleScale, titleScale, 1.0f);
                context.drawTextWithShadow(textRenderer, catTitle, 0, 0, 0xFFFF55);
                context.getMatrices().pop();

                context.fill(x + 5, y + 24, x + boxW - 5, y + 25, 0x44FFFFFF);

                int startY = y + 28;
                int type = cat.chartType();
                int limit = Math.min(5, cat.data().size());

                if (limit == 0) {
                    context.drawTextWithShadow(textRenderer, "데이터가 없습니다.", x + 10, startY + 4, 0x888888);
                }
                else if (type == 0) {
                    for (int j = 0; j < limit; j++) {
                        TmiEntry e = cat.data().get(j);

                        String line;
                        if (cat.id().equals("ARCHIVED")) {
                            line = e.player() + " | " + e.timeStr();
                        } else if (cat.isRecentLayout()) {
                            boolean boldEntry = cat.id().equals("EVENT_RECORDS") && e.isIndependent();
                            line = (boldEntry ? "§l" : "") + e.rankStr() + " | " + e.player() + " | " + e.timeStr();
                        } else {
                            line = (j + 1) + "위 | " + e.player() + " | " + e.displayValue();
                        }

                        int maxLineW = boxW - 20;
                        int lineW = textRenderer.getWidth(line);
                        float lineScale = 1.0f;
                        if (lineW > maxLineW && maxLineW > 0) {
                            lineScale = (float) maxLineW / lineW;
                        }

                        context.getMatrices().push();
                        context.getMatrices().translate(x + 10, startY + j * 18, 0);
                        context.getMatrices().scale(lineScale, lineScale, 1.0f);
                        String displayLine = textRenderer.trimToWidth(Text.literal(line), (int)(maxLineW / lineScale)).getString();
                        context.drawTextWithShadow(textRenderer, displayLine, 0, 0, 0xDDDDDD);
                        context.getMatrices().pop();
                    }
                }
                else if (type == 1) {
                    double maxVal = 0;
                    for (int j = 0; j < limit; j++) maxVal = Math.max(maxVal, extractNumericValue(cat.data().get(j)));

                    int graphAreaH = 80;
                    int barAreaW = boxW - 20;
                    int singleBarW = barAreaW / 5 - 4;
                    int maxAllowedW = singleBarW + 4;

                    for (int j = 0; j < limit; j++) {
                        TmiEntry e = cat.data().get(j);
                        double val = extractNumericValue(e);
                        int barH = maxVal > 0 ? (int)((val / maxVal) * graphAreaH) : 0;
                        int barX = x + 10 + j * (singleBarW + 4) + (singleBarW / 2);

                        int barColor = CHART_COLORS[j % CHART_COLORS.length];

                        int bBottom = y + boxH - 15;
                        context.fill(barX - (singleBarW / 2), bBottom - barH, barX + (singleBarW / 2), bBottom, barColor & 0x99FFFFFF);

                        String valText = String.valueOf((int)val);
                        int valTextW = textRenderer.getWidth(valText);
                        float valScale = 0.85f;
                        if (valTextW * valScale > maxAllowedW) valScale = (float) maxAllowedW / valTextW;
                        valScale = Math.max(0.4f, valScale);

                        context.getMatrices().push();
                        context.getMatrices().translate(barX, bBottom - barH - 8, 0);
                        context.getMatrices().scale(valScale, valScale, 1.0f);
                        context.drawCenteredTextWithShadow(textRenderer, valText, 0, 0, 0xDDDDDD);
                        context.getMatrices().pop();

                        String fullPlayerName = e.player();
                        int nameW = textRenderer.getWidth(fullPlayerName);
                        float nameScale = 0.8f;
                        if (nameW * nameScale > maxAllowedW) nameScale = (float) maxAllowedW / nameW;
                        nameScale = Math.max(0.35f, nameScale);

                        int unscaledMaxW = (int) (maxAllowedW / nameScale);
                        String displayPlayerName = textRenderer.trimToWidth(Text.literal(fullPlayerName), unscaledMaxW).getString();

                        context.getMatrices().push();
                        context.getMatrices().translate(barX, bBottom + 3, 0);
                        context.getMatrices().scale(nameScale, nameScale, 1.0f);
                        context.drawCenteredTextWithShadow(textRenderer, displayPlayerName, 0, 0, 0xFFFFFF);
                        context.getMatrices().pop();
                    }
                }
                else if (type == 2) {
                    double totalSum = 0;
                    for (TmiEntry e : cat.data()) totalSum += extractNumericValue(e);

                    List<Double> pieValues = new ArrayList<>();
                    List<Integer> pieColors = new ArrayList<>();

                    double top5Sum = 0;
                    for (int j = 0; j < limit; j++) {
                        double val = extractNumericValue(cat.data().get(j));
                        pieValues.add(val);
                        pieColors.add(CHART_COLORS[j]);
                        top5Sum += val;
                    }

                    if (totalSum > top5Sum) {
                        pieValues.add(totalSum - top5Sum);
                        pieColors.add(0xFF555555);
                    }

                    int pieRadius = 40;
                    boolean showLegend = boxW >= 170;
                    int pieCX = showLegend ? (x + pieRadius + 15) : (x + boxW / 2);
                    int pieCY = y + 80;

                    drawPieChart(context, pieCX, pieCY, pieRadius, pieValues, pieColors);

                    if (showLegend) {
                        int legX = pieCX + pieRadius + 15;
                        int legLimit = pieValues.size();
                        for (int j = 0; j < legLimit; j++) {
                            boolean isOther = (j >= limit);
                            String label = isOther ? "기타" : cat.data().get(j).player();
                            int pct = (int) Math.round((pieValues.get(j) / totalSum) * 100);

                            int spacing = legLimit > 5 ? 16 : 18;
                            int rowY = startY + 2 + j * spacing;

                            context.fill(legX, rowY, legX + 8, rowY + 8, pieColors.get(j));
                            context.drawTextWithShadow(textRenderer, textRenderer.trimToWidth(Text.literal(label + " " + pct + "%"), boxW - (legX - x) - 10).getString(), legX + 12, rowY, 0xDDDDDD);
                        }
                    }
                }
            }

            context.getMatrices().pop(); context.disableScissor();

            if (maxGridScroll > 0) {
                int barW = 6; int barX = rightAreaX + rightAreaW - barW - 2; int barY = tableTop; int barH = tableH;
                context.fill(barX, barY, barX + barW, barY + barH, 0x55000000);
                int thumbH = Math.max(20, (int) (barH * ((float) tableH / (totalRows * (boxH + gap)))));
                int thumbY = barY + (int) ((barH - thumbH) * ((float) gridScrollY / maxGridScroll));
                context.fill(barX, thumbY, barX + barW, thumbY + thumbH, 0xFF888888);
            }

        }
        else {
            if (filteredData.isEmpty()) {
                context.drawCenteredTextWithShadow(this.textRenderer, "검색 결과나 데이터가 없습니다.", rightAreaX + rightAreaW / 2, tableTop + 26, 0xFFFFFF);
                return;
            }

            context.fill(rightAreaX, tableTop, rightAreaX + rightAreaW, tableTop + tableH, 0x66000000);
            drawRectBorder(context, rightAreaX, tableTop, rightAreaW, tableH, 0xFF222222);

            int headerRowY = tableTop + 8;

            // ★ 스크롤 한계 보정
            int extraH = 0;
            if (selectedCategory.isRecentLayout() && selectedDetailEntry != null) {
                extraH += 16 * 3;
                boolean showTrack  = rightAreaW > 220;
                boolean showTime   = rightAreaW > 280;
                boolean showBody   = rightAreaW > 350;
                boolean showEngine = rightAreaW > 420;
                int hiddenCount = 0;
                if (!showTrack) hiddenCount++;
                if (!showTime) hiddenCount++;
                if (!showBody) hiddenCount++;
                if (!showEngine) hiddenCount++;
                if (hiddenCount > 0) extraH += hiddenCount * 16 + 5;
                extraH += 26;
            }

            int adjustedCapacityRows = Math.max(1, (tableH - 26 - extraH) / ROW_H) + 1;
            int maxScroll = Math.max(0, filteredData.size() - adjustedCapacityRows + 1); // ★ +1: 마지막 카드 하단 여유공간
            tableScroll = Math.max(0, Math.min(tableScroll, maxScroll));

            int startY = tableTop + 24;
            int start = tableScroll;
            int end = Math.min(start + Math.max(1, (tableH - 26) / ROW_H) + 2, filteredData.size());

            String myName = this.client != null && this.client.getSession() != null ? this.client.getSession().getUsername() : "";

            int cType = selectedCategory.chartType();

            // 툴팁 렌더링을 위해 변수 선언
            String tooltipToDraw = null;

            if (isGraphView && cType == 2) {
                double totalSum = 0;
                for (TmiEntry e : filteredData) totalSum += extractNumericValue(e);

                int pieLimit = Math.min(8, filteredData.size());
                List<Double> pieValues = new ArrayList<>();
                List<Integer> pieColors = new ArrayList<>();
                double topSum = 0;

                for (int j = 0; j < pieLimit; j++) {
                    double val = extractNumericValue(filteredData.get(j));
                    pieValues.add(val);
                    pieColors.add(CHART_COLORS[j % CHART_COLORS.length]);
                    topSum += val;
                }
                if (totalSum > topSum) {
                    pieValues.add(totalSum - topSum);
                    pieColors.add(0xFF555555);
                }

                int pieRadius = Math.min(tableH / 2 - 20, 100);
                int pieCX = rightAreaX + (rightAreaW / 3);
                int pieCY = tableTop + (tableH / 2);
                drawPieChart(context, pieCX, pieCY, pieRadius, pieValues, pieColors);

                int legX = pieCX + pieRadius + 40;
                int legY = tableTop + 20;
                context.drawTextWithShadow(textRenderer, "순위표 (Top 8 및 기타)", legX, legY, 0xFFFF55);

                boolean showHeadPie = selectedCategory.isPlayer() || selectedCategory.isRecentLayout();

                for (int j = 0; j < pieValues.size(); j++) {
                    boolean isOther = (j == pieLimit && totalSum > topSum);
                    String label = isOther ? "나머지 전체 (기타)" : (j + 1) + "위: " + filteredData.get(j).player();
                    double pct = (pieValues.get(j) / totalSum) * 100.0;
                    String valStr = isOther ? String.valueOf((int)(totalSum - topSum)) : filteredData.get(j).displayValue();

                    int rowY = legY + 20 + j * 18;
                    context.fill(legX, rowY, legX + 10, rowY + 10, pieColors.get(j));

                    int textX = legX + 15;
                    if (showHeadPie && !isOther) {
                        int headSize = 12;
                        drawPlayerHead(context, filteredData.get(j).player(), textX, rowY - 1, headSize);
                        textX += headSize + 4;
                    }
                    context.drawTextWithShadow(textRenderer, String.format("%s | %s (%.1f%%)", label, valStr, pct), textX, rowY + 1, 0xFFFFFF);
                }
            }
            else if (isGraphView && cType == 1) {
                double maxVal = 0;
                for (TmiEntry e : filteredData) maxVal = Math.max(maxVal, extractNumericValue(e));

                for (int i = start; i < end; i++) {
                    TmiEntry e = filteredData.get(i); int y = startY + (i - start) * ROW_H; int rank = i + 1;
                    double val = extractNumericValue(e);

                    int maxBarW = rightAreaW - 180;
                    int barW = maxVal > 0 ? (int)((val / maxVal) * maxBarW) : 0;

                    int barColor = (rank == 1) ? 0x66FFD700 : (rank == 2) ? 0x66C0C0C0 : (rank == 3) ? 0x66CD7F32 : 0x44FFFFFF;
                    if (e.player().equalsIgnoreCase(myName)) barColor = 0x6644AA44;

                    if (mouseX >= rightAreaX && mouseX <= rightAreaX + rightAreaW && mouseY >= y - 2 && mouseY <= y + ROW_H - 2) context.fill(rightAreaX + 1, y - 2, rightAreaX + rightAreaW - 1, y + ROW_H - 1, 0x33FFFFFF);

                    context.fill(rightAreaX + 10, y, rightAreaX + 10 + barW, y + ROW_H - 2, barColor);
                    int textY = y + 3;
                    int rankColor = (rank == 1) ? 0xFFFFE066 : (rank == 2) ? 0xFFE6E6E6 : (rank == 3) ? 0xFFFFB36B : 0xFFFFFFFF;

                    context.drawTextWithShadow(this.textRenderer, rank + "위", rightAreaX + 15, textY, rankColor);

                    boolean showHead = selectedCategory.isPlayer() || selectedCategory.isRecentLayout();
                    int nameX = rightAreaX + 55;
                    int nameTextX = nameX;

                    if (showHead) {
                        int headSize = 14;
                        int headX = nameX;
                        int headY = y + (ROW_H - headSize) / 2;
                        drawPlayerHead(context, e.player(), headX, headY, headSize);
                        nameTextX = headX + headSize + 4;
                    }

                    String pName = e.player();
                    context.drawTextWithShadow(this.textRenderer, pName, nameTextX, textY, 0xFFFFFF);

                    int nameEndX = nameTextX + this.textRenderer.getWidth(pName);
                    String valStr = selectedCategory.isRecentLayout() && !selectedCategory.id().equals("ARCHIVED") ? e.timeStr() : e.displayValue();

                    int valX = rightAreaX + 10 + barW + 5;
                    valX = Math.max(valX, nameEndX + 10);

                    context.drawTextWithShadow(this.textRenderer, valStr, valX, textY, 0xDDDDDD);
                }
            }
            else {
                // 리스트 클리핑 영역 활성화
                context.enableScissor(rightAreaX, tableTop + 24, rightAreaX + rightAreaW, tableTop + tableH);

                if (selectedCategory.isRecentLayout()) {
                    boolean isArchived = selectedCategory.id().equals("ARCHIVED");
                    int colRankX   = rightAreaX + (int)(rightAreaW * 0.02);
                    int colPlayerX = isArchived ? rightAreaX + (int)(rightAreaW * 0.05) : rightAreaX + (int)(rightAreaW * 0.12);
                    int colTrackX  = isArchived ? rightAreaX + (int)(rightAreaW * 0.25) : rightAreaX + (int)(rightAreaW * 0.28);
                    int colTimeX   = isArchived ? rightAreaX + (int)(rightAreaW * 0.48) : rightAreaX + (int)(rightAreaW * 0.50);
                    int colBodyX   = isArchived ? rightAreaX + (int)(rightAreaW * 0.65) : rightAreaX + (int)(rightAreaW * 0.65);
                    int colEngineX = rightAreaX + (int)(rightAreaW * 0.85);

                    boolean showTrack  = rightAreaW > 220;
                    boolean showTime   = rightAreaW > 280;
                    boolean showBody   = rightAreaW > 350;
                    boolean showEngine = rightAreaW > 420;

                    float scale = Math.max(0.75f, Math.min(1.0f, rightAreaW / 500.0f));

                    context.getMatrices().push();
                    context.getMatrices().scale(scale, scale, 1.0f);
                    int sHeadY = (int)(headerRowY / scale);

                    if (!isArchived) context.drawTextWithShadow(this.textRenderer, "순위", (int)(colRankX / scale), sHeadY, 0xDDDDDD);
                    context.drawTextWithShadow(this.textRenderer, "플레이어", (int)(colPlayerX / scale), sHeadY, 0xDDDDDD);
                    if (showTrack) context.drawTextWithShadow(this.textRenderer, "트랙", (int)(colTrackX / scale), sHeadY, 0xDDDDDD);
                    if (showTime) context.drawTextWithShadow(this.textRenderer, "기록", (int)(colTimeX / scale), sHeadY, 0xDDDDDD);
                    if (showBody) context.drawTextWithShadow(this.textRenderer, "카트바디", (int)(colBodyX / scale), sHeadY, 0xDDDDDD);
                    if (showEngine) context.drawTextWithShadow(this.textRenderer, "엔진", (int)(colEngineX / scale), sHeadY, 0xDDDDDD);
                    context.getMatrices().pop();

                    int currentY = startY;
                    profileBtnScreenW = 0; // 초기화

                    for (int i = start; i < end; i++) {
                        TmiEntry e = filteredData.get(i);
                        boolean isMe = e.player().equalsIgnoreCase(myName);
                        boolean isIndepEvent = selectedCategory.id().equals("EVENT_RECORDS") && e.isIndependent();
                        boolean isExpanded = (e == selectedDetailEntry);

                        int itemH = ROW_H;
                        int expandH = 0;
                        int hiddenCount = 0;

                        if (isExpanded) {
                            expandH += 16 * 3; // 자기소개, 모드, 날짜 기본
                            if (!showTrack) hiddenCount++;
                            if (!showTime) hiddenCount++;
                            if (!showBody) hiddenCount++;
                            if (!showEngine) hiddenCount++;
                            if (hiddenCount > 0) expandH += hiddenCount * 16 + 5;
                            expandH += 26; // 프로필 가기 버튼 여백
                            itemH += expandH;
                        }

                        if (currentY > tableTop + tableH) break;

                        // ★ 독립 이벤트 기록일 때 마우스 호버 감지하여 커스텀 툴팁 설정
                        boolean isRowHovered = mouseX >= rightAreaX && mouseX <= rightAreaX + rightAreaW && mouseY >= currentY && mouseY < currentY + itemH;
                        if (isIndepEvent && isRowHovered) {
                            tooltipToDraw = "✨ 이벤트 랭킹에만 등록된 기록";
                        }

                        int bgColor = 0x00000000;
                        if (isExpanded) bgColor = 0x550B0B0B;
                        else if (isMe) bgColor = 0x6644AA44;
                        else if (((i - start) & 1) == 1) bgColor = 0x22000000;

                        context.fill(rightAreaX + 1, currentY, rightAreaX + rightAreaW - 1, currentY + itemH, bgColor);
                        if (isExpanded) {
                            drawRectBorder(context, rightAreaX + 1, currentY, rightAreaW - 2, itemH, 0xFF444444);
                        }

                        boolean hover = !isExpanded && mouseX >= rightAreaX && mouseX <= rightAreaX + rightAreaW && mouseY >= currentY && mouseY < currentY + itemH;
                        if (hover) {
                            context.fill(rightAreaX + 1, currentY, rightAreaX + rightAreaW - 1, currentY + itemH, 0x33FFFFFF);
                        }

                        context.getMatrices().push();
                        context.getMatrices().scale(scale, scale, 1.0f);
                        int sY = (int)((currentY + (ROW_H - 10 * scale) / 2) / scale);

                        if (!isArchived) context.drawTextWithShadow(this.textRenderer, (isIndepEvent ? "§l" : "") + e.rankStr(), (int)(colRankX / scale), sY, 0xFFFFFF);

                        int nextCol1 = showTrack ? colTrackX : (showTime ? colTimeX : (showBody ? colBodyX : (showEngine ? colEngineX : rightAreaX + rightAreaW)));

                        int headSize = 14;
                        int localHeadX = (int)(colPlayerX / scale);
                        int localHeadY = (int)((currentY + (ROW_H - headSize * scale) / 2) / scale);
                        drawPlayerHead(context, e.player(), localHeadX, localHeadY, headSize);
                        int nameTextX = localHeadX + headSize + 4;

                        int maxPlayerW = Math.max(0, (int)((nextCol1 - colPlayerX) / scale) - headSize - 4 - 5);

                        String repText = ""; if (isExpanded && selectedRepTitle != null && !selectedRepTitle.isEmpty()) repText = " [" + selectedRepTitle + "]";

                        if (isExpanded && !repText.isEmpty()) {
                            int maxNameW = Math.max(0, maxPlayerW - textRenderer.getWidth(repText));
                            if (maxNameW <= 0) {
                                context.drawTextWithShadow(textRenderer, trimWithEllipsis((isIndepEvent ? "§l" : "") + e.player() + repText, maxPlayerW), nameTextX, sY, 0xFFFFFF);
                            } else {
                                String trimmedName = trimWithEllipsis((isIndepEvent ? "§l" : "") + e.player(), maxNameW);
                                context.drawTextWithShadow(textRenderer, trimmedName, nameTextX, sY, 0xFFFFFF);
                                context.drawTextWithShadow(textRenderer, repText, nameTextX + textRenderer.getWidth(trimmedName), sY, parseHex(selectedRepColor, 0x55FFFF));
                            }
                        } else {
                            context.drawTextWithShadow(this.textRenderer, trimWithEllipsis((isIndepEvent ? "§l" : "") + e.player(), maxPlayerW), nameTextX, sY, 0xFFFFFF);
                        }

                        boolean hiddenSomething = false;

                        if (showTrack) {
                            int nextCol2 = showTime ? colTimeX : (showBody ? colBodyX : (showEngine ? colEngineX : rightAreaX + rightAreaW));
                            int maxTrackW = Math.max(0, (int)((nextCol2 - colTrackX - 5) / scale));
                            context.drawTextWithShadow(this.textRenderer, trimWithEllipsis(e.track(), maxTrackW), (int)(colTrackX / scale), sY, 0xFFFFFF);
                        } else hiddenSomething = true;

                        if (showTime) {
                            int nextCol3 = showBody ? colBodyX : (showEngine ? colEngineX : rightAreaX + rightAreaW);
                            int maxTimeW = Math.max(0, (int)((nextCol3 - colTimeX - 5) / scale));
                            context.drawTextWithShadow(this.textRenderer, trimWithEllipsis(e.timeStr(), maxTimeW), (int)(colTimeX / scale), sY, 0xFFFFFF);
                        } else hiddenSomething = true;

                        if (showBody) {
                            int nextCol4 = showEngine ? colEngineX : rightAreaX + rightAreaW;
                            int maxBodyW = Math.max(0, (int)((nextCol4 - colBodyX - 5) / scale));
                            context.drawTextWithShadow(this.textRenderer, trimWithEllipsis(TireUtil.composeBodyLabel(e.body(), e.tire()), maxBodyW), (int)(colBodyX / scale), sY, 0xFFFFFF);
                        } else hiddenSomething = true;

                        if (showEngine) {
                            int maxEngW = Math.max(0, (int)((rightAreaX + rightAreaW - colEngineX - 5) / scale));
                            String engStr = e.engine().replace("엔진", "").replace("ENGINE", "").replace("engine", "").replace("[", "").replace("]", "").trim();
                            context.drawTextWithShadow(this.textRenderer, trimWithEllipsis(engStr, maxEngW), (int)(colEngineX / scale), sY, 0xFFFFFF);
                        } else hiddenSomething = true;

                        if (hiddenSomething) {
                            context.drawTextWithShadow(this.textRenderer, "+", (int)((rightAreaX + rightAreaW - 15) / scale), sY, 0xAAAAAA);
                        }
                        context.getMatrices().pop();

                        // ★ 아코디언 내용 렌더링
                        if (isExpanded) {
                            context.getMatrices().push();
                            context.getMatrices().scale(scale, scale, 1.0f);

                            int infoX = (int)((colPlayerX + 5) / scale);
                            int infoY = sY + 20;
                            int lineH = 16;
                            int maxW = (int)((rightAreaW - 50) / scale);

                            context.drawTextWithShadow(textRenderer, trimWithEllipsis("§7" + selectedProfileDesc, maxW), infoX, infoY, 0xAAAAAA);
                            infoY += lineH;
                            infoY += 5; // 여백

                            if (hiddenCount > 0) {
                                if (!showTrack) {
                                    context.drawTextWithShadow(textRenderer, "§8트랙: §f" + e.track(), infoX, infoY, 0xFFFFFF);
                                    infoY += lineH;
                                }
                                if (!showTime) {
                                    context.drawTextWithShadow(textRenderer, "§8기록: §e" + e.timeStr(), infoX, infoY, 0xFFFFFF);
                                    infoY += lineH;
                                }
                                if (!showBody) {
                                    context.drawTextWithShadow(textRenderer, "§8카트: §f" + TireUtil.composeBodyLabel(e.body(), e.tire()), infoX, infoY, 0xFFFFFF);
                                    infoY += lineH;
                                }
                                if (!showEngine) {
                                    String eng = e.engine().replace("엔진", "").replace("ENGINE", "").replace("engine", "").replace("[", "").replace("]", "").trim();
                                    context.drawTextWithShadow(textRenderer, "§8엔진: §f" + eng, infoX, infoY, 0xFFFFFF);
                                    infoY += lineH;
                                }
                            }

                            context.drawTextWithShadow(textRenderer, "§8모드: §f" + (e.modes() == null || e.modes().isEmpty() ? "없음" : e.modes()), infoX, infoY, 0xFFFFFF);
                            infoY += lineH;
                            String dateStr = formatDateTimeFull(e.submittedAtMs());
                            context.drawTextWithShadow(textRenderer, "§8등록: §7" + dateStr, infoX, infoY, 0xAAAAAA);

                            context.getMatrices().pop();

                            // 수동 프로필 버튼 렌더링
                            int btnW = 80;
                            int btnH = 20;
                            int btnX = rightAreaX + 10;
                            int btnY = currentY + itemH - 24;

                            profileBtnScreenX = btnX;
                            profileBtnScreenY = btnY;
                            profileBtnScreenW = btnW;
                            profileBtnScreenH = btnH;

                            boolean hoverBtn = isInside(mouseX, mouseY, btnX, btnY, btnW, btnH);
                            context.fill(btnX, btnY, btnX + btnW, btnY + btnH, hoverBtn ? 0xFF444444 : 0xFF222222);
                            drawRectBorder(context, btnX, btnY, btnW, btnH, hoverBtn ? 0xFF888888 : 0xFF555555);

                            int btnHeadSize = 14;
                            String btnLabel = "프로필";
                            int groupW = btnHeadSize + 4 + textRenderer.getWidth(btnLabel);
                            int groupX = btnX + (btnW - groupW) / 2;
                            int groupY = btnY + (btnH - btnHeadSize) / 2;
                            drawPlayerHead(context, e.player(), groupX, groupY, btnHeadSize);
                            context.drawTextWithShadow(textRenderer, btnLabel, groupX + btnHeadSize + 4, groupY + (btnHeadSize - 8) / 2, 0xFFFFFF);
                            // 툴팁
                            if (hoverBtn) renderTooltip(context, net.minecraft.text.Text.literal("프로필 보기"), mouseX, mouseY);
                        }

                        currentY += itemH;
                    }
                } else {
                    int colRankX   = rightAreaX + (int)(rightAreaW * 0.05);
                    int colPlayerX = rightAreaX + (int)(rightAreaW * 0.25);
                    int colValueX  = rightAreaX + (int)(rightAreaW * 0.70);

                    float scale = Math.max(0.8f, Math.min(1.0f, rightAreaW / 400.0f));

                    context.getMatrices().push();
                    context.getMatrices().scale(scale, scale, 1.0f);
                    int sHeadY = (int)(headerRowY / scale);

                    context.drawTextWithShadow(this.textRenderer, "순위", (int)(colRankX / scale), sHeadY, 0xDDDDDD);
                    context.drawTextWithShadow(this.textRenderer, selectedCategory.isPlayer() ? "플레이어" : "항목 이름", (int)(colPlayerX / scale), sHeadY, 0xDDDDDD);
                    context.drawTextWithShadow(this.textRenderer, selectedCategory.name(), (int)(colValueX / scale), sHeadY, 0xDDDDDD);
                    context.getMatrices().pop();

                    for (int i = start; i < end; i++) {
                        TmiEntry e = filteredData.get(i); int y = startY + (i - start) * ROW_H; int rank = i + 1;
                        boolean isMe = selectedCategory.isPlayer() && e.player().equalsIgnoreCase(myName);

                        if (isMe) context.fill(rightAreaX + 1, y - 2, rightAreaX + rightAreaW - 1, y + ROW_H - 1, 0x6644AA44);
                        else if (rank == 1) context.fill(rightAreaX + 1, y - 2, rightAreaX + rightAreaW - 1, y + ROW_H - 1, 0x44FFD700);
                        else if (rank == 2) context.fill(rightAreaX + 1, y - 2, rightAreaX + rightAreaW - 1, y + ROW_H - 1, 0x44C0C0C0);
                        else if (rank == 3) context.fill(rightAreaX + 1, y - 2, rightAreaX + rightAreaW - 1, y + ROW_H - 1, 0x44CD7F32);
                        else if (((i - start) & 1) == 1) context.fill(rightAreaX + 1, y - 2, rightAreaX + rightAreaW - 1, y + ROW_H - 1, 0x22000000);

                        if (mouseX >= rightAreaX && mouseX <= rightAreaX + rightAreaW && mouseY >= y - 2 && mouseY <= y + ROW_H - 2) {
                            context.fill(rightAreaX + 1, y - 2, rightAreaX + rightAreaW - 1, y + ROW_H - 1, 0x33FFFFFF);
                        }

                        context.getMatrices().push();
                        context.getMatrices().scale(scale, scale, 1.0f);
                        int sY = (int)((y + (ROW_H - 10 * scale) / 2) / scale);

                        int rankColor = (rank == 1) ? 0xFFFFE066 : (rank == 2) ? 0xFFE6E6E6 : (rank == 3) ? 0xFFFFB36B : 0xFFFFFFFF;
                        context.drawTextWithShadow(this.textRenderer, rank + "위", (int)(colRankX / scale), sY, rankColor);

                        int maxPlayerW = Math.max(0, (int)((colValueX - colPlayerX - 5) / scale));
                        int nameTextX = (int)(colPlayerX / scale);
                        if (selectedCategory.isPlayer()) {
                            int headSize = 14;
                            int localHeadX = (int)(colPlayerX / scale);
                            int localHeadY = (int)((y + (ROW_H - headSize * scale) / 2) / scale);
                            drawPlayerHead(context, e.player(), localHeadX, localHeadY, headSize);
                            nameTextX = localHeadX + headSize + 4;
                            maxPlayerW = Math.max(0, maxPlayerW - headSize - 4);
                        }
                        context.drawTextWithShadow(this.textRenderer, trimWithEllipsis(e.player(), maxPlayerW), nameTextX, sY, 0xFFFFFF);

                        int maxValW = Math.max(0, (int)((rightAreaX + rightAreaW - colValueX - 5) / scale));
                        context.drawTextWithShadow(this.textRenderer, trimWithEllipsis(e.displayValue(), maxValW), (int)(colValueX / scale), sY, 0xFFFFFF);

                        context.getMatrices().pop();
                    }
                }

                context.disableScissor();
            }

            if (!(isGraphView && cType == 2)) {
                if (maxScroll > 0) {
                    int barW = 6; int barX = rightAreaX + rightAreaW - barW - 2; int barY = tableTop + 24; int barH = tableH - 26;
                    context.fill(barX, barY, barX + barW, barY + barH, 0x55000000);
                    int thumbH = Math.max(10, (int) (barH * ((float) (tableH - 26) / (ROW_H * filteredData.size()))));
                    int thumbY = barY + (int) ((barH - thumbH) * ((float) tableScroll / maxScroll));
                    context.fill(barX, thumbY, barX + barW, thumbY + thumbH, 0xFF888888);
                }
            }

            // ★ Scissor(클리핑) 해제 후 커스텀 툴팁 렌더링 (목록 위에 온전히 뜨게 만듦)
            if (tooltipToDraw != null) {
                renderCustomTooltip(context, tooltipToDraw, mouseX, mouseY);
            }
        }

        context.drawCenteredTextWithShadow(this.textRenderer, String.format("총 카테고리/데이터 뷰"), rightAreaX + rightAreaW / 2, this.height - 26, 0xAAAAAA);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (isNewUi() && sharedSidebar != null && sharedSidebar.mouseClicked(mouseX, mouseY, button)) return true;

        if (!isNewUi()) {
            int sbX = OUTER_PAD; int sbY = OUTER_PAD; int sbH = this.height - OUTER_PAD * 2;
            int curSidebarW = getLegacySidebarWidth();
            if (mouseX >= sbX && mouseX <= sbX + curSidebarW && mouseY >= sbY + 24 && mouseY <= sbY + sbH) {
                double adjustedY = mouseY + legacySidebarScroll;
                int sy = OUTER_PAD + 83;
                for (MainCat mc : mainCats) {
                    if (mouseX >= sbX && mouseX <= sbX + curSidebarW && adjustedY >= sy && adjustedY <= sy + 30) {
                        playUiClick(); setSelectedMainCat(mc.id()); return true;
                    }
                    sy += 35;
                }
            }
        }

        int effectiveLeftW = getEffectiveSidebarWidth();
        int rightAreaX = OUTER_PAD + effectiveLeftW + 8;
        int rightAreaW = this.width - OUTER_PAD - rightAreaX;
        int tableTop = isDetailView ? (OUTER_PAD + HEADER_H + 8) : (OUTER_PAD + 4);
        int tableBottom = this.height - 46;
        int tableH = Math.max(80, tableBottom - tableTop);

        if (!loading && error == null && mouseX >= rightAreaX && mouseX <= rightAreaX + rightAreaW && mouseY >= tableTop && mouseY <= tableTop + tableH) {
            if (!isDetailView) {
                List<TmiCategory> subs = getSubCategories();
                int columns = 2; int gap = 12; int boxW = (rightAreaW - gap) / 2; int boxH = 145;

                for (int i = 0; i < subs.size(); i++) {
                    TmiCategory cat = subs.get(i);
                    int col = i % columns; int row = i / columns;
                    int x = rightAreaX + col * (boxW + gap);
                    int y = tableTop + row * (boxH + gap) - gridScrollY;

                    if (mouseX >= x && mouseX <= x + boxW && mouseY >= y && mouseY <= y + boxH) {
                        playUiClick();
                        selectedCategory = cat;
                        isDetailView = true;
                        isGraphView = false;
                        if (toggleGraphBtn != null) toggleGraphBtn.setMessage(Text.literal("📊 그래프 뷰"));
                        tableScroll = 0;
                        if (searchBox != null) searchBox.setText("");
                        updateFilteredData();
                        updateUIVisibility();
                        return true;
                    }
                }
            }
            else if (selectedCategory != null && !filteredData.isEmpty() && !(isGraphView && selectedCategory.chartType() == 2)) {

                // ★ 1. 아코디언 내부 프로필 가기 버튼 클릭
                if (selectedDetailEntry != null && profileBtnScreenW > 0) {
                    if (isInside(mouseX, mouseY, profileBtnScreenX, profileBtnScreenY, profileBtnScreenW, profileBtnScreenH)) {
                        playUiClick();
                        if (this.client != null) {
                            this.client.setScreen(new PlayerProfileScreen(selectedDetailEntry.player(), this));
                        }
                        return true;
                    }
                }

                // ★ 스크롤 한계 보정 후 클릭 연산
                int extraH = 0;
                if (selectedCategory.isRecentLayout() && selectedDetailEntry != null) {
                    extraH += 16 * 3;
                    boolean showTrack  = rightAreaW > 220;
                    boolean showTime   = rightAreaW > 280;
                    boolean showBody   = rightAreaW > 350;
                    boolean showEngine = rightAreaW > 420;
                    int hiddenCount = 0;
                    if (!showTrack) hiddenCount++;
                    if (!showTime) hiddenCount++;
                    if (!showBody) hiddenCount++;
                    if (!showEngine) hiddenCount++;
                    if (hiddenCount > 0) extraH += hiddenCount * 16 + 5;
                    extraH += 26;
                }

                int adjustedCapacityRows = Math.max(1, (tableH - 26 - extraH) / ROW_H) + 1;
                int maxScroll = Math.max(0, filteredData.size() - adjustedCapacityRows + 1); // ★ +1: 마지막 카드 하단 여유공간
                tableScroll = Math.max(0, Math.min(tableScroll, maxScroll));

                int startY = tableTop + 24;
                int start = tableScroll;
                int end = Math.min(start + Math.max(1, (tableH - 26) / ROW_H) + 2, filteredData.size());

                int currentY = startY;

                for (int i = start; i < end; i++) {
                    TmiEntry e = filteredData.get(i);
                    int itemH = ROW_H;

                    if (selectedCategory.isRecentLayout() && e == selectedDetailEntry) {
                        int expandH = 16 * 3;
                        boolean showTrack  = rightAreaW > 220;
                        boolean showTime   = rightAreaW > 280;
                        boolean showBody   = rightAreaW > 350;
                        boolean showEngine = rightAreaW > 420;

                        int hiddenCount = 0;
                        if (!showTrack) hiddenCount++;
                        if (!showTime) hiddenCount++;
                        if (!showBody) hiddenCount++;
                        if (!showEngine) hiddenCount++;
                        if (hiddenCount > 0) expandH += hiddenCount * 16 + 5;

                        expandH += 26; // 버튼 여백
                        itemH += expandH;
                    }

                    if (currentY > tableTop + tableH) break;

                    if (mouseY >= currentY - 2 && mouseY <= currentY + itemH - 2) {
                        playUiClick();

                        if (selectedCategory.isRecentLayout()) {
                            if (selectedDetailEntry == e) {
                                selectedDetailEntry = null; // 닫기
                            } else {
                                selectedDetailEntry = e;
                                selectedProfileDesc = "불러오는 중...";
                                selectedRepTitle = "";
                                fetchProfileDesc(e.player());
                            }
                            return true;
                        }
                        else if (selectedCategory.isPlayer()) {
                            String targetPlayer = e.player();
                            if (this.client != null) this.client.setScreen(new PlayerProfileScreen(targetPlayer, this));
                            return true;
                        }
                    }
                    currentY += itemH;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean shouldPause() { return false; }
}