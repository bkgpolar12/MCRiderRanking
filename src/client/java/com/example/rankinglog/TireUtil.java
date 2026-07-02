package com.example.rankinglog;

public final class TireUtil {
    private TireUtil() {}

    public static final String UNKNOWN_NAME = "UNKNOWN";
    public static final String RACING_NAME = "레이싱 타이어";
    public static final String SPIKE_NAME  = "스파이크 타이어";

    public static final String RACING_ICON = "[𐃏]";
    public static final String SPIKE_ICON  = "[✸]";

    /** value(0/1/...) -> 타이어 이름 */
    public static String nameFromValue(double value) {
        int v = (int) Math.round(value);
        return switch (v) {
            case 0 -> RACING_NAME;
            case 1 -> SPIKE_NAME;
            default -> UNKNOWN_NAME;
        };
    }

    /** 타이어 이름 -> 표시용 기호(대괄호 포함). UNKNOWN이면 "" */
    public static String iconFromName(String tireName) {
        if (tireName == null) return "";
        String s = tireName.trim();
        if (s.equalsIgnoreCase(RACING_NAME)) return RACING_ICON;
        if (s.equalsIgnoreCase(SPIKE_NAME))  return SPIKE_ICON;
        return "";
    }

    /** bodyName + " " + [icon] 형태로 합치기 (아이콘 없으면 body만) */
    public static String composeBodyLabel(String bodyName, String tireName) {
        String body = (bodyName == null || bodyName.isBlank()) ? "UNKNOWN" : bodyName;
        String icon = iconFromName(tireName);
        if (icon.isEmpty()) return body;
        return body + " " + icon;
    }

    /** tooltip용 타이어 이름 안전 처리 */
    public static String tooltipName(String tireName) {
        if (tireName == null || tireName.isBlank() || tireName.equalsIgnoreCase("UNKNOWN")) return "타이어: 알 수 없음";
        return "타이어: " + tireName.trim();
    }
}