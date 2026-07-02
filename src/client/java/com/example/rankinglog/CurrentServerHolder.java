package com.example.rankinglog;

/**
 * 현재 플레이어가 접속해 있는 서버의 "ip:port" 문자열을 보관하는 싱글턴 홀더.
 *
 * - InGameHudMixin이 매 렌더 틱마다 갱신한다.
 * - 싱글플레이 중이면 SINGLEPLAY 상수를 반환한다.
 * - 서버 주소를 알 수 없으면 DEFAULT_ADDRESS를 반환한다.
 * - get_all_rankings 쿼리에 보낼 때는 getForQuery()를 사용한다
 * (싱글플레이·불명 → DEFAULT_ADDRESS로 폴백).
 */
public final class CurrentServerHolder {

    /** 싱글플레이 상태임을 나타내는 특수 값 */
    public static final String SINGLEPLAY = "__singleplay__";

    /** 서버 주소를 알 수 없을 때 / 쿼리 폴백용 기본값 */
    public static final String DEFAULT_ADDRESS = "193.122.114.163:60819";

    private CurrentServerHolder() {}

    private static volatile String currentAddress = DEFAULT_ADDRESS;

    /** 모달에서 랭킹 조회를 위해 임시로 선택한 서버 주소 (null이면 현재 접속 서버 사용) */
    private static volatile String queryAddressOverride = null;

    /**
     * 현재 접속 상태를 반환한다.
     * - 싱글플레이 → SINGLEPLAY ("__singleplay__")
     * - 멀티플레이 → "ip:port"
     * - 미접속/불명 → DEFAULT_ADDRESS
     */
    public static String get() {
        return currentAddress;
    }

    /**
     * SQL get_all_rankings 에 p_server_address로 넘길 값.
     * 조회 오버라이드가 지정되어 있다면 해당 값을 우선 반환한다.
     */
    public static String getForQuery() {
        if (queryAddressOverride != null) {
            return queryAddressOverride;
        }
        String addr = currentAddress;
        if (addr == null || addr.isBlank() || addr.equals(SINGLEPLAY)) {
            return "__singleplay__";
        }
        return addr;
    }

    /**
     * InGameHudMixin 전용 — 매 틱에 호출해 현재 서버 주소를 갱신한다.
     *
     * @param address "ip:port", SINGLEPLAY, 또는 null/blank(→ DEFAULT_ADDRESS)
     */
    public static void set(String address) {
        String newAddress = (address == null || address.isBlank()) ? "__singleplay__" : address;

        // 실제 접속 물리 서버가 바뀌면(예: 다른 서버로 로그인), 유저가 선택해두었던 랭킹 뷰 오버라이드를 초기화
        if (!newAddress.equals(currentAddress)) {
            queryAddressOverride = null;
        }
        currentAddress = newAddress;
    }

    /**
     * 서버 변경 모달에서 다른 서버의 랭킹을 보고 싶을 때 호출
     */
    public static void setQueryOverride(String address) {
        queryAddressOverride = address;
    }

    /** 오버라이드 해제 (필요 시) */
    public static void clearQueryOverride() {
        queryAddressOverride = null;
    }
}