package com.example.rankinglog;

import com.google.gson.JsonObject;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

/**
 * 기록 삭제 확인 모달 (NoticeModalScreen과 동일한 스타일: 부모 화면을 배경으로 유지한 채
 * Z축 400으로 띄운 어두운 오버레이 + 패널을 직접 그리는 방식).
 *
 * 1단계: 본인 닉네임을 정확히 입력해야 다음으로 진행 가능.
 * 2단계: 마지막으로 한 번 더 확인 후 실제 삭제 요청 전송.
 */
public class DeleteRecordConfirmScreen extends Screen {

    private static final int MODAL_W = 280;
    private static final int MODAL_H = 170;

    private final Screen parent;
    private final String player;
    private final String recordId;
    private final Runnable onDeleted;

    private int step = 1;
    private TextFieldWidget nicknameField;
    private String errorMsg = null;
    private boolean submitting = false;

    public DeleteRecordConfirmScreen(Screen parent, String player, String recordId, Runnable onDeleted) {
        super(Text.literal("기록 삭제"));
        this.parent = parent;
        this.player = player;
        this.recordId = recordId;
        this.onDeleted = onDeleted;
    }

    @Override
    protected void init() {
        int cx = width / 2;
        int cy = height / 2;
        int modalX = cx - MODAL_W / 2;
        int modalY = cy - MODAL_H / 2;

        nicknameField = new TextFieldWidget(this.textRenderer, modalX + 15, modalY + 62, MODAL_W - 30, 20, Text.literal("닉네임"));
        nicknameField.setMaxLength(32);
        nicknameField.setFocused(true);
    }

    private void playUiClick() {
        if (this.client != null) this.client.getSoundManager().play(PositionedSoundInstance.master(SoundEvents.UI_BUTTON_CLICK, 1.0f));
    }

    private boolean isInside(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx <= x + w && my >= y && my <= y + h;
    }

    private void tryValidateNickname() {
        String typed = nicknameField.getText();
        if (typed == null || !typed.equals(player)) {
            errorMsg = "닉네임이 일치하지 않습니다. 정확히 입력해주세요.";
            return;
        }
        errorMsg = null;
        step = 2;
    }

    private void doDelete() {
        if (submitting) return;
        submitting = true;
        errorMsg = null;

        var session = MinecraftClient.getInstance().getSession();
        String uuid = String.valueOf(session.getUuidOrNull());
        String token = session.getAccessToken();

        new Thread(() -> {
            JsonObject res = AddRankingScreen.deleteRecord(player, recordId, uuid, token);
            boolean ok = res != null && res.has("ok") && res.get("ok").getAsBoolean();

            MinecraftClient.getInstance().execute(() -> {
                if (ok) {
                    if (onDeleted != null) onDeleted.run();
                    if (this.client != null) this.client.setScreen(parent);
                } else {
                    String err = (res != null && res.has("message")) ? res.get("message").getAsString()
                            : (res != null && res.has("error")) ? res.get("error").getAsString() : "알 수 없는 오류";
                    errorMsg = "삭제 실패: " + err;
                    submitting = false;
                }
            });
        }, "record-delete-thread").start();
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        // 1. 부모 화면(랭킹 스크린) 렌더링 (배경 유지)
        parent.render(context, -100, -100, delta);

        // ★ Notice 모달과 동일한 기술: Z축 400으로 완전히 띄워서 겹침/흐려짐 방지
        context.getMatrices().push();
        context.getMatrices().translate(0, 0, 400);

        context.fill(0, 0, width, height, 0xAA000000);

        int cx = width / 2;
        int cy = height / 2;
        int modalX = cx - MODAL_W / 2;
        int modalY = cy - MODAL_H / 2;

        context.fill(modalX, modalY, modalX + MODAL_W, modalY + MODAL_H, 0xEE0B0B0B);
        drawRectBorder(context, modalX, modalY, MODAL_W, MODAL_H, 0xFF444444);

        context.drawTextWithShadow(textRenderer, "🗑 기록 삭제", modalX + 15, modalY + 12, 0xFF8888);
        context.fill(modalX + 10, modalY + 28, modalX + MODAL_W - 10, modalY + 29, 0xFF333333);

        if (step == 1) {
            context.drawTextWithShadow(textRenderer, "§7본인 닉네임을 정확히 입력하세요.", modalX + 15, modalY + 38, 0xAAAAAA);
            context.drawTextWithShadow(textRenderer, "§7대상: §f" + player, modalX + 15, modalY + 50, 0xFFFFFF);
            nicknameField.render(context, mouseX, mouseY, delta);
        } else {
            context.drawTextWithShadow(textRenderer, "§c정말로 이 기록을 삭제하시겠습니까?", modalX + 15, modalY + 42, 0xFFFFFF);
            context.drawTextWithShadow(textRenderer, "§7이 작업은 되돌릴 수 없습니다.", modalX + 15, modalY + 56, 0xAAAAAA);
        }

        if (errorMsg != null) {
            context.drawTextWithShadow(textRenderer, "§c" + errorMsg, modalX + 15, modalY + MODAL_H - 52, 0xFF5555);
        } else if (submitting) {
            context.drawTextWithShadow(textRenderer, "§7삭제 요청 중...", modalX + 15, modalY + MODAL_H - 52, 0xAAAAAA);
        }

        // ========================================================
        // 버튼 수동 렌더링 (Notice 모달과 동일하게 위젯 없이 직접 그림)
        // ========================================================
        int btnW = 60, btnH = 20;
        int confirmX = modalX + MODAL_W - 15 - btnW;
        int confirmY = modalY + MODAL_H - 30;
        int cancelX = confirmX - 10 - btnW;
        int cancelY = confirmY;

        boolean hoverCancel = isInside(mouseX, mouseY, cancelX, cancelY, btnW, btnH);
        context.fill(cancelX, cancelY, cancelX + btnW, cancelY + btnH, hoverCancel ? 0xFF333333 : 0xFF111111);
        drawRectBorder(context, cancelX, cancelY, btnW, btnH, hoverCancel ? 0xFF666666 : 0xFF444444);
        context.drawCenteredTextWithShadow(textRenderer, "취소", cancelX + btnW / 2, cancelY + 6, 0xFFFFFF);

        String confirmLabel = step == 1 ? "확인" : "삭제";
        boolean hoverConfirm = isInside(mouseX, mouseY, confirmX, confirmY, btnW, btnH);
        int confirmBg = step == 2 ? (hoverConfirm ? 0xFF773333 : 0xFF551111) : (hoverConfirm ? 0xFF333333 : 0xFF111111);
        int confirmBorder = step == 2 ? (hoverConfirm ? 0xFFFF8888 : 0xFFAA4444) : (hoverConfirm ? 0xFF666666 : 0xFF444444);
        context.fill(confirmX, confirmY, confirmX + btnW, confirmY + btnH, confirmBg);
        drawRectBorder(context, confirmX, confirmY, btnW, btnH, confirmBorder);
        context.drawCenteredTextWithShadow(textRenderer, confirmLabel, confirmX + btnW / 2, confirmY + 6, 0xFFFFFF);

        context.getMatrices().pop();
        // ★ 위젯을 addDrawableChild로 등록하지 않으므로 super.render() 호출 금지 (Notice 모달과 동일)
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        int cx = width / 2;
        int cy = height / 2;
        int modalX = cx - MODAL_W / 2;
        int modalY = cy - MODAL_H / 2;

        int btnW = 60, btnH = 20;
        int confirmX = modalX + MODAL_W - 15 - btnW;
        int confirmY = modalY + MODAL_H - 30;
        int cancelX = confirmX - 10 - btnW;
        int cancelY = confirmY;

        if (isInside(mouseX, mouseY, cancelX, cancelY, btnW, btnH)) {
            playUiClick();
            close();
            return true;
        }

        if (isInside(mouseX, mouseY, confirmX, confirmY, btnW, btnH)) {
            playUiClick();
            if (step == 1) tryValidateNickname();
            else doDelete();
            return true;
        }

        if (step == 1 && nicknameField.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }

        // 모달 바깥 배경 클릭 시 취소하고 랭킹 스크린으로 복귀
        if (!isInside(mouseX, mouseY, modalX, modalY, MODAL_W, MODAL_H)) {
            playUiClick();
            close();
            return true;
        }

        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (step == 1) {
            if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
                tryValidateNickname();
                return true;
            }
            if (nicknameField.keyPressed(keyCode, scanCode, modifiers)) {
                return true;
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char chr, int modifiers) {
        if (step == 1 && nicknameField.charTyped(chr, modifiers)) {
            return true;
        }
        return super.charTyped(chr, modifiers);
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