package com.example.rankinglog;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.Drawable;
import net.minecraft.client.gui.Element;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.sound.SoundEvents;

import java.util.*;

public class ModSettingsScreen extends Screen {

    private final Screen parent;

    // 트랙 드롭다운
    private boolean trackDropdownOpen = false;
    private TextFieldWidget trackSearchBox;
    private final List<String> allTracks = new ArrayList<>();
    private final List<String> filteredTracks = new ArrayList<>();
    private int trackScroll = 0;
    private ButtonWidget trackToggleBtn;

    // 엔진 드롭다운
    private boolean engineDropdownOpen = false;
    private TextFieldWidget engineSearchBox;
    private final List<String> allEngines = new ArrayList<>();
    private final List<String> filteredEngines = new ArrayList<>();
    private int engineScroll = 0;
    private ButtonWidget engineToggleBtn;

    // 타이어 및 모드 드롭다운
    private boolean tireDropdownOpen = false;
    private final List<String> ALL_TIRES = List.of("ALL", "레이싱 타이어", "스파이크 타이어");
    private ButtonWidget tireToggleBtn;

    private boolean modeDropdownOpen = false;
    private ButtonWidget modeToggleBtn;

    // 기능 섹션 토글 (알약형 ON/OFF)
    private ButtonWidget autoSubmitBtn;
    private ButtonWidget debugLogBtn;
    private ButtonWidget showMainScreenBtn;
    private ButtonWidget toastToggleBtn;

    // 화면 섹션
    private ButtonWidget toastPosBtn;
    private SliderWidget bgAlphaSlider;
    private SliderWidget cacheTtlSlider;
    private ButtonWidget uiThemeBtn;
    private ButtonWidget clearCacheBtn;

    // 뒤로 가기
    private ButtonWidget backBtn;

    // 사이드바
    private SharedSidebar sharedSidebar;

    private static final int OUTER_PAD = 12;
    private static final int TOGGLE_W  = 46;
    private static final int ROW_H     = 22;
    private static final int ROW_BTN_H = 20;

    // ★ 스크롤 콘텐츠 총 높이
    private static final int TOTAL_CONTENT_H = 14 + ROW_H * 5 + 15 + 14 + ROW_H * 4 + 20;

    // ★ 스크롤 관리를 위한 변수
    private double scrollAmount = 0;
    private int maxScroll = 0;
    private int currentPanelY = 0;
    private int currentPanelH = 0;
    private int contentStartY = 0;

    public ModSettingsScreen(Screen parent) {
        super(Text.literal("MCRiderRanking 설정"));
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

    private static String getModVersion() {
        Optional<ModContainer> c = FabricLoader.getInstance().getModContainer("modid");
        return c.map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse("1.6.99");
    }

    private void playUiClick() {
        if (this.client != null)
            this.client.getSoundManager().play(PositionedSoundInstance.master(SoundEvents.UI_BUTTON_CLICK, 1.0f));
    }

    private void closeAllDropdowns() {
        trackDropdownOpen = false; engineDropdownOpen = false;
        tireDropdownOpen  = false; modeDropdownOpen  = false;
        if (trackSearchBox  != null) { trackSearchBox.setVisible(false);  trackSearchBox.setFocused(false); }
        if (engineSearchBox != null) { engineSearchBox.setVisible(false); engineSearchBox.setFocused(false); }
    }

    private void updateButtonTexts() {
        if (trackToggleBtn  != null) trackToggleBtn.setMessage(Text.literal("기본 트랙: " + ModConfig.get().defaultTrack));
        if (engineToggleBtn != null) engineToggleBtn.setMessage(Text.literal("기본 엔진: " + ModConfig.get().defaultEngine));
        if (tireToggleBtn   != null) tireToggleBtn.setMessage(Text.literal("기본 타이어: " + ModConfig.get().defaultTire));
        if (modeToggleBtn   != null) {
            List<String> modes = ModConfig.get().defaultModes;
            String s = modes.isEmpty() ? "없음" : modes.size() == 1 ? modes.get(0) : modes.get(0) + " +" + (modes.size() - 1);
            modeToggleBtn.setMessage(Text.literal("기본 모드: " + s));
        }
    }

    // ── 텍스트 헬퍼 ────────────────────────────────────────────
    private static Text toggleText(boolean on) {
        return Text.literal(on ? "§aON" : "§cOFF");
    }
    private Text getBgAlphaText() {
        return Text.literal("배경 불투명도: " + (int) Math.round(ModConfig.get().backgroundAlpha / 255.0 * 100) + "%");
    }
    private Text getCacheTtlText() {
        int s = ModConfig.get().cacheTtlSeconds;
        return Text.literal("캐시 갱신: §e" + (s >= 60 ? (s / 60) + "분 " + (s % 60) + "초" : s + "초"));
    }
    private Text getUiThemeText() {
        return Text.literal("UI: " + (ModConfig.get().useLegacyUi ? "§e레거시" : "§aNEW"));
    }
    private Text getToastPosText() {
        String[] names = {"우측 상단", "좌측 상단", "우측 하단", "좌측 하단"};
        int p = Math.max(0, Math.min(3, ModConfig.get().toastPosition));
        return Text.literal("알림 위치: §e" + names[p]);
    }

    // ── 초기화 ──────────────────────────────────────────────────
    @Override
    protected void init() {
        if (isNewUi()) {
            sharedSidebar = new SharedSidebar(
                    this, "SETTING", null, false,
                    catId -> { if (client != null) client.setScreen(new TmiRankingScreen(new MainMenuScreen(), catId)); },
                    () -> {},
                    this::repositionUiElements
            );
        }

        // ── 기능 섹션: 알약형 토글 ──
        autoSubmitBtn = this.addDrawableChild(ButtonWidget.builder(toggleText(ModConfig.get().autoSubmitEnabled), btn -> {
            playUiClick();
            ModConfig.get().autoSubmitEnabled = !ModConfig.get().autoSubmitEnabled;
            ModConfig.save(); btn.setMessage(toggleText(ModConfig.get().autoSubmitEnabled));
        }).dimensions(0, 0, TOGGLE_W, ROW_BTN_H).build());

        debugLogBtn = this.addDrawableChild(ButtonWidget.builder(toggleText(ModConfig.get().debugLogEnabled), btn -> {
            playUiClick();
            ModConfig.get().debugLogEnabled = !ModConfig.get().debugLogEnabled;
            ModConfig.save(); btn.setMessage(toggleText(ModConfig.get().debugLogEnabled));
        }).dimensions(0, 0, TOGGLE_W, ROW_BTN_H).build());

        showMainScreenBtn = this.addDrawableChild(ButtonWidget.builder(toggleText(ModConfig.get().showMainScreen), btn -> {
            playUiClick();
            ModConfig.get().showMainScreen = !ModConfig.get().showMainScreen;
            ModConfig.save(); btn.setMessage(toggleText(ModConfig.get().showMainScreen));

            if (this.client != null) {
                this.client.setScreen(new ModSettingsScreen(this.parent));
            }
        }).dimensions(0, 0, TOGGLE_W, ROW_BTN_H).build());
        showMainScreenBtn.active = !ModConfig.get().useLegacyUi;

        toastToggleBtn = this.addDrawableChild(ButtonWidget.builder(toggleText(ModConfig.get().showToasts), btn -> {
            playUiClick();
            ModConfig.get().showToasts = !ModConfig.get().showToasts;
            ModConfig.save(); btn.setMessage(toggleText(ModConfig.get().showToasts));
        }).dimensions(0, 0, TOGGLE_W, ROW_BTN_H).build());

        toastPosBtn = this.addDrawableChild(ButtonWidget.builder(getToastPosText(), btn -> {
            playUiClick();
            ModConfig.get().toastPosition = (ModConfig.get().toastPosition + 1) % 4;
            ModConfig.save(); btn.setMessage(getToastPosText());
        }).dimensions(0, 0, 180, ROW_BTN_H).build());

        // ── 화면 섹션 ──
        double initAlpha = ModConfig.get().backgroundAlpha / 255.0;
        bgAlphaSlider = this.addDrawableChild(new SliderWidget(0, 0, 180, ROW_BTN_H, getBgAlphaText(), initAlpha) {
            @Override protected void updateMessage() { setMessage(getBgAlphaText()); }
            @Override protected void applyValue() { ModConfig.get().backgroundAlpha = (int)(this.value * 255); ModConfig.save(); }
        });

        double minTtl = 30.0, maxTtl = 600.0;
        double initTtl = (ModConfig.get().cacheTtlSeconds - minTtl) / (maxTtl - minTtl);
        cacheTtlSlider = this.addDrawableChild(new SliderWidget(0, 0, 180, ROW_BTN_H, getCacheTtlText(), initTtl) {
            @Override protected void updateMessage() { setMessage(getCacheTtlText()); }
            @Override protected void applyValue() { ModConfig.get().cacheTtlSeconds = (int)(minTtl + this.value * (maxTtl - minTtl)); ModConfig.save(); }
        });

        uiThemeBtn = this.addDrawableChild(ButtonWidget.builder(getUiThemeText(), btn -> {
            playUiClick();
            boolean turningLegacy = !ModConfig.get().useLegacyUi;
            if (turningLegacy) {
                ModConfig.get().savedShowMainScreen = ModConfig.get().showMainScreen;
                ModConfig.get().showMainScreen = true;
            } else {
                ModConfig.get().showMainScreen = ModConfig.get().savedShowMainScreen;
            }
            ModConfig.get().useLegacyUi = turningLegacy;
            ModConfig.save();
            if (this.client != null) this.client.setScreen(new ModSettingsScreen(this.parent));
        }).dimensions(0, 0, 180, ROW_BTN_H).build());

        clearCacheBtn = this.addDrawableChild(ButtonWidget.builder(Text.literal("⚠ 캐시 초기화"), btn -> {
            playUiClick();
            RankingScreen.ApiCache.clearCache(); EventOptionSelectScreen.clearCache(); EventRankingScreen.clearCache();
            if (this.client != null && this.client.player != null)
                this.client.player.sendMessage(Text.literal("§e[System] 모든 캐시가 초기화되었습니다."), false);
        }).dimensions(0, 0, 180, ROW_BTN_H).build());

        // ── 기본값 섹션: 드롭다운 ──
        trackToggleBtn = ButtonWidget.builder(Text.empty(), btn -> {
            playUiClick(); boolean was = trackDropdownOpen; closeAllDropdowns();
            if (!was) { trackDropdownOpen = true; trackSearchBox.setVisible(true); trackSearchBox.setText(""); trackSearchBox.setFocused(true); setFocused(trackSearchBox); applyTrackSearch(); }
        }).dimensions(0, 0, 180, ROW_BTN_H).build();
        this.addDrawableChild(trackToggleBtn);

        engineToggleBtn = ButtonWidget.builder(Text.empty(), btn -> {
            playUiClick(); boolean was = engineDropdownOpen; closeAllDropdowns();
            if (!was) { engineDropdownOpen = true; engineSearchBox.setVisible(true); engineSearchBox.setText(""); engineSearchBox.setFocused(true); setFocused(engineSearchBox); applyEngineSearch(); }
        }).dimensions(0, 0, 180, ROW_BTN_H).build();
        this.addDrawableChild(engineToggleBtn);

        tireToggleBtn = ButtonWidget.builder(Text.empty(), btn -> {
            playUiClick(); boolean was = tireDropdownOpen; closeAllDropdowns(); tireDropdownOpen = !was;
        }).dimensions(0, 0, 180, ROW_BTN_H).build();
        this.addDrawableChild(tireToggleBtn);

        modeToggleBtn = ButtonWidget.builder(Text.empty(), btn -> {
            playUiClick(); boolean was = modeDropdownOpen; closeAllDropdowns(); modeDropdownOpen = !was;
        }).dimensions(0, 0, 180, ROW_BTN_H).build();
        this.addDrawableChild(modeToggleBtn);

        backBtn = ButtonWidget.builder(Text.literal("⏴"), btn -> {
            playUiClick();
            if (ModConfig.get().showMainScreen) { if (this.client != null) this.client.setScreen(this.parent); }
            else close();
        }).dimensions(0, 0, 20, 20).tooltip(net.minecraft.client.gui.tooltip.Tooltip.of(Text.literal("뒤로 가기"))).build();
        this.addSelectableChild(backBtn);

        updateButtonTexts();

        trackSearchBox = new TextFieldWidget(this.textRenderer, 0, 0, 160, 14, Text.literal("트랙 검색"));
        trackSearchBox.setMaxLength(64); trackSearchBox.setVisible(false);
        trackSearchBox.setChangedListener(s -> { trackScroll = 0; applyTrackSearch(); });
        this.addSelectableChild(trackSearchBox);

        engineSearchBox = new TextFieldWidget(this.textRenderer, 0, 0, 160, 14, Text.literal("엔진 검색"));
        engineSearchBox.setMaxLength(32); engineSearchBox.setVisible(false);
        engineSearchBox.setChangedListener(s -> { engineScroll = 0; applyEngineSearch(); });
        this.addSelectableChild(engineSearchBox);

        repositionUiElements();

        if (RankingScreen.ApiCache.isAllReady()) loadTracksFromCache();
        else RankingScreen.ApiCache.fetchAllAsync(false, p -> loadTracksFromCache(), err -> {});
        fetchAllEnginesAsync();
    }

    // ── 가시성 체크 헬퍼 (스크롤 밖으로 나간 버튼 비활성화 방지용) ──
    private void updateBtnVis(ButtonWidget btn) {
        if (btn != null) btn.visible = btn.getY() < currentPanelY + currentPanelH && btn.getY() + btn.getHeight() > currentPanelY;
    }
    private void updateSldVis(SliderWidget sld) {
        if (sld != null) sld.visible = sld.getY() < currentPanelY + currentPanelH && sld.getY() + sld.getHeight() > currentPanelY;
    }

    // ★ 설정 박스 반응형 사이즈 및 스크롤 포지션 동적 업데이트
    private void repositionUiElements() {
        int effectiveLeftW = getEffectiveSidebarWidth();
        int rightAreaX = effectiveLeftW == 0 ? OUTER_PAD : OUTER_PAD + effectiveLeftW + 8;
        int rightAreaW = this.width - OUTER_PAD - rightAreaX;

        int headerH = 50;
        currentPanelY = 10 + headerH + 10;

        // 화면 크기에 맞춰 박스 크기가 동적으로 변경됨 (최소 80px 보장)
        currentPanelH = Math.max(80, this.height - 40 - currentPanelY);
        int panelW = rightAreaW;

        int cx = rightAreaX + panelW / 2;
        int colW = Math.max(120, Math.min(220, (panelW - 40) / 2));
        int leftX = cx - colW - 10;
        int rightX = cx + 10;

        // 동적으로 스크롤 최대치 계산
        maxScroll = Math.max(0, TOTAL_CONTENT_H - currentPanelH);
        scrollAmount = Math.max(0, Math.min(scrollAmount, maxScroll));

        contentStartY = currentPanelY + 15 - (int)scrollAmount;

        int funcY = contentStartY + 14;

        if (autoSubmitBtn != null) {
            autoSubmitBtn.setPosition(leftX + colW - TOGGLE_W, funcY);
            debugLogBtn.setPosition(leftX + colW - TOGGLE_W, funcY + ROW_H);
            showMainScreenBtn.setPosition(leftX + colW - TOGGLE_W, funcY + ROW_H * 2);
            toastToggleBtn.setPosition(leftX + colW - TOGGLE_W, funcY + ROW_H * 3);
            toastPosBtn.setPosition(leftX, funcY + ROW_H * 4); toastPosBtn.setWidth(colW);

            int screenLabelY = funcY + ROW_H * 5 + 15;
            int screenY = screenLabelY + 14;
            bgAlphaSlider.setPosition(leftX, screenY);                bgAlphaSlider.setWidth(colW);
            cacheTtlSlider.setPosition(leftX, screenY + ROW_H);      cacheTtlSlider.setWidth(colW);
            uiThemeBtn.setPosition(leftX, screenY + ROW_H * 2);      uiThemeBtn.setWidth(colW);
            clearCacheBtn.setPosition(leftX, screenY + ROW_H * 3);   clearCacheBtn.setWidth(colW);

            int defY = contentStartY + 14;
            trackToggleBtn.setPosition(rightX, defY);              trackToggleBtn.setWidth(colW);
            engineToggleBtn.setPosition(rightX, defY + ROW_H);    engineToggleBtn.setWidth(colW);
            tireToggleBtn.setPosition(rightX, defY + ROW_H * 2);  tireToggleBtn.setWidth(colW);
            modeToggleBtn.setPosition(rightX, defY + ROW_H * 3);  modeToggleBtn.setWidth(colW);

            trackSearchBox.setX(rightX + 10);  trackSearchBox.setY(defY + 10);          trackSearchBox.setWidth(colW - 20);
            engineSearchBox.setX(rightX + 10); engineSearchBox.setY(defY + ROW_H + 10); engineSearchBox.setWidth(colW - 20);

            // 가려진 부분의 위젯 클릭 비활성화 처리
            updateBtnVis(autoSubmitBtn); updateBtnVis(debugLogBtn); updateBtnVis(showMainScreenBtn); updateBtnVis(toastToggleBtn); updateBtnVis(toastPosBtn);
            updateSldVis(bgAlphaSlider); updateSldVis(cacheTtlSlider); updateBtnVis(uiThemeBtn); updateBtnVis(clearCacheBtn);
            updateBtnVis(trackToggleBtn); updateBtnVis(engineToggleBtn); updateBtnVis(tireToggleBtn); updateBtnVis(modeToggleBtn);
        }

        if (backBtn != null) backBtn.setPosition(rightAreaX + rightAreaW - 22, this.height - 28);
    }

    // ── 렌더 ─────────────────────────────────────────────────────
    private void drawRectBorder(DrawContext ctx, int x, int y, int w, int h, int color) {
        ctx.fill(x, y, x + w, y + 1, color); ctx.fill(x, y + h - 1, x + w, y + h, color);
        ctx.fill(x, y, x + 1, y + h, color); ctx.fill(x + w - 1, y, x + w, y + h, color);
    }

    private void drawSectionLabel(DrawContext ctx, String label, int x, int y, int w) {
        ctx.fill(x, y + 5, x + 4, y + 6, 0xFF2A2A2A);
        ctx.drawTextWithShadow(this.textRenderer, label, x + 8, y, 0xFF8888);
        int textW = this.textRenderer.getWidth(label);
        int rightStartX = x + 8 + textW + 4;
        if (rightStartX < x + w) {
            ctx.fill(rightStartX, y + 5, x + w, y + 6, 0xFF2A2A2A);
        }
    }

    private void drawRowLabel(DrawContext ctx, String label, int x, int y, int colW) {
        int maxW = colW - TOGGLE_W - 8;
        String disp = this.textRenderer.getWidth(label) > maxW
                ? this.textRenderer.trimToWidth(Text.literal(label), maxW - 8).getString() + ".." : label;
        ctx.drawTextWithShadow(this.textRenderer, disp, x + 2, y + 5, 0xCCCCCC);
    }

    // ★ RankingScreen의 배경 코드를 완벽하게 복사
    @Override
    public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
        context.fill(0, 0, this.width, this.height, ModConfig.get().getBgColor());
    }

    @Override public boolean shouldPause() { return false; }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        // 배경 렌더링 호출
        renderBackground(context, mouseX, mouseY, delta);

        if (isNewUi() && sharedSidebar != null) sharedSidebar.render(context, mouseX, mouseY, delta);

        int effectiveLeftW = getEffectiveSidebarWidth();
        int rightAreaX = effectiveLeftW == 0 ? OUTER_PAD : OUTER_PAD + effectiveLeftW + 8;
        int rightAreaW = this.width - OUTER_PAD - rightAreaX;
        int cx = rightAreaX + rightAreaW / 2;

        int headerX = rightAreaX;
        int headerY = 10;
        int headerW = rightAreaW;
        int headerH = 50;

        int panelW = rightAreaW;
        int colW = Math.max(120, Math.min(220, (panelW - 40) / 2));
        int leftX = cx - colW - 10;
        int rightX = cx + 10;

        // 헤더 박스 렌더링
        context.fill(headerX, headerY, headerX + headerW, headerY + headerH, 0xCC000000);
        drawRectBorder(context, headerX, headerY, headerW, headerH, 0xFF2A2A2A);

        // 메인 패널 박스 렌더링 (화면 높이에 맞게 반응형)
        context.fill(rightAreaX, currentPanelY, rightAreaX + panelW, currentPanelY + currentPanelH, 0x66000000);
        drawRectBorder(context, rightAreaX, currentPanelY, panelW, currentPanelH, 0xFF222222);

        context.drawCenteredTextWithShadow(this.textRenderer, "§6§l" + this.title.getString(), cx, headerY + 14, 0xFFFFFF);
        context.drawCenteredTextWithShadow(this.textRenderer, "§7V" + getModVersion(), cx, headerY + 28, 0xFFFFFF);

        context.fill(cx - 1, currentPanelY + 15, cx, currentPanelY + currentPanelH - 15, 0xFF2A2A2A);

        int funcLabelY = contentStartY;
        int funcY  = funcLabelY + 14;
        int screenLabelY = funcY + ROW_H * 5 + 15;
        int defLabelY = contentStartY;

        // ★ 스크롤 오버레이 클리핑 (박스 바깥으로 나가는 텍스트 안 보이게 자름)
        context.enableScissor(rightAreaX, currentPanelY, rightAreaX + panelW, currentPanelY + currentPanelH);

        drawSectionLabel(context, "§e기능", leftX,  funcLabelY,  colW);
        drawSectionLabel(context, "§e화면", leftX,  screenLabelY, colW);
        drawSectionLabel(context, "§e기본값", rightX, defLabelY, colW);

        drawRowLabel(context, "자동 기록 등록",  leftX, funcY,            colW);
        drawRowLabel(context, "디버그 로그",     leftX, funcY + ROW_H,    colW);
        drawRowLabel(context, ModConfig.get().useLegacyUi ? "§7메인 스크린 보기" : "메인 스크린 보기", leftX, funcY + ROW_H * 2, colW);
        drawRowLabel(context, "알림 표시",       leftX, funcY + ROW_H * 3, colW);

        // ★ 위젯 렌더링 방식 복구 (어두워지는 현상 방지)
        for (Element element : this.children()) {
            if (element instanceof Drawable drawable) drawable.render(context, mouseX, mouseY, delta);
        }

        context.disableScissor();

        // ── 우측 스크롤바 렌더링 ──
        if (maxScroll > 0) {
            int barW = 6;
            int barX = rightAreaX + panelW - barW - 2;
            int barY = currentPanelY + 2;
            int barH = currentPanelH - 4;
            context.fill(barX, barY, barX + barW, barY + barH, 0x55000000);
            int thumbH = Math.max(20, (int) (barH * ((float) currentPanelH / TOTAL_CONTENT_H)));
            int thumbY = barY + (int) ((barH - thumbH) * (scrollAmount / maxScroll));
            context.fill(barX, thumbY, barX + barW, thumbY + thumbH, 0xFF888888);
        }

        // 클리핑 방지를 위해 수동 렌더링되는 뒤로 가기 버튼
        if (backBtn != null) backBtn.render(context, mouseX, mouseY, delta);

        // ── 드롭다운 오버레이 ──
        context.getMatrices().push();
        context.getMatrices().translate(0, 0, 400);

        if (trackDropdownOpen && trackToggleBtn != null) {
            int pX = trackToggleBtn.getX(), pY = trackToggleBtn.getY() + 22, pW = trackToggleBtn.getWidth(), pH = 140;
            context.fill(pX, pY, pX + pW, pY + pH, 0xFA0F0F0F); drawRectBorder(context, pX, pY, pW, pH, 0xFF555555);
            trackSearchBox.setX(pX + 10); trackSearchBox.setY(pY + 10); trackSearchBox.render(context, mouseX, mouseY, delta);
            int listY = pY + 34; int visR = (pH - 34) / 16;
            int maxSc = Math.max(0, filteredTracks.size() - visR); if (trackScroll > maxSc) trackScroll = maxSc;
            for (int i = 0; i < visR; i++) {
                int idx = trackScroll + i; if (idx >= filteredTracks.size()) break;
                String tr = filteredTracks.get(idx); int iy = listY + i * 16;
                if (isInside(mouseX, mouseY, pX, iy, pW, 16)) context.fill(pX+1,iy,pX+pW-1,iy+16,0xFF222222);
                if (tr.equals(ModConfig.get().defaultTrack)) context.fill(pX+1,iy,pX+pW-1,iy+16,0xFF153015);
                String disp = this.textRenderer.getWidth(tr) > pW - 20 ? this.textRenderer.trimToWidth(Text.literal(tr), pW - 24).getString() + ".." : tr;
                context.drawTextWithShadow(this.textRenderer, disp, pX + 8, iy + 4, 0xFFFFFF);
            }
            if (maxSc > 0) {
                int bX = pX + pW - 7; context.fill(bX, listY, bX+6, listY+visR*16, 0xFF111111);
                int tH = Math.max(10, (int)((visR*16)*((float)visR/filteredTracks.size())));
                int tY = listY + (int)(((visR*16)-tH)*((float)trackScroll/maxSc));
                context.fill(bX, tY, bX+6, tY+tH, 0xFF555555);
            }
        }

        if (engineDropdownOpen && engineToggleBtn != null) {
            int pX = engineToggleBtn.getX(), pY = engineToggleBtn.getY() + 22, pW = engineToggleBtn.getWidth(), pH = 140;
            context.fill(pX, pY, pX+pW, pY+pH, 0xFA0F0F0F); drawRectBorder(context, pX, pY, pW, pH, 0xFF555555);
            engineSearchBox.setX(pX+10); engineSearchBox.setY(pY+10); engineSearchBox.render(context, mouseX, mouseY, delta);
            int listY = pY + 34; int visR = (pH - 34) / 16;
            int maxSc = Math.max(0, filteredEngines.size() - visR); if (engineScroll > maxSc) engineScroll = maxSc;
            for (int i = 0; i < visR; i++) {
                int idx = engineScroll + i; if (idx >= filteredEngines.size()) break;
                String en = filteredEngines.get(idx); int iy = listY + i * 16;
                if (isInside(mouseX, mouseY, pX, iy, pW, 16)) context.fill(pX+1,iy,pX+pW-1,iy+16,0xFF222222);
                if (en.equals(ModConfig.get().defaultEngine)) context.fill(pX+1,iy,pX+pW-1,iy+16,0xFF153015);
                String disp = this.textRenderer.getWidth(en) > pW-20 ? this.textRenderer.trimToWidth(Text.literal(en), pW-24).getString() + ".." : en;
                context.drawTextWithShadow(this.textRenderer, disp.equals("ALL") ? "전체" : disp, pX+8, iy+4, 0xFFFFFF);
            }
            if (maxSc > 0) {
                int bX = pX+pW-7; context.fill(bX, listY, bX+6, listY+visR*16, 0xFF111111);
                int tH = Math.max(10, (int)((visR*16)*((float)visR/filteredEngines.size())));
                int tY = listY + (int)(((visR*16)-tH)*((float)engineScroll/maxSc));
                context.fill(bX, tY, bX+6, tY+tH, 0xFF555555);
            }
        }

        if (tireDropdownOpen && tireToggleBtn != null) {
            int pX = tireToggleBtn.getX(), pY = tireToggleBtn.getY() + 22, pW = tireToggleBtn.getWidth(), pH = 10 + ALL_TIRES.size() * 16;
            context.fill(pX, pY, pX+pW, pY+pH, 0xFA0F0F0F); drawRectBorder(context, pX, pY, pW, pH, 0xFF555555);
            int listY = pY + 5;
            for (int i = 0; i < ALL_TIRES.size(); i++) {
                String t = ALL_TIRES.get(i); int iy = listY + i * 16;
                if (isInside(mouseX, mouseY, pX, iy, pW, 16)) context.fill(pX+1,iy,pX+pW-1,iy+16,0xFF222222);
                if (t.equals(ModConfig.get().defaultTire)) context.fill(pX+1,iy,pX+pW-1,iy+16,0xFF153015);
                context.drawTextWithShadow(this.textRenderer, t.equals("ALL") ? "전체" : t, pX+8, iy+4, 0xFFFFFF);
            }
        }

        if (modeDropdownOpen && modeToggleBtn != null) {
            int pX = modeToggleBtn.getX(), pY = modeToggleBtn.getY() + 22, pW = modeToggleBtn.getWidth(), pH = 10 + RankingScreen.FIXED_MODES.size() * 16;
            context.fill(pX, pY, pX+pW, pY+pH, 0xFA0F0F0F); drawRectBorder(context, pX, pY, pW, pH, 0xFF555555);
            int listY = pY + 5;
            for (int i = 0; i < RankingScreen.FIXED_MODES.size(); i++) {
                String m = RankingScreen.FIXED_MODES.get(i); int iy = listY + i * 16;
                boolean checked = ModConfig.get().defaultModes.contains(m);
                boolean hover   = isInside(mouseX, mouseY, pX, iy, pW, 16);
                if (hover) context.fill(pX+1, iy, pX+pW-1, iy+16, 0xFF1E1E1E);
                context.drawTextWithShadow(this.textRenderer, m, pX+8, iy+4, 0xFFFFFF);
                int bsX = pX+pW-18, bsY = iy+2;
                context.fill(bsX, bsY, bsX+12, bsY+12, hover ? 0xFF2A2A2A : 0xFF141414);
                drawRectBorder(context, bsX, bsY, 12, 12, 0xFFAAAAAA);
                if (checked) context.fill(bsX+3, bsY+3, bsX+9, bsY+9, 0xFF55FF55);
            }
        }

        context.getMatrices().pop();
    }

    // ── 스크롤 감지 및 클릭 이벤트 ──────────────────────────────────────────────────
    @Override
    public boolean mouseScrolled(double mx, double my, double hAmt, double vAmt) {
        if (isNewUi() && sharedSidebar != null && sharedSidebar.mouseScrolled(mx, my, hAmt, vAmt)) return true;
        if (trackDropdownOpen && trackToggleBtn != null && isInside(mx, my, trackToggleBtn.getX(), trackToggleBtn.getY()+22, trackToggleBtn.getWidth(), 140)) {
            trackScroll = Math.clamp(trackScroll - (vAmt > 0 ? 1 : -1), 0, Math.max(0, filteredTracks.size() - ((140-34)/16))); return true;
        }
        if (engineDropdownOpen && engineToggleBtn != null && isInside(mx, my, engineToggleBtn.getX(), engineToggleBtn.getY()+22, engineToggleBtn.getWidth(), 140)) {
            engineScroll = Math.clamp(engineScroll - (vAmt > 0 ? 1 : -1), 0, Math.max(0, filteredEngines.size() - ((140-34)/16))); return true;
        }

        // ★ 설정 영역(Panel) 안에서 마우스 휠을 굴렸을 때의 스크롤 작동 처리
        int effectiveLeftW = getEffectiveSidebarWidth();
        int rightAreaX = effectiveLeftW == 0 ? OUTER_PAD : OUTER_PAD + effectiveLeftW + 8;
        int rightAreaW = this.width - OUTER_PAD - rightAreaX;

        if (mx >= rightAreaX && mx <= rightAreaX + rightAreaW && my >= currentPanelY && my <= currentPanelY + currentPanelH) {
            if (maxScroll > 0) {
                closeAllDropdowns(); // 스크롤 시 드롭다운 닫힘 처리
                scrollAmount -= vAmt * 25; // 휠 1틱당 이동할 픽셀 수 (25px)
                scrollAmount = Math.max(0, Math.min(scrollAmount, maxScroll));
                repositionUiElements(); // 스크롤 변경 시 위치 즉각 갱신
                return true;
            }
        }

        return super.mouseScrolled(mx, my, hAmt, vAmt);
    }

    private boolean isInside(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx <= x + w && my >= y && my <= y + h;
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (isNewUi() && sharedSidebar != null && sharedSidebar.mouseClicked(mx, my, button)) return true;

        boolean insideAny = false;

        if (trackDropdownOpen && trackToggleBtn != null) {
            int pX = trackToggleBtn.getX(), pY = trackToggleBtn.getY()+22, pW = trackToggleBtn.getWidth();
            if (isInside(mx, my, pX, pY, pW, 140)) {
                insideAny = true;
                if (trackSearchBox.isMouseOver(mx, my)) { trackSearchBox.mouseClicked(mx, my, button); trackSearchBox.setFocused(true); setFocused(trackSearchBox); return true; }
                int listY = pY + 34;
                if (my >= listY) {
                    int ci = (int)((my - listY) / 16) + trackScroll;
                    if (ci >= 0 && ci < filteredTracks.size()) { ModConfig.get().defaultTrack = filteredTracks.get(ci); ModConfig.save(); updateButtonTexts(); closeAllDropdowns(); playUiClick(); }
                }
                return true;
            }
        }

        if (engineDropdownOpen && engineToggleBtn != null) {
            int pX = engineToggleBtn.getX(), pY = engineToggleBtn.getY()+22, pW = engineToggleBtn.getWidth();
            if (isInside(mx, my, pX, pY, pW, 140)) {
                insideAny = true;
                if (engineSearchBox.isMouseOver(mx, my)) { engineSearchBox.mouseClicked(mx, my, button); engineSearchBox.setFocused(true); setFocused(engineSearchBox); return true; }
                int listY = pY + 34;
                if (my >= listY) {
                    int ci = (int)((my - listY) / 16) + engineScroll;
                    if (ci >= 0 && ci < filteredEngines.size()) { ModConfig.get().defaultEngine = filteredEngines.get(ci); ModConfig.save(); updateButtonTexts(); closeAllDropdowns(); playUiClick(); }
                }
                return true;
            }
        }

        if (tireDropdownOpen && tireToggleBtn != null) {
            int pX = tireToggleBtn.getX(), pY = tireToggleBtn.getY()+22, pW = tireToggleBtn.getWidth(), pH = 10 + ALL_TIRES.size()*16;
            if (isInside(mx, my, pX, pY, pW, pH)) {
                insideAny = true;
                int ci = (int)((my - (pY+5)) / 16);
                if (ci >= 0 && ci < ALL_TIRES.size()) { ModConfig.get().defaultTire = ALL_TIRES.get(ci); ModConfig.save(); updateButtonTexts(); closeAllDropdowns(); playUiClick(); }
                return true;
            }
        }

        if (modeDropdownOpen && modeToggleBtn != null) {
            int pX = modeToggleBtn.getX(), pY = modeToggleBtn.getY()+22, pW = modeToggleBtn.getWidth(), pH = 10 + RankingScreen.FIXED_MODES.size()*16;
            if (isInside(mx, my, pX, pY, pW, pH)) {
                insideAny = true;
                int ci = (int)((my - (pY+5)) / 16);
                if (ci >= 0 && ci < RankingScreen.FIXED_MODES.size()) {
                    String cm = RankingScreen.FIXED_MODES.get(ci);
                    if (ModConfig.get().defaultModes.contains(cm)) ModConfig.get().defaultModes.remove(cm);
                    else ModConfig.get().defaultModes.add(cm);
                    ModConfig.save(); updateButtonTexts(); playUiClick();
                }
                return true;
            }
        }

        if ((trackDropdownOpen || engineDropdownOpen || tireDropdownOpen || modeDropdownOpen) && !insideAny) {
            if (!trackToggleBtn.isMouseOver(mx, my) && !engineToggleBtn.isMouseOver(mx, my)
                    && !tireToggleBtn.isMouseOver(mx, my) && !modeToggleBtn.isMouseOver(mx, my)) {
                closeAllDropdowns();
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int mods) {
        if (trackDropdownOpen  && trackSearchBox.keyPressed(keyCode, scanCode, mods))  return true;
        if (engineDropdownOpen && engineSearchBox.keyPressed(keyCode, scanCode, mods)) return true;
        return super.keyPressed(keyCode, scanCode, mods);
    }

    @Override
    public boolean charTyped(char chr, int mods) {
        if (trackDropdownOpen  && trackSearchBox.charTyped(chr, mods))  return true;
        if (engineDropdownOpen && engineSearchBox.charTyped(chr, mods)) return true;
        return super.charTyped(chr, mods);
    }

    // ── 캐시/엔진 로드 ────────────────────────────────────────────
    private void loadTracksFromCache() {
        RankingScreen.ApiCache.AllPayload p = RankingScreen.ApiCache.getAllIfReady();
        allTracks.clear();
        if (p != null && p.tracks != null) for (TrackSelectScreen.TrackEntry t : p.tracks) allTracks.add(t.track());
        else allTracks.add("[α] 빌리지 고가의 질주");
        applyTrackSearch();
    }

    private static String normalizeEngine(String engineName) {
        if (engineName == null) return "UNKNOWN";
        String s = engineName.trim();
        if (s.startsWith("[") && s.endsWith("]") && s.length() >= 3) s = s.substring(1, s.length()-1).trim();
        s = s.replace("엔진","").replace("ENGINE","").replace("engine","").trim();
        return s.isBlank() ? "UNKNOWN" : s.toUpperCase();
    }

    private void fetchAllEnginesAsync() {
        new Thread(() -> {
            try {
                JsonObject res = RankingScreen.Net.postJson(RankingScreen.SUPABASE_RPC_URL + "get_all_engines", "{}");
                if (res.has("ok") && res.get("ok").getAsBoolean() && res.has("engines")) {
                    JsonArray arr = res.getAsJsonArray("engines");
                    Set<String> set = new HashSet<>();
                    for (int i = 0; i < arr.size(); i++) {
                        if (!arr.get(i).isJsonNull()) {
                            String n = normalizeEngine(arr.get(i).getAsString());
                            if (!n.equals("UNKNOWN")) set.add(n);
                        }
                    }
                    if (this.client != null) this.client.execute(() -> {
                        allEngines.clear(); allEngines.add("ALL");
                        List<String> sorted = new ArrayList<>(set); sorted.sort(String::compareToIgnoreCase);
                        allEngines.addAll(sorted); applyEngineSearch();
                    });
                }
            } catch (Exception ignored) {}
        }, "Fetch-Engines").start();
    }

    private void applyTrackSearch() {
        filteredTracks.clear();
        String q = trackSearchBox.getText().toLowerCase().trim();
        if (q.isEmpty()) filteredTracks.addAll(allTracks);
        else for (String t : allTracks) if (t.toLowerCase().contains(q)) filteredTracks.add(t);
    }

    private void applyEngineSearch() {
        filteredEngines.clear();
        String q = engineSearchBox.getText().toLowerCase().trim();
        if (q.isEmpty()) filteredEngines.addAll(allEngines);
        else for (String e : allEngines) if (e.toLowerCase().contains(q)) filteredEngines.add(e);
    }

    @Override
    public void close() { if (this.client != null) this.client.setScreen(null); }
}