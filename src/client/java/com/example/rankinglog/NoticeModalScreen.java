package com.example.rankinglog;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

public class NoticeModalScreen extends Screen {
    private final Screen parent;
    private static final List<Notice> notices = new ArrayList<>();
    private static boolean loading = false;
    private int page = 0;
    private double scrollAmount = 0;

    private static class Notice {
        String title;
        List<OrderedText> wrappedDesc;
        int totalHeight;
    }

    public NoticeModalScreen(Screen parent) {
        super(Text.literal("공지사항"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        if (notices.isEmpty() && !loading) {
            fetchNotices();
        }
    }

    private void fetchNotices() {
        loading = true;
        new Thread(() -> {
            try {
                JsonObject obj = RankingScreen.Net.postJson(RankingScreen.SUPABASE_RPC_URL + "get_notices", "{}");
                if (obj.has("ok") && obj.get("ok").getAsBoolean()) {
                    notices.clear();
                    JsonArray arr = obj.getAsJsonArray("notices");
                    for (int i = 0; i < arr.size(); i++) {
                        JsonObject n = arr.get(i).getAsJsonObject();
                        Notice notice = new Notice();
                        notice.title = n.get("title").getAsString();
                        // 텍스트를 모달 너비에 맞게 줄바꿈 처리
                        notice.wrappedDesc = textRenderer.wrapLines(Text.literal(n.get("desc").getAsString()), 350);
                        notice.totalHeight = notice.wrappedDesc.size() * 14;
                        notices.add(notice);
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                if (this.client != null) {
                    this.client.execute(() -> {
                        loading = false;
                        page = 0;
                        scrollAmount = 0;
                    });
                }
            }
        }).start();
    }

    private void playUiClick() {
        if (this.client != null) this.client.getSoundManager().play(PositionedSoundInstance.master(SoundEvents.UI_BUTTON_CLICK, 1.0f));
    }

    private boolean isInside(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx <= x + w && my >= y && my <= y + h;
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        // 1. 부모 화면 렌더링 (배경 유지)
        parent.render(context, -100, -100, delta);

        // ★ 드롭다운과 동일한 기술: 전체 렌더링을 Z축 400으로 완전히 띄워서 겹침/흐려짐 방지 (위젯 미사용)
        context.getMatrices().push();
        context.getMatrices().translate(0, 0, 400);

        // 어두운 반투명 배경
        context.fill(0, 0, width, height, 0xAA000000);

        int cx = width / 2;
        int cy = height / 2;
        int modalW = 380;
        int modalH = 220;
        int modalX = cx - modalW / 2;
        int modalY = cy - modalH / 2;

        // 2. 드롭다운 스타일의 선명한 배경 및 테두리 적용
        context.fill(modalX, modalY, modalX + modalW, modalY + modalH, 0xEE0B0B0B);
        drawRectBorder(context, modalX, modalY, modalW, modalH, 0xFF444444);

        // 3. 상단 헤더
        context.drawTextWithShadow(textRenderer, "📢 공지사항", modalX + 15, modalY + 12, 0xFFDDAA);
        context.fill(modalX + 10, modalY + 28, modalX + modalW - 10, modalY + 29, 0xFF333333); // 구분선

        if (loading) {
            context.drawCenteredTextWithShadow(textRenderer, "불러오는 중...", cx, cy, 0xAAAAAA);
        } else if (notices.isEmpty()) {
            context.drawCenteredTextWithShadow(textRenderer, "공지사항이 없습니다.", cx, cy, 0xAAAAAA);
        } else {
            Notice n = notices.get(page);

            // 해당 페이지 공지 제목
            context.drawTextWithShadow(textRenderer, "§l" + n.title, modalX + 15, modalY + 38, 0xFFFFFF);

            // 텍스트 출력 영역 및 스크롤 클리핑 (Scissor)
            int contentY = modalY + 55;
            int contentH = modalH - 55 - 35;

            context.enableScissor(modalX + 15, contentY, modalX + modalW - 15, contentY + contentH);
            int textY = contentY - (int)scrollAmount;

            for (OrderedText line : n.wrappedDesc) {
                if (textY + 14 >= contentY && textY <= contentY + contentH) {
                    context.drawTextWithShadow(textRenderer, line, modalX + 15, textY, 0xFFDDDDDD);
                }
                textY += 14;
            }
            context.disableScissor();

            // 4. 스크롤바 렌더링
            int maxScroll = Math.max(0, n.totalHeight - contentH);
            if (maxScroll > 0) {
                int barX = modalX + modalW - 8;
                context.fill(barX, contentY, barX + 4, contentY + contentH, 0x55000000);
                int thumbH = Math.max(10, (int) (contentH * ((float) contentH / n.totalHeight)));
                int thumbY = contentY + (int) ((contentH - thumbH) * (scrollAmount / maxScroll));
                context.fill(barX, thumbY, barX + 4, thumbY + thumbH, 0xFF888888);
            }

            // 페이지 번호 (1 / N)
            context.drawCenteredTextWithShadow(textRenderer, (page + 1) + " / " + notices.size(), cx, modalY + modalH - 20, 0xAAAAAA);
        }

        // ========================================================
        // 5. 버튼 수동 렌더링 (마우스 호버, 비활성화 상태 등을 직접 그림)
        // ========================================================

        // ◀ 이전 버튼
        int prevX = modalX + 15;
        int prevY = modalY + modalH - 26;
        boolean canPrev = page > 0;
        boolean hoverPrev = canPrev && isInside(mouseX, mouseY, prevX, prevY, 20, 20);
        context.fill(prevX, prevY, prevX + 20, prevY + 20, canPrev ? (hoverPrev ? 0xFF333333 : 0xFF111111) : 0xFF0A0A0A);
        drawRectBorder(context, prevX, prevY, 20, 20, canPrev ? (hoverPrev ? 0xFF666666 : 0xFF444444) : 0xFF222222);
        context.drawCenteredTextWithShadow(textRenderer, "◀", prevX + 10, prevY + 6, canPrev ? 0xFFFFFF : 0xFF555555);

        // ▶ 다음 버튼
        int nextX = modalX + 40;
        int nextY = modalY + modalH - 26;
        boolean canNext = page < notices.size() - 1;
        boolean hoverNext = canNext && isInside(mouseX, mouseY, nextX, nextY, 20, 20);
        context.fill(nextX, nextY, nextX + 20, nextY + 20, canNext ? (hoverNext ? 0xFF333333 : 0xFF111111) : 0xFF0A0A0A);
        drawRectBorder(context, nextX, nextY, 20, 20, canNext ? (hoverNext ? 0xFF666666 : 0xFF444444) : 0xFF222222);
        context.drawCenteredTextWithShadow(textRenderer, "▶", nextX + 10, nextY + 6, canNext ? 0xFFFFFF : 0xFF555555);

        // 닫기 버튼
        int closeW = 40;
        int closeX = modalX + modalW - 15 - closeW;
        int closeY = modalY + modalH - 26;
        boolean hoverClose = isInside(mouseX, mouseY, closeX, closeY, closeW, 20);
        context.fill(closeX, closeY, closeX + closeW, closeY + 20, hoverClose ? 0xFF333333 : 0xFF111111);
        drawRectBorder(context, closeX, closeY, closeW, 20, hoverClose ? 0xFF666666 : 0xFF444444);
        context.drawCenteredTextWithShadow(textRenderer, "닫기", closeX + closeW / 2, closeY + 6, 0xFFFFFF);

        context.getMatrices().pop();
        // ★ super.render() 삭제 완료 (위젯을 사용하지 않으므로 호출 금지)
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        int cx = width / 2;
        int cy = height / 2;
        int modalW = 380;
        int modalH = 220;
        int modalX = cx - modalW / 2;
        int modalY = cy - modalH / 2;

        // 이전 버튼 클릭
        if (page > 0 && isInside(mouseX, mouseY, modalX + 15, modalY + modalH - 26, 20, 20)) {
            playUiClick();
            page--;
            scrollAmount = 0;
            return true;
        }

        // 다음 버튼 클릭
        if (page < notices.size() - 1 && isInside(mouseX, mouseY, modalX + 40, modalY + modalH - 26, 20, 20)) {
            playUiClick();
            page++;
            scrollAmount = 0;
            return true;
        }

        // 닫기 버튼 클릭
        int closeW = 40;
        if (isInside(mouseX, mouseY, modalX + modalW - 15 - closeW, modalY + modalH - 26, closeW, 20)) {
            playUiClick();
            close();
            return true;
        }

        // 모달 바깥 배경 클릭 시 닫기
        if (!isInside(mouseX, mouseY, modalX, modalY, modalW, modalH)) {
            playUiClick();
            close();
            return true;
        }

        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (!notices.isEmpty() && !loading) {
            Notice n = notices.get(page);
            int modalH = 220;
            int contentH = modalH - 55 - 35;
            int maxScroll = Math.max(0, n.totalHeight - contentH);

            scrollAmount -= verticalAmount * 15;
            scrollAmount = Math.max(0, Math.min(scrollAmount, maxScroll));

            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    private void drawRectBorder(DrawContext c, int x, int y, int w, int h, int color) {
        c.fill(x, y, x + w, y + 1, color);
        c.fill(x, y + h - 1, x + w, y + h, color);
        c.fill(x, y, x + 1, y + h, color);
        c.fill(x + w - 1, y, x + w, y + h, color);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    public void close() {
        if (client != null) client.setScreen(parent);
    }
}