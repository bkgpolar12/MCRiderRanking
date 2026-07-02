package com.example.rankinglog;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class ModConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String FILE_NAME = "mcrider_ranking_config.json";
    private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);

    // 설정 값들
    public boolean autoSubmitEnabled = true;
    public boolean debugLogEnabled = false;
    public int cacheTtlSeconds = 60;
    public int bodyScanMode = 0;
    public int backgroundAlpha = 110;

    // ★ 토스트 알림 표시 여부 및 위치
    public boolean showToasts = true;
    public int toastPosition = 0; // 0: 우측 상단, 1: 좌측 상단, 2: 우측 하단, 3: 좌측 하단

    public String defaultTrack = "[α] 빌리지 고가의 질주";

    // 필터 기본값들
    public String defaultEngine = "ALL";
    public String defaultTire = "ALL";
    public List<String> defaultModes = new ArrayList<>();

    // UI 테마 설정 (false = NEW 사이드바 모드, true = 레거시 중앙 정렬 모드)
    public boolean useLegacyUi = false;

    // 메인 스크린 표시 여부 (false = 메인 스크린 건너뜀, true = 메인 스크린 표시)
    public boolean showMainScreen = false;
    // useLegacyUi=true 전환 직전에 showMainScreen 값을 보존 (복구용)
    public boolean savedShowMainScreen = false;

    public boolean hasSeenTutorial = false;

    public long getCacheTtlMs() {
        return (long) Math.clamp(cacheTtlSeconds, 10, 600) * 1000L;
    }

    public int getBgColor() {
        int alpha = Math.clamp(backgroundAlpha, 0, 255);
        return (alpha << 24);
    }

    private static ModConfig INSTANCE = new ModConfig();
    public static ModConfig get() { return INSTANCE; }

    public static void load() {
        if (!Files.exists(PATH)) {
            save();
            return;
        }
        try {
            String json = Files.readString(PATH, StandardCharsets.UTF_8);
            ModConfig loaded = GSON.fromJson(json, ModConfig.class);
            if (loaded != null) {
                if (loaded.defaultTrack == null || loaded.defaultTrack.isBlank()) loaded.defaultTrack = "[α] 빌리지 고가의 질주";
                if (loaded.defaultEngine == null || loaded.defaultEngine.isBlank()) loaded.defaultEngine = "ALL";
                if (loaded.defaultTire == null || loaded.defaultTire.isBlank()) loaded.defaultTire = "ALL";
                if (loaded.defaultModes == null) loaded.defaultModes = new ArrayList<>();

                INSTANCE = loaded;
            }
        } catch (IOException | JsonSyntaxException e) {
            INSTANCE = new ModConfig();
            save();
        }
    }

    public static void save() {
        try {
            String json = GSON.toJson(INSTANCE);
            Files.writeString(PATH, json, StandardCharsets.UTF_8);
        } catch (IOException ignored) {}
    }
}