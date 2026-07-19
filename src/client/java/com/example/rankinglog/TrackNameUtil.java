package com.example.rankinglog;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.decoration.DisplayEntity;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class TrackNameUtil {

    public static final Box LOBBY_BOX = new Box(
            -21, 3, 155,
            -15, -1, 152
    );

    // ★ InGameHudMixin과 동일한 ATTACK_BOX 추가
    public static final Box ATTACK_BOX = new Box(
            0, 0, 0,
            0, 0, 0
    );

    /** 플레이어와 가장 가까운 박스 안의 TextDisplay를 Y 내림차순 정렬해서 2번(index=2)을 트랙 텍스트로 사용 */
    public static String readTrackNameFromLobbyBox() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null || client.player == null) return null;

        // 플레이어의 현재 위치
        Vec3d p = client.player.getPos();

        // 박스 배열 및 가장 가까운 박스 계산
        Box[] boxes = { LOBBY_BOX, ATTACK_BOX };
        Box closestBox = boxes[0];
        double minDst = closestBox.getCenter().squaredDistanceTo(p);

        for (int i = 1; i < boxes.length; i++) {
            double dst = boxes[i].getCenter().squaredDistanceTo(p);
            if (dst < minDst) {
                minDst = dst;
                closestBox = boxes[i];
            }
        }

        List<DisplayEntity.TextDisplayEntity> list = new ArrayList<>();

        for (Entity e : client.world.getEntities()) {
            if (e instanceof DisplayEntity.TextDisplayEntity td) {
                // 가장 가까운 박스 내부에 있는 텍스트만 추출
                if (closestBox.contains(td.getPos())) {
                    list.add(td);
                }
            }
        }

        list.sort(
                Comparator.comparingDouble(Entity::getY).reversed()
                        .thenComparingDouble(Entity::getX)
                        .thenComparingDouble(Entity::getZ)
        );

        if (list.size() <= 2) return null;

        String raw = list.get(2).getText().getString();
        return raw.replace("\n", " ").replaceAll("\\s+", " ").trim();
    }
}