package com.example.rankinglog;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.attribute.EntityAttributeInstance;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.decoration.DisplayEntity;
import net.minecraft.text.Text;
import net.minecraft.text.TextColor;
import net.minecraft.util.Identifier;

// ★ NBT 추출을 위한 추가 import
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.nbt.NbtCompound;

import java.util.List;
import java.util.Set;

public final class BodyCaptureManager {

    private BodyCaptureManager() {}

    private static boolean active = false;
    private static boolean capturedThisRace = false;
    private static boolean gracePeriod = false;
    private static long graceStartMs = 0;

    private static final long BODY_SCAN_INTERVAL_MS = 100;
    private static long lastBodyScanMs = 0;

    private static String cachedKartBodyName = null;
    private static String cachedKartColor = null;
    private static String cachedTireName = null;

    // ★ 카트 아이템의 스탯(NBT)을 저장할 변수
    private static NbtCompound cachedKartItemNbt = null;

    private static String lastItemDisplayKey = null;
    private static String lastTireKey = null;

    private static long lastBodyLogMs = 0;
    private static String lastBodyLogValue = null;
    private static final long LOG_COOLDOWN_MS = 600;

    private static final Identifier KART_TIRE_ID = Identifier.of("minecraft", "kart-tire");

    public static String getCachedKartBodyNameOrUnknown() {
        if (cachedKartBodyName == null || cachedKartBodyName.trim().isEmpty()) return "UNKNOWN";
        return cachedKartBodyName;
    }

    public static String getCachedKartColorOrHex() {
        if (cachedKartColor == null || cachedKartColor.trim().isEmpty()) return "#FFFFFF";
        return cachedKartColor;
    }

    // ★ 캐싱된 NBT를 가져오는 getter
    public static NbtCompound getCachedKartItemNbt() {
        return cachedKartItemNbt;
    }

    public static void onLoadingDetected(String sourceTag) {
        if (capturedThisRace) capturedThisRace = false;
        if (active) return;

        active = true;
        gracePeriod = false;
        lastBodyScanMs = 0;

        cachedKartBodyName = null;
        cachedKartColor = null;
        cachedTireName = null;
        cachedKartItemNbt = null; // ★ NBT 초기화
        lastItemDisplayKey = null;
        lastTireKey = null;

        if (DebugLog.enabled()) {
            DebugLog.chat("§7[Body] 캡처 활성화 (" + sourceTag + ")");
        }
    }

    public static void onTitle3() {
        if (!active) return;

        if (cachedKartBodyName != null && !cachedKartBodyName.equals("UNKNOWN")) {
            finishCapture("title=3 (Found)");
        } else {
            if (!gracePeriod) {
                gracePeriod = true;
                graceStartMs = System.currentTimeMillis();
                if (DebugLog.enabled()) {
                    DebugLog.chat("§e[Body] '3' 감지! RootVehicle 탐색 시작... (최대 2초 연장)");
                }
            }
        }
    }

    private static void finishCapture(String reason) {
        active = false;
        gracePeriod = false;
        capturedThisRace = true;

        if (cachedKartBodyName == null) cachedKartBodyName = "UNKNOWN";
        if (cachedTireName == null) cachedTireName = "UNKNOWN";

        logBody("§a[Body] 캡처 종료(" + reason + ") : " + safeShow(cachedKartBodyName));
    }

    public static void onRaceFailed() {
        if (!active && capturedThisRace) return;
        finishCapture("완주 실패");
    }

    public static void tickScan() {
        if (!active) return;

        long now = System.currentTimeMillis();

        if (gracePeriod && (now - graceStartMs > 2000)) {
            finishCapture("Grace Period Timeout");
            return;
        }

        if (now - lastBodyScanMs < BODY_SCAN_INTERVAL_MS) return;
        lastBodyScanMs = now;

        readTireNameAndCacheLast();

        String displayName = readPassengerDataCarrier();
        if (displayName != null && !displayName.trim().isEmpty()) {
            cachedKartBodyName = displayName;
            if (gracePeriod) {
                finishCapture("Grace Period Match");
            }
        }
    }

    private static String readPassengerDataCarrier() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return null;

        // 1. 탑승 상태 감지 (RootVehicle == player면 탑승하지 않은 상태)
        Entity vehicle = client.player.getRootVehicle();
        if (vehicle == client.player) {
            return null;
        }

        try {
            // 2. 승객 리스트를 가져와 foreach로 순회
            List<Entity> passengers = vehicle.getPassengerList();
            for (Entity passenger : passengers) {
                if (passenger == client.player) continue;

                if (passenger instanceof DisplayEntity.ItemDisplayEntity display) {
                    Text customText = display.getCustomName();
                    String customName = (customText != null) ? customText.getString() : "";
                    Set<String> tags = display.getCommandTags();

                    String stackName = (display.getItemStack() != null) ? display.getItemStack().getName().getString() : "";

                    // mcrider-datacarrier 식별 조건
                    boolean isDataCarrier = "mcrider-datacarrier".equalsIgnoreCase(customName)
                            || tags.contains("mcrider-datacarrier")
                            || stackName.contains("mcrider-datacarrier");

                    if (isDataCarrier) {
                        String targetName = customName;
                        Text targetText = customText;

                        // 식별자가 이름이나 태그인 경우, 실제 카트 이름은 아이템 이름에서 가져옴
                        if ("mcrider-datacarrier".equalsIgnoreCase(customName) || tags.contains("mcrider-datacarrier")) {
                            if (display.getItemStack() != null && !display.getItemStack().isEmpty()) {
                                targetText = display.getItemStack().getName();
                                targetName = targetText.getString();

                                // ★ 아이템의 NBT (Custom Data) 스탯 추출 및 저장
                                NbtComponent customDataComp = display.getItemStack().get(DataComponentTypes.CUSTOM_DATA);
                                if (customDataComp != null) {
                                    cachedKartItemNbt = customDataComp.copyNbt();
                                } else {
                                    cachedKartItemNbt = null;
                                }
                            }
                        }

                        if (targetName == null || targetName.trim().isEmpty()) continue;

                        cachedKartColor = extractColorHex(targetText);
                        String key = display.getId() + "|" + targetName;
                        if (!key.equals(lastItemDisplayKey)) {
                            lastItemDisplayKey = key;
                            if (DebugLog.enabled()) {
                                DebugLog.chat("§b§l[Body] 매칭 성공: " + targetName);
                            }
                        }
                        return targetName;
                    }
                }
            }
        } catch (Throwable e) {
            if (DebugLog.enabled()) DebugLog.chat("§c[Error] 데이터 추출 실패: " + e.getMessage());
        }
        return null;
    }

    private static String extractColorHex(Text text) {
        if (text == null) return "#FFFFFF";
        TextColor color = text.getStyle().getColor();
        if (color != null) return String.format("#%06X", color.getRgb());
        for (Text s : text.getSiblings()) {
            TextColor sc = s.getStyle().getColor();
            if (sc != null && !s.getString().trim().isEmpty()) return String.format("#%06X", sc.getRgb());
        }
        return "#FFFFFF";
    }

    private static void readTireNameAndCacheLast() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;
        try {
            EntityAttributeInstance inst = client.player.getAttributeInstance(EntityAttributes.EXPLOSION_KNOCKBACK_RESISTANCE);
            if (inst == null) return;

            Double tireValue = null;
            for (var mod : inst.getModifiers()) {
                if (KART_TIRE_ID.equals(mod.id())) {
                    tireValue = mod.value();
                    break;
                }
            }
            if (tireValue == null) return;

            String tireName = TireUtil.nameFromValue(tireValue);
            String key = KART_TIRE_ID + "|" + tireName;
            if (key.equals(lastTireKey)) return;

            lastTireKey = key;
            cachedTireName = tireName;
        } catch (Throwable ignored) {}
    }

    private static void logBody(String msg) {
        if (!DebugLog.enabled()) return;
        long now = System.currentTimeMillis();
        if (msg.equals(lastBodyLogValue) && (now - lastBodyLogMs) < LOG_COOLDOWN_MS) return;
        lastBodyLogValue = msg;
        lastBodyLogMs = now;
        DebugLog.chat(msg);
    }

    private static String safeShow(String v) {
        if (v == null) return "null";
        String s = v.replace("\n", " ").replaceAll("\\s+", " ").trim();
        return s.isEmpty() ? "(blank)" : s;
    }
}