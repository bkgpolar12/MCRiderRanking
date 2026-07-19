package com.example.mixin.client;

import com.example.rankinglog.AutoSubmitter;
import net.minecraft.client.gui.hud.ChatHud;
import net.minecraft.client.gui.hud.MessageIndicator;
import net.minecraft.network.message.MessageSignatureData;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Mixin(ChatHud.class)
public class ChatHudMixin {

    // 채팅 메시지가 추가될 때 가로채기
    @Inject(method = "addMessage(Lnet/minecraft/text/Text;Lnet/minecraft/network/message/MessageSignatureData;Lnet/minecraft/client/gui/hud/MessageIndicator;)V", at = @At("HEAD"))
    private void onAddMessage(Text message, MessageSignatureData signature, MessageIndicator indicator, CallbackInfo ci) {

        // signature가 null이면 플레이어가 직접 친 채팅이 아닌 '시스템 메시지(서버 플러그인/커맨드 블록 등)'입니다.
        // 그리고 레이싱(무장) 상태일 때만 작동합니다.
        if (signature == null && AutoSubmitter.multiPlayerSubmitArmed) {
            String text = message.getString().trim();

            // 정규식: "숫자/숫자 | 00:00.000" 형태 감지 (띄어쓰기 유연하게 허용)
            Matcher m = Pattern.compile("(\\d+)\\s*/\\s*\\d+\\s*\\|\\s*(\\d{2}:\\d{2}\\.\\d{3})").matcher(text);

            if (m.find()) {
                String lapNum = m.group(1);   // "1"
                String lapTime = m.group(2);  // "00:14.689"

                // "lap1:00:14.689" 형태로 리스트에 추가
                AutoSubmitter.lapTimes.add("lap" + lapNum + ":" + lapTime);
            }
        }
    }
}