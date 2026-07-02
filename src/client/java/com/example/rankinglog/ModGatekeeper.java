package com.example.rankinglog;

import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.text.Text;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.Set;

import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.LogManager;

public final class ModGatekeeper {

    // ===== 설정 =====
    private static final int CHAT_BUFFER_MAX = 200;
    private static final long MARKER_TTL_MS = 30_000;
    private static final String MARKER = "----적용된 모드----";

    private static final Set<String> ALLOWED_MODES = Set.of(
            "팀전",
            "무한 부스터 모드",
            "톡톡이 모드",
            "갓겜 모드",
            "벽 충돌 페널티"
    );

    private static final Set<String> IGNORE_MODES = Set.of(
            "고스트 모드",
            "드래프트 끄기",
            "견인 가속 끄기"
    );

    private static final Deque<Line> chatBuf = new ArrayDeque<>(CHAT_BUFFER_MAX);

    private static volatile int lastMarkerIndex = -1;
    private static volatile long lastMarkerAtMs = 0;

    // ===== 수집 상태 =====
    private static volatile boolean collecting = false;
    private static volatile long collectingStartMs = 0; // 스턱 감지용

    private static final LinkedHashSet<String> detectedAllowed = new LinkedHashSet<>();
    // ★ 다인 타임어택 판별용으로 무시된 모드들을 임시 기록하는 셋
    private static final LinkedHashSet<String> detectedGhostModes = new LinkedHashSet<>();

    private static final LinkedHashSet<String> debugObserved = new LinkedHashSet<>();

    // freeze 결과
    private static volatile boolean frozen = false;
    private static volatile String frozenModesCsv = "없음";
    private static volatile boolean frozenAllowed = true;

    private static final Logger LOGGER = LogManager.getLogger("RankingLog/ModGatekeeper");
    private static long lastMarkerLogMs = 0;

    private ModGatekeeper() {}

    public static void init() {
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (message == null) return;
            String raw = message.getString();
            if (raw == null) return;

            if (looksLikePlayerChat(raw)) return;
            onIncomingText(message);
        });
    }

    private static boolean looksLikePlayerChat(String raw) {
        String s = normalize(raw);

        if (s.startsWith("<") && s.contains("> ")) return true;

        int idx = s.indexOf(": ");
        if (idx >= 1 && idx <= 20) {
            String left = s.substring(0, idx).trim();
            if (left.matches("[A-Za-z0-9_]{2,16}")) return true;
        }
        return false;
    }

    /** 새 레이스용 초기화 */
    public static void resetForNewRace() {
        collecting = false;
        collectingStartMs = 0;

        detectedAllowed.clear();
        detectedGhostModes.clear(); // ★ 셋 초기화
        debugObserved.clear();

        frozen = false;
        frozenModesCsv = "없음";
        frozenAllowed = true;
    }

    private static void onIncomingText(Text msg) {
        if (msg == null) return;
        String raw = msg.getString();
        if (raw == null) return;

        String[] lines = raw.split("\\R");
        long now = System.currentTimeMillis();

        for (String line : lines) {
            String s = normalize(line);
            if (s.isEmpty()) continue;

            pushToBuffer(new Line(now, s));

            if (isModeMarkerLine(s)) {
                lastMarkerIndex = chatBuf.size() - 1;
                lastMarkerAtMs = now;

                if (DebugLog.enabled()) {
                    if (now - lastMarkerLogMs > 1000) {
                        lastMarkerLogMs = now;
                        LOGGER.info("[ModeDebug] marker seen (idx={}) text={}", lastMarkerIndex, s);
                    }
                }
            }

            if (collecting) {
                scanLineForModes(s);
            }
        }
    }

    private static boolean isModeMarkerLine(String s) {
        if (s == null) return false;
        String x = normalize(s);
        return x.contains("적용된 모드");
    }

    private static void pushToBuffer(Line line) {
        chatBuf.addLast(line);
        while (chatBuf.size() > CHAT_BUFFER_MAX) {
            chatBuf.removeFirst();
            if (lastMarkerIndex >= 0) lastMarkerIndex--;
            if (lastMarkerIndex < 0) lastMarkerAtMs = 0;
        }
    }

    /** 시작 조건 */
    public static void onLoadingTitle() {
        long now = System.currentTimeMillis();

        if (collecting) {
            if (collectingStartMs > 0 && (now - collectingStartMs) < 2000) {
                return;
            }
            resetForNewRace();
        }

        detectedAllowed.clear();
        detectedGhostModes.clear(); // ★ 셋 초기화
        debugObserved.clear();
        frozen = false;
        frozenModesCsv = "없음";
        frozenAllowed = true;

        boolean markerValid = (lastMarkerIndex >= 0) && (now - lastMarkerAtMs <= MARKER_TTL_MS);

        if (DebugLog.enabled()) {
            DebugLog.chat("§a[ModeDebug] onLoadingTitle() markerValid=" + markerValid + " idx=" + lastMarkerIndex);
        }

        if (markerValid) {
            rescanFromMarker();
        }

        collecting = true;
        collectingStartMs = now;
    }

    private static void rescanFromMarker() {
        int start = Math.max(0, lastMarkerIndex);
        int i = 0;

        for (Line ln : chatBuf) {
            if (i >= start) {
                scanLineForModes(ln.text);
            }
            i++;
        }

        if (DebugLog.enabled()) {
            DebugLog.chat("§7[ModeDebug] rescanFromMarker done. detected=" + detectedAllowed + ", ghosts=" + detectedGhostModes);
        }
    }

    /** 종료 조건 */
    public static void freezeNow() {
        if (frozen) return;

        collecting = false;
        collectingStartMs = 0;

        frozenModesCsv = detectedAllowed.isEmpty()
                ? "없음"
                : String.join(", ", detectedAllowed);

        frozenAllowed = true;
        frozen = true;

        if (DebugLog.enabled()) {
            DebugLog.chat("§7[Mode] frozenCsv=" + frozenModesCsv);
        }
    }

    public static String getModesCsv() {
        return (frozenModesCsv == null || frozenModesCsv.isBlank()) ? "없음" : frozenModesCsv;
    }

    public static boolean isAllowed() {
        return frozenAllowed;
    }

    public static boolean isCollecting() {
        return collecting;
    }

    // ★ 다인 타임어택 조건 만족 여부 확인 (3가지 모드가 모두 적용되어야 함)
    public static boolean isGhostTimeAttackEnvironment() {
        return detectedGhostModes.contains("고스트 모드") &&
                detectedGhostModes.contains("드래프트 끄기") &&
                detectedGhostModes.contains("견인 가속 끄기");
    }

    private static void scanLineForModes(String s) {
        if (s == null) return;
        if ("없음".equals(s)) return;

        // ★ 제외할 모드 목록 (단, 다인 타임어택 판별을 위해 수집은 별도로 함)
        for (String ign : IGNORE_MODES) {
            if (s.contains(ign)) {
                detectedGhostModes.add(ign);
            }
        }

        // ★ DB에 업로드 될 실제 모드 목록 수집
        for (String mode : ALLOWED_MODES) {
            if (s.contains(mode)) {
                detectedAllowed.add(mode);
            }
        }
    }

    private static String normalize(String s) {
        if (s == null) return "";
        return s.replaceAll("\\s+", " ").trim();
    }

    private record Line(long atMs, String text) {}
}