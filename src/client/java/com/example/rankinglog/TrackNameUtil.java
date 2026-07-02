package com.example.rankinglog;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.decoration.DisplayEntity;
import net.minecraft.util.math.Box;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class TrackNameUtil {

    // 네가 쓰던 LOBBY_BOX 그대로
    public static final Box LOBBY_BOX = new Box(
            -21, 3, 155,
            -15, -1, 152
    );

    /** LOBBY_BOX 안 TextDisplay를 Y 내림차순 정렬해서 2번(index=2)을 트랙 텍스트로 사용 */
    public static String readTrackNameFromLobbyBox() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null) return null;

        List<DisplayEntity.TextDisplayEntity> list = new ArrayList<>();

        for (Entity e : client.world.getEntities()) {
            if (e instanceof DisplayEntity.TextDisplayEntity td) {
                if (LOBBY_BOX.contains(td.getPos())) {
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
