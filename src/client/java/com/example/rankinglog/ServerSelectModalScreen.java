package com.example.rankinglog;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

public class ServerSelectModalScreen extends Screen {

    private final Screen parent;

    private static final List<ServerEntry> servers = new ArrayList<>();
    private static boolean loading = false;
    private static boolean hasFetched = false; // ★ 서버 목록 캐싱 여부
    private static volatile boolean singleplayAccessible = true; // ★ 싱글플레이 접근 권한 전역 상태

    // ★ 위젯 대신 사용할 수동 검색창 상태 변수
    private String searchQuery = "";
    private boolean isSearchFocused = true; // 모달이 열릴 때 자동 포커스
    private int scrollOffset = 0;
    private int maxVisibleRows = 1;

    // 모달 알림창 표시 여부
    private boolean showDevBlockAlert = false;
    private boolean showSingleplayInfo = false; // 싱글플레이 안내 모달
    private boolean showSingleBlockAlert = false; // ★ 싱글플레이 차단 모달

    private record ServerEntry(String key, String address, String displayAddress, String title, long recordCount, boolean isMainServer, boolean isDevServer, boolean isAccessible) {}

    // ── 싱글플레이 엔트리 (누적 기록 수는 fetch 후 설정) ──
    private static volatile long singleplayRecordCount = -1L;
    private static ServerEntry buildSingleplayEntry() {
        return new ServerEntry("__singleplay__", "(싱글플레이 §c[이벤트 ✕])", "(싱글플레이 §c[이벤트 ✕])", "싱글플레이 §c[이벤트 ✕]",
                singleplayRecordCount, false, false, singleplayAccessible);
    }

    public ServerSelectModalScreen(Screen parent) {
        super(Text.literal("서버 변경"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        fetchServersAsync(true); // 모달을 열 때는 강제로 목록을 갱신합니다.
    }

    // ★ 백그라운드에서도 안전하게 서버 목록을 불러오도록 공용(Static) 메서드로 변경
    public static void fetchServersAsync(boolean force) {
        if (loading || (!force && hasFetched)) return;
        loading = true;
        new Thread(() -> {
            try {
                String reqPlayer = "";
                MinecraftClient client = MinecraftClient.getInstance();
                if (client != null && client.getSession() != null) {
                    reqPlayer = client.getSession().getUsername();
                }
                JsonObject reqBody = new JsonObject();
                reqBody.addProperty("p_req_player", reqPlayer);
                JsonObject obj = RankingScreen.Net.postJson(
                        RankingScreen.SUPABASE_RPC_URL + "get_server_list", reqBody.toString()
                );
                if (obj.has("ok") && obj.get("ok").getAsBoolean()) {
                    List<ServerEntry> tempServers = new ArrayList<>();
                    JsonArray arr = obj.getAsJsonArray("servers");
                    for (int i = 0; i < arr.size(); i++) {
                        JsonObject s = arr.get(i).getAsJsonObject();
                        String key            = s.has("key")             && !s.get("key").isJsonNull()             ? s.get("key").getAsString()             : "";
                        String address        = s.has("address")         && !s.get("address").isJsonNull()         ? s.get("address").getAsString()         : "";
                        String displayAddress = s.has("display_address") && !s.get("display_address").isJsonNull() ? s.get("display_address").getAsString().replace("&", "§") : address;
                        String title          = s.has("title")           && !s.get("title").isJsonNull()           ? s.get("title").getAsString().replace("&", "§")           : displayAddress;
                        long   cnt            = s.has("record_count")    && !s.get("record_count").isJsonNull()    ? s.get("record_count").getAsLong()      : 0L;
                        boolean isMain        = s.has("is_main_server")  && !s.get("is_main_server").isJsonNull()  && s.get("is_main_server").getAsBoolean();
                        boolean isDev         = s.has("is_dev_server")   && !s.get("is_dev_server").isJsonNull()   && s.get("is_dev_server").getAsBoolean();
                        boolean isAccessible  = s.has("is_accessible")   && !s.get("is_accessible").isJsonNull()   ? s.get("is_accessible").getAsBoolean()  : true;

                        if (!key.isBlank()) tempServers.add(new ServerEntry(key, address, displayAddress, title, cnt, isMain, isDev, isAccessible));
                    }

                    // 싱글플레이 누적 기록 수 파싱
                    long singleCnt = obj.has("singleplay_count") && !obj.get("singleplay_count").isJsonNull()
                            ? obj.get("singleplay_count").getAsLong() : 0L;

                    // ★ 싱글플레이 접근 권한 파싱
                    boolean spAccessible = obj.has("singleplay_accessible") && !obj.get("singleplay_accessible").isJsonNull()
                            ? obj.get("singleplay_accessible").getAsBoolean() : true;

                    if (client != null) {
                        client.execute(() -> {
                            singleplayRecordCount = singleCnt;
                            singleplayAccessible = spAccessible;
                            servers.clear();
                            servers.addAll(tempServers);
                            hasFetched = true;
                        });
                    }
                }
            } catch (Exception e) {
                System.err.println("[RankingLog Error] 서버 목록을 불러오지 못했습니다.");
                e.printStackTrace();
            } finally {
                MinecraftClient client = MinecraftClient.getInstance();
                if (client != null) client.execute(() -> loading = false);
                else loading = false;
            }
        }, "ServerList-Fetch").start();
    }

    // ★ RankingScreen의 지구본 버튼 옆에 현재 서버 이름을 표시하기 위한 기능
    public static String getServerTitle(String address) {
        if (CurrentServerHolder.SINGLEPLAY.equals(address)) return "싱글플레이 §c[이벤트 ✕]";
        if (!hasFetched && !loading) {
            fetchServersAsync(false);
        }
        for (ServerEntry e : servers) {
            if (e.address().equalsIgnoreCase(address)) {
                return e.title().isBlank() ? e.displayAddress() : e.title();
            }
        }
        return address == null || address.isEmpty() || CurrentServerHolder.SINGLEPLAY.equals(address) ? "싱글플레이 §c[이벤트 ✕]" : address;
    }

    // ── 모달 레이아웃 상수 ──────────────────────────────────────
    private static final int MODAL_W  = 400;
    private static final int ROW_H    = 50;
    private static final int PAD      = 14;
    private static final int HEADER_H = 60;
    private static final int FOOTER_H = 36;

    // 검색 필터 및 정렬: 공식 서버 → 싱글플레이 → 개발 서버 → 일반 서버
    private List<ServerEntry> getFilteredEntries() {
        List<ServerEntry> list = new ArrayList<>();
        ServerEntry mainServer = null;
        List<ServerEntry> devServers = new ArrayList<>();
        List<ServerEntry> otherServers = new ArrayList<>();

        String q = searchQuery.trim().toLowerCase();

        for (ServerEntry s : servers) {
            boolean match = q.isEmpty() ||
                    s.title().toLowerCase().contains(q) ||
                    s.displayAddress().toLowerCase().contains(q) ||
                    s.address().toLowerCase().contains(q);
            if (!match) continue;

            if (s.isMainServer() && mainServer == null) {
                mainServer = s;
            } else if (s.isDevServer()) {
                devServers.add(s);
            } else {
                otherServers.add(s);
            }
        }

        if (mainServer != null) list.add(mainServer);
        list.addAll(devServers);

        if (q.isEmpty() || "싱글플레이".contains(q) || "singleplay".contains(q) || "(싱글플레이)".contains(q)) {
            list.add(buildSingleplayEntry());
        }

        list.addAll(otherServers);
        return list;
    }

    private void playUiClick() {
        if (this.client != null)
            this.client.getSoundManager().play(PositionedSoundInstance.master(SoundEvents.UI_BUTTON_CLICK, 1.0f));
    }

    private boolean isInside(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx <= x + w && my >= y && my <= y + h;
    }

    private void drawRectBorder(DrawContext c, int x, int y, int w, int h, int color) {
        c.fill(x, y, x + w, y + 1, color);
        c.fill(x, y + h - 1, x + w, y + h, color);
        c.fill(x, y, x + 1, y + h, color);
        c.fill(x + w - 1, y, x + w, y + h, color);
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        // 부모 화면 렌더링 (배경 유지)
        parent.render(ctx, -100, -100, delta);

        // ── 알림 모달 우선 표시 ──
        if (showDevBlockAlert) {
            renderDevBlockAlert(ctx, mouseX, mouseY);
            return;
        }
        if (showSingleBlockAlert) {
            renderSingleBlockAlert(ctx, mouseX, mouseY);
            return;
        }
        if (showSingleplayInfo) {
            renderSingleplayInfo(ctx, mouseX, mouseY);
            return;
        }

        ctx.getMatrices().push();
        ctx.getMatrices().translate(0, 0, 400); // UI 우선순위 최상단
        ctx.fill(0, 0, width, height, 0xAA000000);

        int cx = width / 2;
        int cy = height / 2;

        List<ServerEntry> entries = loading && servers.isEmpty() ? List.of() : getFilteredEntries();

        int max_mH = height - 40;
        int maxRows = (max_mH - HEADER_H - FOOTER_H) / ROW_H;
        if (maxRows < 1) maxRows = 1;

        int rowCount = entries.isEmpty() ? 1 : entries.size();
        int calculated_mH = HEADER_H + Math.min(rowCount, maxRows) * ROW_H + FOOTER_H;
        int mH = loading && servers.isEmpty() ? (HEADER_H + ROW_H + FOOTER_H) : calculated_mH;
        mH = Math.min(mH, max_mH);

        int mX = cx - MODAL_W / 2;
        int mY = cy - mH / 2;

        maxVisibleRows = (mH - HEADER_H - FOOTER_H) / ROW_H;

        ctx.fill(mX, mY, mX + MODAL_W, mY + mH, 0xEE0B0B0B);
        drawRectBorder(ctx, mX, mY, MODAL_W, mH, 0xFF444444);

        ctx.drawTextWithShadow(textRenderer, "🌐 서버 변경", mX + PAD, mY + 12, 0xAADDFF);
        ctx.fill(mX + 8, mY + HEADER_H - 2, mX + MODAL_W - 8, mY + HEADER_H - 1, 0xFF333333);

        // ========================================================
        // ★ 수동 검색창 렌더링
        // ========================================================
        int searchX = mX + PAD;
        int searchY = mY + 30;
        int searchW = MODAL_W - PAD * 2;
        int searchH = 20;

        ctx.fill(searchX, searchY, searchX + searchW, searchY + searchH, 0xFF000000);
        drawRectBorder(ctx, searchX, searchY, searchW, searchH, isSearchFocused ? 0xFFFFFFFF : 0xFF666666);

        if (searchQuery.isEmpty() && !isSearchFocused) {
            ctx.drawTextWithShadow(textRenderer, "서버 이름, 주소 검색...", searchX + 6, searchY + 6, 0x777777);
        } else {
            String displayStr = textRenderer.trimToWidth(searchQuery, searchW - 15);
            boolean showCursor = isSearchFocused && (System.currentTimeMillis() / 500 % 2 == 0);
            ctx.drawTextWithShadow(textRenderer, displayStr + (showCursor ? "_" : ""), searchX + 6, searchY + 6, 0xFFFFFF);
        }

        String currentState = CurrentServerHolder.get();
        boolean isSingleplayMode = currentState.equals(CurrentServerHolder.SINGLEPLAY);
        String connectedAddress = isSingleplayMode ? null : currentState;

        String queryState = CurrentServerHolder.getForQuery();
        boolean isQuerySingleplay = queryState.equals(CurrentServerHolder.SINGLEPLAY);
        String queryAddress = isQuerySingleplay ? null : queryState;

        int maxScroll = Math.max(0, entries.size() - maxVisibleRows);
        scrollOffset = Math.max(0, Math.min(scrollOffset, maxScroll));
        int end = Math.min(entries.size(), scrollOffset + maxVisibleRows);

        if (loading && servers.isEmpty()) {
            ctx.drawCenteredTextWithShadow(textRenderer, "불러오는 중...", cx, mY + HEADER_H + 16, 0xAAAAAA);
        } else if (entries.isEmpty()) {
            ctx.drawCenteredTextWithShadow(textRenderer, "검색 결과가 없습니다.", cx, mY + HEADER_H + 16, 0xAAAAAA);
        } else {
            int rowY = mY + HEADER_H;
            for (int i = scrollOffset; i < end; i++) {
                ServerEntry e = entries.get(i);
                boolean isSingle = e.key().equals("__singleplay__");
                boolean isLocked = !e.isAccessible(); // ★ 접근 권한 체크 (개발서버, 싱글플레이 공통)

                boolean isConnected = isSingle
                        ? isSingleplayMode
                        : (connectedAddress != null && e.address().equalsIgnoreCase(connectedAddress));

                boolean isViewingRanking = isSingle
                        ? isQuerySingleplay
                        : (queryAddress != null && e.address().equalsIgnoreCase(queryAddress));

                boolean hover = isInside(mouseX, mouseY, mX + 1, rowY, MODAL_W - 2, ROW_H);

                int rowBg = isViewingRanking ? 0xFF0D1F0D : (hover ? 0xFF1A2A1A : 0xFF0D0D0D);
                ctx.fill(mX + 1, rowY, mX + MODAL_W - 1, rowY + ROW_H, rowBg);
                ctx.fill(mX + 8, rowY + ROW_H - 1, mX + MODAL_W - 8, rowY + ROW_H, 0xFF222222);

                String icon = isSingle ? "🖥" : "🌐";
                ctx.drawTextWithShadow(textRenderer, icon, mX + PAD, rowY + 10, 0xFFFFFF);

                String titleStr = e.title().isBlank() ? e.displayAddress() : e.title();
                int titleColor = isSingle ? 0xAAFFCC : (isViewingRanking ? 0x55FF55 : 0xFFFFFF);
                ctx.drawTextWithShadow(textRenderer, "§l" + titleStr, mX + PAD + 18, rowY + 8, titleColor);

                int badgeOffset = 0;
                if (e.isMainServer()) {
                    int titleW = textRenderer.getWidth("§l" + titleStr);
                    String mainBadge = "§6[공식 서버]";
                    ctx.drawTextWithShadow(textRenderer, mainBadge, mX + PAD + 18 + titleW + 6, rowY + 8, 0xFFAA00);
                    badgeOffset = textRenderer.getWidth(mainBadge) + 6;
                } else if (e.isDevServer()) {
                    int titleW = textRenderer.getWidth("§l" + titleStr);
                    String devBadge = "§3[개발 서버]";
                    ctx.drawTextWithShadow(textRenderer, devBadge, mX + PAD + 18 + titleW + 6, rowY + 8, 0x55FFFF);
                    badgeOffset = textRenderer.getWidth(devBadge) + 6;
                }

                if (!isSingle) {
                    String addrStr = "§7" + e.displayAddress();
                    int addrW  = textRenderer.getWidth(addrStr);
                    int addrX  = mX + MODAL_W - PAD - addrW;
                    int nameEndX = mX + PAD + 18 + textRenderer.getWidth("§l" + titleStr) + badgeOffset + 6;
                    if (addrX > nameEndX) {
                        ctx.drawTextWithShadow(textRenderer, addrStr, addrX, rowY + 8, 0xAAAAAA);
                    }
                }

                if (isConnected) {
                    String badge   = "§a[현재 속한 서버]";
                    int   badgeX   = mX + PAD + 18;
                    ctx.drawTextWithShadow(textRenderer, badge, badgeX, rowY + 24, 0x55FF55);

                    if (e.recordCount() >= 0) {
                        String recStr = "§7누적 기록 §e" + e.recordCount() + "개";
                        int    recX   = badgeX + textRenderer.getWidth(badge) + 8;
                        ctx.drawTextWithShadow(textRenderer, recStr, recX, rowY + 24, 0xAAAAAA);
                    }
                } else if (e.recordCount() >= 0) {
                    String recStr = "§7누적 기록 §e" + e.recordCount() + "개";
                    ctx.drawTextWithShadow(textRenderer, recStr, mX + PAD + 18, rowY + 24, 0xAAAAAA);
                }

                // ★ 싱글플레이 전용 안내 버튼 (잠겨있지 않을 때만 표시)
                if (isSingle && !isLocked) {
                    int infoBtnW = 44;
                    int infoBtnH = 18;
                    int infoBtnX = mX + MODAL_W - PAD - infoBtnW;
                    int infoBtnY = rowY + 16;
                    boolean hoverInfo = isInside(mouseX, mouseY, infoBtnX, infoBtnY, infoBtnW, infoBtnH);

                    ctx.fill(infoBtnX, infoBtnY, infoBtnX + infoBtnW, infoBtnY + infoBtnH, hoverInfo ? 0xFF334433 : 0xFF1A2A1A);
                    drawRectBorder(ctx, infoBtnX, infoBtnY, infoBtnW, infoBtnH, hoverInfo ? 0xFF66AA66 : 0xFF335533);
                    ctx.drawCenteredTextWithShadow(textRenderer, "안내 ℹ", infoBtnX + infoBtnW / 2, infoBtnY + 5, 0xFFFFFF);
                }

                if (isLocked) {
                    ctx.fill(mX + 1, rowY, mX + MODAL_W - 1, rowY + ROW_H, 0xBB000000);
                    String locked = "§c⛔ 접근 불가";
                    ctx.drawTextWithShadow(textRenderer, locked,
                            mX + MODAL_W - PAD - textRenderer.getWidth(locked), rowY + 16, 0xFF5555);
                } else if (hover) {
                    int offset = isSingle ? 50 : 0;
                    String hint = "§a▶ 선택";
                    ctx.drawTextWithShadow(textRenderer, hint,
                            mX + MODAL_W - PAD - textRenderer.getWidth(hint) - offset, rowY + 24, 0x88FFFFFF);
                }

                rowY += ROW_H;
            }
        }

        // 스크롤 바 렌더링
        if (maxScroll > 0) {
            int barW = 6;
            int barX = mX + MODAL_W - barW - 4;
            int barY = mY + HEADER_H;
            int barH = maxVisibleRows * ROW_H;
            ctx.fill(barX, barY, barX + barW, barY + barH, 0x55000000);

            int thumbH = Math.max(16, (int) (barH * ((float) maxVisibleRows / entries.size())));
            int thumbY = barY + (int) ((barH - thumbH) * ((float) scrollOffset / maxScroll));
            ctx.fill(barX, thumbY, barX + barW, thumbY + thumbH, 0xFF888888);
        }

        int closeW  = 50;
        int closeX  = cx - closeW / 2;
        int closeY  = mY + mH - FOOTER_H + 8;
        boolean hoverClose = isInside(mouseX, mouseY, closeX, closeY, closeW, 20);
        ctx.fill(closeX, closeY, closeX + closeW, closeY + 20, hoverClose ? 0xFF333333 : 0xFF1A1A1A);
        drawRectBorder(ctx, closeX, closeY, closeW, 20, hoverClose ? 0xFF666666 : 0xFF444444);
        ctx.drawCenteredTextWithShadow(textRenderer, "닫기", closeX + closeW / 2, closeY + 6, 0xFFFFFF);

        ctx.getMatrices().pop();
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        int maxScroll = Math.max(0, getFilteredEntries().size() - maxVisibleRows);
        if (maxScroll > 0) {
            if (verticalAmount > 0) scrollOffset--;
            else if (verticalAmount < 0) scrollOffset++;

            scrollOffset = Math.max(0, Math.min(scrollOffset, maxScroll));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        int cx = width / 2, cy = height / 2;

        if (showDevBlockAlert) {
            int alertW = MODAL_W - 20, alertH = 110;
            int alertX = cx - alertW / 2, alertY = cy - alertH / 2;
            int confirmW = 60, confirmX = cx - confirmW / 2, confirmY = alertY + alertH - 28;
            if (isInside(mouseX, mouseY, confirmX, confirmY, confirmW, 20) || !isInside(mouseX, mouseY, alertX, alertY, alertW, alertH)) {
                playUiClick(); showDevBlockAlert = false;
            }
            return true;
        }

        if (showSingleBlockAlert) {
            int alertW = MODAL_W - 20, alertH = 110;
            int alertX = cx - alertW / 2, alertY = cy - alertH / 2;
            int confirmW = 60, confirmX = cx - confirmW / 2, confirmY = alertY + alertH - 28;
            if (isInside(mouseX, mouseY, confirmX, confirmY, confirmW, 20) || !isInside(mouseX, mouseY, alertX, alertY, alertW, alertH)) {
                playUiClick(); showSingleBlockAlert = false;
            }
            return true;
        }

        if (showSingleplayInfo) {
            int alertW = MODAL_W - 20, alertH = 170;
            int alertX = cx - alertW / 2, alertY = cy - alertH / 2;
            int confirmW = 60, confirmX = cx - confirmW / 2, confirmY = alertY + alertH - 28;
            if (isInside(mouseX, mouseY, confirmX, confirmY, confirmW, 20) || !isInside(mouseX, mouseY, alertX, alertY, alertW, alertH)) {
                playUiClick(); showSingleplayInfo = false;
            }
            return true;
        }

        List<ServerEntry> entries = loading && servers.isEmpty() ? List.of() : getFilteredEntries();
        int max_mH = height - 40;
        int maxRows = (max_mH - HEADER_H - FOOTER_H) / ROW_H;
        if (maxRows < 1) maxRows = 1;

        int rowCount = entries.isEmpty() ? 1 : entries.size();
        int mH = loading && servers.isEmpty() ? (HEADER_H + ROW_H + FOOTER_H) : (HEADER_H + Math.min(rowCount, maxRows) * ROW_H + FOOTER_H);
        mH = Math.min(mH, max_mH);

        int mX = cx - MODAL_W / 2;
        int mY = cy - mH / 2;

        int searchX = mX + PAD; int searchY = mY + 30; int searchW = MODAL_W - PAD * 2; int searchH = 20;

        if (isInside(mouseX, mouseY, searchX, searchY, searchW, searchH)) {
            isSearchFocused = true; playUiClick(); return true;
        } else {
            isSearchFocused = false;
        }

        int closeW = 50;
        int closeX = cx - closeW / 2;
        int closeY = mY + mH - FOOTER_H + 8;
        if (isInside(mouseX, mouseY, closeX, closeY, closeW, 20)) {
            playUiClick(); close(); return true;
        }

        if (!isInside(mouseX, mouseY, mX, mY, MODAL_W, mH)) {
            playUiClick(); close(); return true;
        }

        if (!loading && !entries.isEmpty()) {
            int rowY = mY + HEADER_H;
            int end = Math.min(entries.size(), scrollOffset + maxVisibleRows);

            for (int i = scrollOffset; i < end; i++) {
                ServerEntry e = entries.get(i);
                boolean isSingle = e.key().equals("__singleplay__");

                // ★ 안내 버튼 클릭 우선 확인
                if (isSingle && e.isAccessible()) {
                    int infoBtnW = 44;
                    int infoBtnH = 18;
                    int infoBtnX = mX + MODAL_W - PAD - infoBtnW;
                    int infoBtnY = rowY + 16;
                    if (isInside(mouseX, mouseY, infoBtnX, infoBtnY, infoBtnW, infoBtnH)) {
                        playUiClick();
                        showSingleplayInfo = true;
                        return true;
                    }
                }

                if (isInside(mouseX, mouseY, mX + 1, rowY, MODAL_W - 2, ROW_H)) {
                    playUiClick();
                    onServerSelected(e);
                    return true;
                }
                rowY += ROW_H;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private boolean isValidChar(char chr) { return chr >= ' ' && chr != 127 && chr != '§'; }

    @Override
    public boolean charTyped(char chr, int modifiers) {
        if (isSearchFocused && isValidChar(chr)) {
            if (searchQuery.length() < 50) { searchQuery += chr; scrollOffset = 0; }
            return true;
        }
        return super.charTyped(chr, modifiers);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (isSearchFocused) {
            if (keyCode == 259 && !searchQuery.isEmpty()) {
                searchQuery = searchQuery.substring(0, searchQuery.length() - 1);
                scrollOffset = 0; return true;
            }
            else if (keyCode == 256) { close(); return true; }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private void onServerSelected(ServerEntry e) {
        boolean isSingle = e.key().equals("__singleplay__");
        MinecraftClient mc = this.client;
        if (mc == null) return;

        if (e.isDevServer() && !e.isAccessible()) {
            mc.getSoundManager().play(PositionedSoundInstance.master(net.minecraft.sound.SoundEvents.ENTITY_VILLAGER_NO, 1.0f));
            showDevBlockAlert = true;
            return;
        } else if (isSingle && !e.isAccessible()) {
            mc.getSoundManager().play(PositionedSoundInstance.master(net.minecraft.sound.SoundEvents.ENTITY_VILLAGER_NO, 1.0f));
            showSingleBlockAlert = true;
            return;
        }

        CurrentServerHolder.setQueryOverride(isSingle ? CurrentServerHolder.SINGLEPLAY : e.address());
        RankingScreen.ApiCache.clearCache();
        close();
    }

    /** 싱글플레이 안내 모달 */
    private void renderSingleplayInfo(DrawContext ctx, int mouseX, int mouseY) {
        ctx.getMatrices().push();
        ctx.getMatrices().translate(0, 0, 400);
        ctx.fill(0, 0, width, height, 0xAA000000);

        int cx = width / 2, cy = height / 2;
        int alertW = MODAL_W - 20;
        int alertH = 170;
        int alertX = cx - alertW / 2;
        int alertY = cy - alertH / 2;

        ctx.fill(alertX, alertY, alertX + alertW, alertY + alertH, 0xEE111111);
        drawRectBorder(ctx, alertX, alertY, alertW, alertH, 0xFF666666);

        int textY = alertY + 16;
        ctx.drawCenteredTextWithShadow(textRenderer, "§e📌 싱글플레이 기록 안내", cx, textY, 0xFFFFFF);

        textY += 22;
        ctx.drawCenteredTextWithShadow(textRenderer, "§7싱글플레이는 멀티플레이 서버와 달리 네트워크 검증이 없어", cx, textY, 0xAAAAAA); textY += 14;
        ctx.drawCenteredTextWithShadow(textRenderer, "§7비정상적인 데이터가 기록될 가능성이 존재합니다.", cx, textY, 0xAAAAAA); textY += 14;
        ctx.drawCenteredTextWithShadow(textRenderer, "§c오류나 의심되는 기록을 발견하시면 제보를 부탁드립니다.", cx, textY, 0xAAAAAA); textY += 22;

        ctx.drawCenteredTextWithShadow(textRenderer, "§b🔍 데이터 조작 방지 스탯 확인 기능", cx, textY, 0xFFFFFF); textY += 16;
        ctx.drawCenteredTextWithShadow(textRenderer, "§7싱글플레이 기록은 랭킹 목록에서 카드를 클릭한 뒤,", cx, textY, 0xAAAAAA); textY += 14;
        ctx.drawCenteredTextWithShadow(textRenderer, "§7카트바디 이름 옆의 §f[+]§7 버튼을 누르면", cx, textY, 0xAAAAAA); textY += 14;
        ctx.drawCenteredTextWithShadow(textRenderer, "§7주행 당시 사용된 카트의 상세 스탯을 확인할 수 있습니다.", cx, textY, 0xAAAAAA);

        int confirmW = 60;
        int confirmX = cx - confirmW / 2;
        int confirmY = alertY + alertH - 28;
        boolean hoverConfirm = isInside(mouseX, mouseY, confirmX, confirmY, confirmW, 20);
        ctx.fill(confirmX, confirmY, confirmX + confirmW, confirmY + 20, hoverConfirm ? 0xFF333333 : 0xFF1A1A1A);
        drawRectBorder(ctx, confirmX, confirmY, confirmW, 20, hoverConfirm ? 0xFF888888 : 0xFF555555);
        ctx.drawCenteredTextWithShadow(textRenderer, "확인", confirmX + confirmW / 2, confirmY + 6, 0xFFFFFF);

        ctx.getMatrices().pop();
    }

    /** 싱글플레이 차단 안내 모달 */
    private void renderSingleBlockAlert(DrawContext ctx, int mouseX, int mouseY) {
        ctx.getMatrices().push();
        ctx.getMatrices().translate(0, 0, 400);
        ctx.fill(0, 0, width, height, 0xAA000000);

        int cx = width / 2, cy = height / 2;
        int alertW = MODAL_W - 20;
        int alertH = 110;
        int alertX = cx - alertW / 2;
        int alertY = cy - alertH / 2;

        ctx.fill(alertX, alertY, alertX + alertW, alertY + alertH, 0xEE1A0000);
        drawRectBorder(ctx, alertX, alertY, alertW, alertH, 0xFFFF5555);

        int textY = alertY + 12;
        ctx.drawCenteredTextWithShadow(textRenderer, "§c⛔  싱글플레이 접근이 제한되었습니다.", cx, textY, 0xFF5555);
        ctx.drawCenteredTextWithShadow(textRenderer, "§7현재 서버 설정에 의해", cx, textY + 16, 0xAAAAAA);
        ctx.drawCenteredTextWithShadow(textRenderer, "§7싱글플레이 랭킹 열람이 비활성화되어 있습니다.", cx, textY + 28, 0xAAAAAA);

        int confirmW = 60;
        int confirmX = cx - confirmW / 2;
        int confirmY = alertY + alertH - 28;
        boolean hoverConfirm = isInside(mouseX, mouseY, confirmX, confirmY, confirmW, 20);
        ctx.fill(confirmX, confirmY, confirmX + confirmW, confirmY + 20, hoverConfirm ? 0xFF333333 : 0xFF1A1A1A);
        drawRectBorder(ctx, confirmX, confirmY, confirmW, 20, hoverConfirm ? 0xFF888888 : 0xFF555555);
        ctx.drawCenteredTextWithShadow(textRenderer, "확인", confirmX + confirmW / 2, confirmY + 6, 0xFFFFFF);

        ctx.getMatrices().pop();
    }

    /** 개발 서버 차단 안내 */
    private void renderDevBlockAlert(DrawContext ctx, int mouseX, int mouseY) {
        ctx.getMatrices().push();
        ctx.getMatrices().translate(0, 0, 400);
        ctx.fill(0, 0, width, height, 0xAA000000);

        int cx = width / 2, cy = height / 2;
        int alertW = MODAL_W - 20;
        int alertH = 110;
        int alertX = cx - alertW / 2;
        int alertY = cy - alertH / 2;

        ctx.fill(alertX, alertY, alertX + alertW, alertY + alertH, 0xEE1A0000);
        drawRectBorder(ctx, alertX, alertY, alertW, alertH, 0xFFFF5555);

        int textY = alertY + 12;
        ctx.drawCenteredTextWithShadow(textRenderer, "§c⛔  접근 권한이 없는 개발 서버입니다.", cx, textY, 0xFF5555);
        ctx.drawCenteredTextWithShadow(textRenderer, "§7이 서버의 랭킹은 사전에 등록된", cx, textY + 16, 0xAAAAAA);
        ctx.drawCenteredTextWithShadow(textRenderer, "§7플레이어(화이트리스트)만 열람할 수 있습니다.", cx, textY + 28, 0xAAAAAA);
        ctx.drawCenteredTextWithShadow(textRenderer, "§8열람을 원하시면 개발팀에 문의해 주세요.", cx, textY + 42, 0x777777);

        int confirmW = 60;
        int confirmX = cx - confirmW / 2;
        int confirmY = alertY + alertH - 28;
        boolean hoverConfirm = isInside(mouseX, mouseY, confirmX, confirmY, confirmW, 20);
        ctx.fill(confirmX, confirmY, confirmX + confirmW, confirmY + 20, hoverConfirm ? 0xFF333333 : 0xFF1A1A1A);
        drawRectBorder(ctx, confirmX, confirmY, confirmW, 20, hoverConfirm ? 0xFF888888 : 0xFF555555);
        ctx.drawCenteredTextWithShadow(textRenderer, "확인", confirmX + confirmW / 2, confirmY + 6, 0xFFFFFF);

        ctx.getMatrices().pop();
    }

    @Override public boolean shouldPause() { return false; }
    @Override public void close() { if (client != null) client.setScreen(parent); }
}