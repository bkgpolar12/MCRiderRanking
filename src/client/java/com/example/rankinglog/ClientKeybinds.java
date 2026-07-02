package com.example.rankinglog;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.entity.Entity;
import net.minecraft.entity.decoration.DisplayEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.Box;

// ★ 추가된 import 목록 (아이템 컴포넌트, NBT, 데이터팩 파일 로드용)
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.resource.ResourceManager;
import net.minecraft.resource.Resource;
import net.minecraft.util.Identifier;

import org.lwjgl.glfw.GLFW;

import java.io.BufferedReader;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ClientKeybinds {

    private static KeyBinding OPEN_RANKING;
    private static KeyBinding DEBUG_PASSENGER_KEY;

    private static final Box LOBBY_BOX = new Box(
            -21, 3, 155,
            -15, -1, 152
    );

    // ★ 허용된 카트 파일명 패턴 (classic, common, rare, legend, unique, special)
    // (?:-\\d+)? 를 추가하여 -숫자 부분이 없어도 정상적으로 감지하도록 수정했습니다.
    private static final Pattern ALLOWED_KART_FILE_PATTERN = Pattern.compile("get(classic|common|rare|legend|unique|special)kart(?:-\\d+)?\\.mcfunction");

    public static void init() {
        ModConfig.load();

        OPEN_RANKING = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.rankinglog.open_ranking",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_F7,
                "category.rankinglog"
        ));

        // ★ 주석 해제 및 'U' 키 활성화 완료
//        DEBUG_PASSENGER_KEY = KeyBindingHelper.registerKeyBinding(new KeyBinding(
//                "key.rankinglog.debug_passenger",
//                InputUtil.Type.KEYSYM,
//                GLFW.GLFW_KEY_U,
//                "category.rankinglog"
//        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (OPEN_RANKING.wasPressed()) {
                openMainMenu();
            }

            // ★ U 키 입력 시 아이템/데이터팩 스탯 비교 로직 실행
            // DEBUG_PASSENGER_KEY가 null(주석 처리됨)이면 건너뜀
            if (DEBUG_PASSENGER_KEY != null) {
                while (DEBUG_PASSENGER_KEY.wasPressed()) {
                    compareKartStatsWithDatapack();
                }
            }
        });
    }

    /**
     * 메인 핸드에 들고 있는 아이템의 이름/NBT와 싱글플레이 데이터팩의 카트 스텟을 비교합니다.
     */
    private static void compareKartStatsWithDatapack() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.world == null) return;

        // 1. 싱글플레이 환경인지 (데이터팩 파일 직접 접근 가능한지) 체크
        if (client.getServer() == null) {
            client.player.sendMessage(Text.literal("§c[에러] 멀티플레이 서버에서는 데이터팩 파일에 직접 접근할 수 없습니다."), false);
            return;
        }

        // 2. 메인 핸드에 든 아이템 가져오기
        var stack = client.player.getMainHandStack();
        if (stack == null || stack.isEmpty()) {
            client.player.sendMessage(Text.literal("§c[에러] 메인 핸드에 비교할 카트 아이템을 들어주세요."), false);
            return;
        }

        // 아이템의 순수 이름 추출 (색상 코드 제거)
        String itemName = stack.getName().getString();
        client.player.sendMessage(Text.literal("§e[검사 대상] §f" + itemName + " 아이템 분석 중... (데이터팩 탐색 시작)"), false);

        // 3. 아이템의 1.21+ Custom Data (NBT) 가져오기
        NbtComponent customDataComp = stack.get(DataComponentTypes.CUSTOM_DATA);
        if (customDataComp == null) {
            client.player.sendMessage(Text.literal("§c[에러] 이 아이템에는 스텟을 담고 있는 커스텀 데이터(NBT)가 존재하지 않습니다."), false);
            return;
        }
        NbtCompound itemNbt = customDataComp.copyNbt();

        // 4. 데이터팩에서 getkartitem 폴더 하위의 '모든' mcfunction 파일 검색 및 로드
        ResourceManager rm = client.getServer().getResourceManager();

        // findResources를 사용하여 function/getkartitem/getofficialkart 하위에 있는 파일 중 조건에 맞는 파일만 가져옵니다.
        Map<Identifier, Resource> resources = rm.findResources("function/getkartitem/getofficialkart", id -> {
            if (!id.getNamespace().equals("kartmodel")) return false;

            String path = id.getPath();
            if (!path.endsWith(".mcfunction")) return false;

            // 파일명만 추출하여 우리가 허용한 카트 등급인지 검사
            String fileName = path.substring(path.lastIndexOf('/') + 1);
            return ALLOWED_KART_FILE_PATTERN.matcher(fileName).matches();
        });

        if (resources.isEmpty()) {
            client.player.sendMessage(Text.literal("§c[에러] 데이터팩에서 허용된 등급의 카트 함수 파일들을 찾을 수 없습니다."), false);
            return;
        }

        String targetLine = null;
        String foundFileName = "";

        // 모든 파일들을 순회하면서 해당하는 이름이 있는지 싹 다 뒤집니다.
        searchLoop:
        for (Map.Entry<Identifier, Resource> entry : resources.entrySet()) {
            try (BufferedReader reader = entry.getValue().getReader()) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    // 해당 아이템의 이름을 가진 줄을 찾음
                    if (line.startsWith("function kartmain:makekart") && line.contains("translate:\"" + itemName + "\"")) {
                        targetLine = line;
                        foundFileName = entry.getKey().getPath(); // 어느 파일에서 찾았는지 기록
                        break searchLoop; // 찾으면 전체 파일 탐색 즉시 종료 (최적화)
                    }
                }
            } catch (Exception ignored) {
                // 읽기 실패 시 무시하고 다음 파일 탐색 진행
            }
        }

        if (targetLine == null) {
            client.player.sendMessage(Text.literal("§c[결과] 허용된 등급의 데이터팩을 모두 뒤졌으나 이름이 '" + itemName + "'인 카트를 찾을 수 없습니다."), false);
            return;
        }

        client.player.sendMessage(Text.literal("§e[발견] §f" + foundFileName + " 에서 카트 감지 완료!"), false);
        client.player.sendMessage(Text.literal("§a[결과] 아이템 NBT vs 데이터팩 스탯 비교"), false);

        // 5. 스텟 비교 진행
        String[] statsToCompare = {
                "speed", "accel", "boost", "corner", "drift",
                "gauge", "boosttime", "maxboostcount", "defense", "draft"
        };
        boolean allMatch = true;

        for (String stat : statsToCompare) {
            // 데이터팩 문자열에서 숫자 추출
            int dpVal = extractStatFromLine(targetLine, stat);
            if (dpVal == -1) continue; // 데이터팩에 해당 스탯이 적혀있지 않으면 스킵

            // 아이템 커스텀 데이터(NBT)에서 안전하게 값 파싱
            int itemVal = -1;
            if (itemNbt.contains(stat)) {
                net.minecraft.nbt.NbtElement elem = itemNbt.get(stat);
                if (elem instanceof net.minecraft.nbt.AbstractNbtNumber number) {
                    itemVal = number.intValue();
                } else if (elem != null) {
                    try {
                        itemVal = Integer.parseInt(String.valueOf(elem.asString()));
                    } catch (NumberFormatException ignored) {}
                }
            }

            if (itemVal == -1) {
                client.player.sendMessage(Text.literal("  §c✘ " + stat + " 값이 아이템에 존재하지 않습니다! (데이터팩: " + dpVal + ")"), false);
                allMatch = false;
                continue;
            }

            if (dpVal == itemVal) {
                client.player.sendMessage(Text.literal("  §a✔ " + stat + ": " + itemVal + " (일치)"), false);
            } else {
                client.player.sendMessage(Text.literal("  §c✘ " + stat + " 불일치! (아이템: " + itemVal + " / 데이터팩: " + dpVal + ")"), false);
                allMatch = false;
            }
        }

        if (allMatch) {
            client.player.sendMessage(Text.literal("§b§l▶ 모든 스탯이 완벽하게 일치합니다!"), false);
        } else {
            client.player.sendMessage(Text.literal("§4§l▶ 일치하지 않는 스탯이 존재합니다."), false);
        }
    }

    /**
     * 데이터팩 구문에서 정규식을 이용해 특정 스탯의 숫자 값을 추출합니다.
     */
    private static int extractStatFromLine(String line, String statName) {
        Matcher m = Pattern.compile(statName + ":(\\d+)").matcher(line);
        if (m.find()) {
            return Integer.parseInt(m.group(1));
        }
        return -1;
    }

    private static void debugRootVehiclePassengers() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;

        client.player.sendMessage(Text.literal("§6§l[Debug] RootVehicle 승객 분석"), false);

        // 1. 탑승 여부 체크
        Entity vehicle = client.player.getRootVehicle();
        if (vehicle == client.player) {
            client.player.sendMessage(Text.literal("§c[결과] 현재 탑승 중인 차량이 없습니다."), false);
            return;
        }

        // 2. 승객 리스트 할당 및 foreach 루프
        List<Entity> passengers = vehicle.getPassengerList();
        client.player.sendMessage(Text.literal("§f차량: §a" + vehicle.getType().getName().getString() + " §7| 승객: §e" + passengers.size()), false);

        for (Entity p : passengers) {
            boolean isMe = (p == client.player);
            String className = p.getClass().getSimpleName();
            Text customNameText = p.getCustomName();
            String customName = (customNameText != null) ? customNameText.getString() : "§8(null)§f";
            Set<String> tags = p.getCommandTags();

            client.player.sendMessage(Text.literal("§e- " + (isMe ? "§d(본인) " : "") + "§b" + className), false);
            client.player.sendMessage(Text.literal("    §f이름: " + customName + " §7| 태그: " + tags), false);

            if (p instanceof DisplayEntity.ItemDisplayEntity display) {
                var stack = display.getItemStack();
                String stackName = (stack != null && !stack.isEmpty()) ? stack.getName().getString() : "§8(없음)§f";
                client.player.sendMessage(Text.literal("    §3└ [Item] §f" + stackName), false);

                if ("mcrider-datacarrier".equalsIgnoreCase(customName) || tags.contains("mcrider-datacarrier") || stackName.contains("mcrider-datacarrier")) {
                    client.player.sendMessage(Text.literal("    §a§l▶ DataCarrier 감지 성공!"), false);
                }
            }
        }
    }

    private static void openMainMenu() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;

        client.send(() -> {
            // ★ showMainScreen이 false일 경우 바로 RankingScreen을 엽니다.
            if (!ModConfig.get().showMainScreen) {
                String track = TrackNameUtil.readTrackNameFromLobbyBox();
                String finalTrack = (track == null || track.isBlank()) ? ModConfig.get().defaultTrack : track.trim();
                client.setScreen(new RankingScreen(finalTrack));
            } else {
                // showMainScreen이 true일 경우 MainMenuScreen을 엽니다.
                client.setScreen(new MainMenuScreen());
            }
        });

        RankingScreen.ApiCache.fetchAllAsync(false, p -> {}, err -> {});
    }
}