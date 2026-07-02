package com.example.rankinglog;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.sound.SoundEvents;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

public class SharedSidebar {

    private static final int EXPANDED_W = 150;
    private static final int COLLAPSED_W = 40;
    private static final int OUTER_PAD = 12;

    private static boolean expanded = false;
    private static boolean tmiMenuOpen = false;

    private double scroll = 0;
    private int totalContentHeight = 0;

    // ★ 이벤트 개수 캐싱 관련 정적 변수
    private static int activeEventCount = -1;
    private static long eventCountCachedAt = 0;
    private static final long EVENT_TTL_MS = 60_000;

    // ★ 공지사항 개수 캐싱 관련 정적 변수 추가
    private static int activeNoticeCount = -1;
    private static long noticeCountCachedAt = 0;
    private static final long NOTICE_TTL_MS = 60_000;

    private final Screen parent;
    private final String activeTabId;
    private String activeTmiCatId;
    private final Consumer<String> onTmiCategorySelected;

    // 타 스크린 호환성을 위해 변수는 유지하지만, 사이드바 내에서는 더이상 사용되지 않습니다.
    private final Runnable onRefresh;
    private final Runnable onLayoutChanged;

    private record SideTab(String id, String icon, String name) {}
    private final List<SideTab> sideTabs = buildSideTabs();

    private static List<SideTab> buildSideTabs() {
        SideTab firstTab = ModConfig.get().showMainScreen
                ? new SideTab("HOME", "\uD83C\uDFE0", "메인")
                : new SideTab("NOTICE", "\uD83D\uDCE3", "공지사항");
        return java.util.Arrays.asList(
                firstTab,
                new SideTab("RIDER_FIND", "🔍", "라이더 찾기"),
                new SideTab("RANKING", "🏆", "랭킹"),
                new SideTab("TMI", "📊", "TMI"),
                new SideTab("EVENT", "📅", "이벤트"),
                new SideTab("SETTING", "⚙", "설정")
        );
    }

    private record MainCat(String id, String icon, String name) {}
    private final List<MainCat> tmiCats = List.of(
            new MainCat("PLAYER", "👤", "플레이어"),
            new MainCat("ENGINE", "⚙", "엔진"),
            new MainCat("KARTBODY", "🚙", "카트바디"),
            new MainCat("RECORD", "⏱", "기록")
    );

    public SharedSidebar(Screen parent, String activeTabId, String activeTmiCatId, boolean initialTmiMenuOpen,
                         Consumer<String> onTmiCategorySelected, Runnable onRefresh, Runnable onLayoutChanged) {
        this.parent = parent;
        this.activeTabId = activeTabId;
        this.activeTmiCatId = activeTmiCatId;

        if (initialTmiMenuOpen) {
            tmiMenuOpen = true;
        }

        this.onTmiCategorySelected = onTmiCategorySelected;
        this.onRefresh = onRefresh;
        this.onLayoutChanged = onLayoutChanged;

        // ★ 사이드바 로드 시 이벤트 개수가 만료되었거나 없다면 불러오기
        if (activeEventCount == -1 || System.currentTimeMillis() - eventCountCachedAt > EVENT_TTL_MS) {
            fetchEventCount();
        }

        // ★ 사이드바 로드 시 공지사항 개수가 만료되었거나 없다면 불러오기
        if (activeNoticeCount == -1 || System.currentTimeMillis() - noticeCountCachedAt > NOTICE_TTL_MS) {
            fetchNoticeCount();
        }
    }

    // ★ 이벤트 정보를 백그라운드 스레드에서 패치하는 메서드
    private void fetchEventCount() {
        new Thread(() -> {
            try {
                JsonObject obj = RankingScreen.Net.postJson(RankingScreen.SUPABASE_RPC_URL + "get_event_list", "{}");

                if (obj.has("ok") && obj.get("ok").getAsBoolean()) {
                    JsonArray arr = obj.getAsJsonArray("events");
                    MinecraftClient client = MinecraftClient.getInstance();
                    String playerName = (client != null && client.player != null) ? client.player.getName().getString() : "";

                    boolean isDev = Objects.equals("BKGpolar1", playerName);
                    int count = 0;

                    for (int i = 0; i < arr.size(); i++) {
                        JsonObject o = arr.get(i).getAsJsonObject();
                        String visibleValue = o.has("visible") ? o.get("visible").getAsString().trim() : "";
                        boolean shouldShow = true;

                        if ("false-dev".equalsIgnoreCase(visibleValue)) { if (isDev) shouldShow = false; }
                        else if ("true-dev".equalsIgnoreCase(visibleValue)) { if (!isDev) shouldShow = false; }

                        if (shouldShow) count++;
                    }
                    activeEventCount = count;
                    eventCountCachedAt = System.currentTimeMillis();
                }
            } catch (Exception e) { e.printStackTrace(); }
        }).start();
    }

    // ★ 공지사항 개수를 백그라운드 스레드에서 패치하는 메서드 추가
    private void fetchNoticeCount() {
        new Thread(() -> {
            try {
                JsonObject obj = RankingScreen.Net.postJson(RankingScreen.SUPABASE_RPC_URL + "get_notices", "{}");

                if (obj.has("ok") && obj.get("ok").getAsBoolean()) {
                    JsonArray arr = obj.getAsJsonArray("notices");
                    activeNoticeCount = arr.size();
                    noticeCountCachedAt = System.currentTimeMillis();
                }
            } catch (Exception e) { e.printStackTrace(); }
        }).start();
    }

    public void setActiveTmiCatId(String activeTmiCatId) {
        this.activeTmiCatId = activeTmiCatId;
    }

    public int getCurrentWidth(int parentWidth) {
        boolean isSmall = parentWidth < 480;
        int collapsedW = isSmall ? 0 : COLLAPSED_W;
        return expanded ? EXPANDED_W : collapsedW;
    }

    private static String getModVersion() {
        Optional<ModContainer> modContainer = FabricLoader.getInstance().getModContainer("modid");
        return modContainer.map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse("dev");
    }

    private void playClickSound() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client != null) {
            client.getSoundManager().play(PositionedSoundInstance.master(SoundEvents.UI_BUTTON_CLICK, 1.0f));
        }
    }

    private void drawRectBorder(DrawContext context, int x, int y, int w, int h, int color) {
        context.fill(x, y, x + w, y + 1, color);
        context.fill(x, y + h - 1, x + w, y + h, color);
        context.fill(x, y, x + 1, y + h, color);
        context.fill(x + w - 1, y, x + w, y + h, color);
    }

    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null) return;
        TextRenderer textRenderer = client.textRenderer;

        int curW = getCurrentWidth(parent.width);
        int sbX = OUTER_PAD;
        int sbY = OUTER_PAD;
        int sbH = parent.height - OUTER_PAD * 2;

        if (curW > 0) {
            context.fill(sbX, sbY, sbX + curW, sbY + sbH, 0xCC000000);
            drawRectBorder(context, sbX, sbY, curW, sbH, 0xFF2A2A2A);
        }

        int toggleBtnSize = 16;
        int toggleX = curW == 0 ? sbX : sbX + curW - toggleBtnSize - 4;
        int toggleY = sbY + 4;
        boolean hoverToggle = mouseX >= toggleX - 2 && mouseX <= toggleX + toggleBtnSize + 2 && mouseY >= toggleY - 2 && mouseY <= toggleY + toggleBtnSize + 2;

        if (curW == 0) {
            context.fill(toggleX - 2, toggleY - 2, toggleX + toggleBtnSize + 2, toggleY + toggleBtnSize + 2, 0xCC000000);
            drawRectBorder(context, toggleX - 2, toggleY - 2, toggleBtnSize + 4, toggleBtnSize + 4, 0xFF2A2A2A);
        }
        context.fill(toggleX, toggleY, toggleX + toggleBtnSize, toggleY + toggleBtnSize, hoverToggle ? 0xFF555555 : 0xFF222222);
        drawRectBorder(context, toggleX, toggleY, toggleBtnSize, toggleBtnSize, 0xFF555555);
        context.drawCenteredTextWithShadow(textRenderer, expanded ? "◁" : "▷", toggleX + toggleBtnSize / 2, toggleY + 4, 0xFFFFFF);

        if (curW > 0) {
            int contentTop = sbY + 24;
            int maxScroll = Math.max(0, totalContentHeight - (sbH - 24));
            scroll = Math.max(0, Math.min(scroll, maxScroll));

            context.enableScissor(sbX, contentTop, sbX + curW, sbY + sbH);
            context.getMatrices().push();
            context.getMatrices().translate(0, -scroll, 0);

            int sy = contentTop + 5;

            if (expanded) {
                float titleScale = 1.1f;
                context.getMatrices().push();
                context.getMatrices().scale(titleScale, titleScale, 1.0f);
                context.drawCenteredTextWithShadow(textRenderer, "§6§lMCRiderRanking", (int)((sbX + curW / 2) / titleScale), (int)(sy / titleScale), 0xFFFFFF);
                context.getMatrices().pop();
                sy += 16;
                context.drawCenteredTextWithShadow(textRenderer, "§7V" + getModVersion(), sbX + curW / 2, sy, 0xFFFFFF);
                sy += 24;
            } else {
                context.drawCenteredTextWithShadow(textRenderer, "§6§lM", sbX + curW / 2, sy, 0xFFFFFF);
                sy += 24;
            }

            // 프로필 버튼 선명하게
            int profH = 24;
            double adjustedY = mouseY + scroll;
            boolean isProfSel = "PROFILE".equals(activeTabId);
            boolean hoverProf = mouseX >= sbX && mouseX <= sbX + curW && adjustedY >= sy && adjustedY <= sy + profH;

            int profBgColor = isProfSel ? 0xFF335533 : (hoverProf ? 0xFF444444 : 0x22000000);
            context.fill(sbX + 4, sy, sbX + curW - 4, sy + profH, profBgColor);

            if (isProfSel) drawRectBorder(context, sbX + 4, sy, curW - 8, profH, 0xFF55FF55);
            else if (hoverProf) drawRectBorder(context, sbX + 4, sy, curW - 8, profH, 0xFF888888);
            else drawRectBorder(context, sbX + 4, sy, curW - 8, profH, 0xFF3A3A3A);

            String playerName = client.getSession() != null ? client.getSession().getUsername() : "Player";
            if (expanded) {
                context.drawTextWithShadow(textRenderer, "👤 " + playerName, sbX + 10, sy + 8, isProfSel ? 0xFF55FF55 : 0xFFFFFF);
            } else {
                context.drawCenteredTextWithShadow(textRenderer, "👤", sbX + curW / 2, sy + 8, isProfSel ? 0xFF55FF55 : 0xFFFFFF);
            }
            sy += profH + 20;

            // 탭 버튼들 선명하게
            for (SideTab tab : sideTabs) {
                boolean isSel = tab.id().equals(activeTabId);
                int tabH = 30;
                boolean hoverTab = mouseX >= sbX && mouseX <= sbX + curW && adjustedY >= sy && adjustedY <= sy + tabH;

                int bgColor = isSel ? 0xFF335533 : (hoverTab ? 0xFF444444 : 0x22000000);
                context.fill(sbX + 4, sy, sbX + curW - 4, sy + tabH, bgColor);

                if (isSel) drawRectBorder(context, sbX + 4, sy, curW - 8, tabH, 0xFF55FF55);
                else if (hoverTab) drawRectBorder(context, sbX + 4, sy, curW - 8, tabH, 0xFF888888);
                else drawRectBorder(context, sbX + 4, sy, curW - 8, tabH, 0xFF3A3A3A);

                if (expanded) {
                    String extra = tab.id().equals("TMI") ? (tmiMenuOpen ? " ▾" : " ▸") : "";
                    context.drawTextWithShadow(textRenderer, tab.icon() + " " + tab.name() + extra, sbX + 15, sy + 11, isSel ? 0xFF55FF55 : 0xAAAAAA);
                } else {
                    String extra = tab.id().equals("TMI") ? (tmiMenuOpen ? "▾" : "▸") : "";
                    context.drawCenteredTextWithShadow(textRenderer, tab.icon() + extra, sbX + curW / 2, sy + 11, isSel ? 0xFF55FF55 : 0xAAAAAA);
                }

                // ★ 'EVENT' 또는 'NOTICE' 탭에 알림 뱃지(카운트) 작게 렌더링
                int displayBadgeCount = -1;
                if (tab.id().equals("EVENT") && activeEventCount > 0) {
                    displayBadgeCount = activeEventCount;
                } else if (tab.id().equals("NOTICE") && activeNoticeCount > 0) {
                    displayBadgeCount = activeNoticeCount;
                }

                if (displayBadgeCount > 0) {
                    float badgeScale = 0.75f; // 뱃지 크기 25% 축소
                    String countText = String.valueOf(displayBadgeCount);
                    int textWidth = textRenderer.getWidth(countText);
                    int boxW = Math.max(12, textWidth + 4);
                    int boxH = 12;

                    int bx1, by1;
                    if (expanded) {
                        int textEndX = sbX + 15 + textRenderer.getWidth(tab.icon() + " " + tab.name());
                        bx1 = textEndX + 4; // 텍스트 우측 여백
                        by1 = sy + 6;       // 탭 중앙보다 살짝 위로 (Superscript 느낌)
                    } else {
                        bx1 = sbX + curW / 2 + 4; // 아이콘의 우측 상단
                        by1 = sy + 2;
                    }

                    // 매트릭스를 통한 독립적인 크기 스케일링
                    context.getMatrices().push();
                    context.getMatrices().translate(bx1, by1, 400); // UI 최상단으로 렌더링
                    context.getMatrices().scale(badgeScale, badgeScale, 1.0f);

                    context.fill(0, 0, boxW, boxH, 0xFFFF0000);
                    context.drawBorder(0, 0, boxW, boxH, 0xFFFFFFFF);

                    int textxX = (boxW - textWidth) / 2 + 1; // 시각적 정렬 보정
                    int textxY = (boxH - 8) / 2 + 1;
                    context.drawText(textRenderer, countText, textxX, textxY, 0xFFFFFFFF, false);

                    context.getMatrices().pop();
                }

                sy += tabH + 5;

                // TMI 서브 탭들 선명하게
                if (tab.id().equals("TMI") && tmiMenuOpen) {
                    for (MainCat mc : tmiCats) {
                        int subH = 24;
                        boolean isSubSel = mc.id().equals(activeTmiCatId) && activeTabId.equals("TMI");
                        boolean hoverSub = mouseX >= sbX && mouseX <= sbX + curW && adjustedY >= sy && adjustedY <= sy + subH;

                        int subBg = isSubSel ? 0xFF224422 : (hoverSub ? 0xFF333333 : 0x11000000);
                        context.fill(sbX + 4, sy, sbX + curW - 4, sy + subH, subBg);

                        if (isSubSel) drawRectBorder(context, sbX + 4, sy, curW - 8, subH, 0xFF44AA44);
                        else if (hoverSub) drawRectBorder(context, sbX + 4, sy, curW - 8, subH, 0xFF666666);
                        else drawRectBorder(context, sbX + 4, sy, curW - 8, subH, 0xFF2A2A2A);

                        if (expanded) {
                            context.drawTextWithShadow(textRenderer, mc.icon() + " " + mc.name(), sbX + 30, sy + 8, isSubSel ? 0xFF55FF55 : 0x888888);
                        } else {
                            context.drawCenteredTextWithShadow(textRenderer, mc.icon(), sbX + curW / 2, sy + 8, isSubSel ? 0xFF55FF55 : 0x888888);
                        }
                        sy += subH + 2;
                    }
                    sy += 3;
                }
            }

            totalContentHeight = sy - contentTop;

            context.getMatrices().pop();
            context.disableScissor();

            if (maxScroll > 0) {
                int barW = 4;
                int barX = sbX + curW - barW - 2;
                int barY = contentTop + 2;
                int barH = sbH - 24 - 4;
                context.fill(barX, barY, barX + barW, barY + barH, 0x55000000);
                int thumbH = Math.max(10, (int) (barH * ((float) (sbH - 24) / totalContentHeight)));
                int thumbY = barY + (int) ((barH - thumbH) * (scroll / maxScroll));
                context.fill(barX, thumbY, barX + barW, thumbY + thumbH, 0xFF888888);
            }
        }
    }

    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        int curW = getCurrentWidth(parent.width);
        int sbX = OUTER_PAD;
        int sbY = OUTER_PAD;
        int sbH = parent.height - OUTER_PAD * 2;

        int toggleBtnSize = 16;
        int toggleX = curW == 0 ? sbX : sbX + curW - toggleBtnSize - 4;
        int toggleY = sbY + 4;

        if (mouseX >= toggleX - 2 && mouseX <= toggleX + toggleBtnSize + 2 && mouseY >= toggleY - 2 && mouseY <= toggleY + toggleBtnSize + 2) {
            playClickSound();
            expanded = !expanded;
            if (onLayoutChanged != null) onLayoutChanged.run();
            return true;
        }

        if (curW > 0 && mouseX >= sbX && mouseX <= sbX + curW && mouseY >= sbY + 24 && mouseY <= sbY + sbH) {
            double adjustedY = mouseY + scroll;
            int sy = sbY + 24 + 5;
            if (expanded) sy += 40; else sy += 24;

            int profH = 24;
            if (adjustedY >= sy && adjustedY <= sy + profH) {
                playClickSound();
                MinecraftClient client = MinecraftClient.getInstance();
                if (client != null && !"PROFILE".equals(activeTabId)) {
                    String playerName = client.getSession() != null ? client.getSession().getUsername() : "Player";
                    client.setScreen(new PlayerProfileScreen(playerName, parent));
                }
                return true;
            }
            sy += profH + 20;

            for (SideTab tab : sideTabs) {
                int tabH = 30;
                if (adjustedY >= sy && adjustedY <= sy + tabH) {
                    playClickSound();
                    if (tab.id().equals("TMI")) {
                        tmiMenuOpen = !tmiMenuOpen;
                    } else if (!tab.id().equals(activeTabId)) {
                        MinecraftClient client = MinecraftClient.getInstance();
                        if (client != null) {
                            Screen parentMenu = new MainMenuScreen();
                            if (tab.id().equals("RIDER_FIND")) client.setScreen(new RiderFindScreen(parentMenu));
                            else if (tab.id().equals("HOME")) client.setScreen(new MainMenuScreen());
                            else if (tab.id().equals("NOTICE")) client.setScreen(new NoticeModalScreen(parent));
                            else if (tab.id().equals("RANKING")) {
                                String track = TrackNameUtil.readTrackNameFromLobbyBox();
                                String finalTrack = (track == null || track.isBlank()) ? ModConfig.get().defaultTrack : track.trim();
                                Objects.requireNonNull(client).setScreen(new RankingScreen(finalTrack));
                            }
                            else if (tab.id().equals("EVENT")) client.setScreen(new EventOptionSelectScreen(parentMenu));
                            else if (tab.id().equals("SETTING")) client.setScreen(new ModSettingsScreen(parentMenu));
                        }
                    }
                    return true;
                }
                sy += tabH + 5;

                if (tab.id().equals("TMI") && tmiMenuOpen) {
                    for (MainCat mc : tmiCats) {
                        int subH = 24;
                        if (adjustedY >= sy && adjustedY <= sy + subH) {
                            playClickSound();
                            if (onTmiCategorySelected != null) {
                                onTmiCategorySelected.accept(mc.id());
                            }
                            return true;
                        }
                        sy += subH + 2;
                    }
                    sy += 3;
                }
            }

            return true;
        }
        return false;
    }

    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        int curW = getCurrentWidth(parent.width);
        if (curW > 0 && mouseX >= OUTER_PAD && mouseX <= OUTER_PAD + curW && mouseY >= OUTER_PAD + 24 && mouseY <= parent.height - OUTER_PAD) {
            int sbH = parent.height - OUTER_PAD * 2;
            int maxScroll = Math.max(0, totalContentHeight - (sbH - 24));
            scroll = Math.max(0, Math.min(scroll - verticalAmount * 20, maxScroll));
            return true;
        }
        return false;
    }
}