package com.example.rankinglog;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;

public class PlayerRecordsModalScreen extends Screen {

    private final Screen parent;
    private final RankingScreen.GroupedEntry groupedEntry;
    private final String trackName;
    private final String selectedProfileDesc;

    private int scrollAmount = 0;
    private static final int ROW_H = 16;
    private int tableTop, tableH, tableX, tableW;
    private RankingScreen.Entry selectedDetailEntry = null;

    private boolean kartSpecExpanded = false;
    private boolean lapsExpanded = false;

    private int specBtnScreenX, specBtnScreenY, specBtnScreenW, specBtnScreenH;
    private int lapsBtnScreenX, lapsBtnScreenY, lapsBtnScreenW, lapsBtnScreenH;

    public PlayerRecordsModalScreen(Screen parent, RankingScreen.GroupedEntry groupedEntry, String trackName, String selectedProfileDesc) {
        super(Text.literal(groupedEntry.entries.get(0).player() + " 님의 기록"));
        this.parent = parent;
        this.groupedEntry = groupedEntry;
        this.trackName = trackName;
        this.selectedProfileDesc = selectedProfileDesc;
    }

    private void playUiClick() {
        if (this.client != null) this.client.getSoundManager().play(PositionedSoundInstance.master(SoundEvents.UI_BUTTON_CLICK, 1.0f));
    }

    private boolean isInside(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx <= x + w && my >= y && my <= y + h;
    }

    private void drawRectBorder(DrawContext context, int x, int y, int w, int h, int color) {
        context.fill(x, y, x + w, y + 1, color); context.fill(x, y + h - 1, x + w, y + h, color);
        context.fill(x, y, x + 1, y + h, color); context.fill(x + w - 1, y, x + w, y + h, color);
    }

    private String trimWithEllipsis(String text, int maxWidth) {
        if (maxWidth <= 0) return "";
        if (this.textRenderer.getWidth(text) <= maxWidth) return text;
        int ellipsisWidth = this.textRenderer.getWidth("...");
        if (maxWidth <= ellipsisWidth) return "";
        return this.textRenderer.trimToWidth(Text.literal(text), maxWidth - ellipsisWidth).getString() + "...";
    }

    private int parseHex(String hex, int fallback) {
        if (hex == null || hex.isEmpty()) return fallback;
        try { if (hex.startsWith("#")) hex = hex.substring(1); return 0xFF000000 | Integer.parseInt(hex, 16); } catch (Exception e) { return fallback; }
    }

    private static String normalizeEngine(String engineName) {
        if (engineName == null) return "UNKNOWN"; String s = engineName.trim();
        if (s.startsWith("[") && s.endsWith("]") && s.length() >= 3) s = s.substring(1, s.length() - 1).trim();
        s = s.replace("엔진", "").replace("ENGINE", "").replace("engine", "").trim();
        return s.isBlank() ? "UNKNOWN" : s.toUpperCase();
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        int extraH = 0;
        if (selectedDetailEntry != null) {
            boolean isSingle = "__singleplay__".equals(selectedDetailEntry.serverAddress());
            extraH += 16 * 3;

            boolean showTime = tableW > 250;
            boolean showBody = tableW > 320;
            boolean showEngine = tableW > 380;

            int hiddenCount = 0;
            if (!showTime) hiddenCount++;
            if (!showBody) hiddenCount++;
            if (!showEngine) hiddenCount++;
            if (hiddenCount > 0) extraH += hiddenCount * 16 + 5;

            if (isSingle && kartSpecExpanded) {
                String specRaw = selectedDetailEntry.kartSpecDebug();
                if (specRaw != null && !specRaw.isEmpty() && !specRaw.equals("없음")) {
                    int lines = 1;
                    if (specRaw.contains("speed")) lines++;
                    if (specRaw.contains("accel") || specRaw.contains("boost:")) lines++;
                    if (specRaw.contains("corner") || specRaw.contains("drift")) lines++;
                    if (specRaw.contains("gauge") || specRaw.contains("boosttime") || specRaw.contains("maxboostcount")) lines++;
                    if (specRaw.contains("defense")) lines++;
                    if (specRaw.contains("draft")) lines++;
                    extraH += lines * 16 + 10;
                } else {
                    extraH += 16 + 10;
                }
            }

            if (lapsExpanded) {
                String lapRaw = selectedDetailEntry.lapData();
                if (lapRaw != null && !lapRaw.isEmpty() && !lapRaw.equals("없음")) {
                    int lapCount = lapRaw.split(",").length;
                    extraH += lapCount * 16 + 10;
                } else {
                    extraH += 16 + 10;
                }
            }
            extraH += 26;
        }

        int adjustedCapacityRows = Math.max(1, (tableH - 24 - extraH) / ROW_H) + 1;
        int maxScroll = Math.max(0, groupedEntry.entries.size() - adjustedCapacityRows + 1);

        scrollAmount = Math.max(0, Math.min(scrollAmount - (int)Math.signum(verticalAmount) * 3, maxScroll));
        return true;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        int cx = width / 2;
        int cy = height / 2;
        int modalW = Math.min(width - 40, 500);
        int modalH = Math.min(height - 40, 300);
        int modalX = cx - modalW / 2;
        int modalY = cy - modalH / 2;

        int closeW = 40;
        int closeX = modalX + modalW - 15 - closeW;
        int closeY = modalY + modalH - 26;

        if (isInside(mouseX, mouseY, closeX, closeY, closeW, 20)) {
            playUiClick();
            close();
            return true;
        }

        if (mouseX >= tableX && mouseX <= tableX + tableW && mouseY >= tableTop + 24 && mouseY <= tableTop + tableH) {
            if (selectedDetailEntry != null && specBtnScreenW > 0) {
                if (isInside(mouseX, mouseY, specBtnScreenX, specBtnScreenY, specBtnScreenW, specBtnScreenH)) {
                    playUiClick();
                    kartSpecExpanded = !kartSpecExpanded;
                    return true;
                }
            }

            if (selectedDetailEntry != null && lapsBtnScreenW > 0) {
                if (isInside(mouseX, mouseY, lapsBtnScreenX, lapsBtnScreenY, lapsBtnScreenW, lapsBtnScreenH)) {
                    playUiClick();
                    lapsExpanded = !lapsExpanded;
                    return true;
                }
            }

            int currentY = tableTop + 24;
            int start = scrollAmount;
            int end = Math.min(start + Math.max(1, (tableH - 24) / ROW_H) + 3, groupedEntry.entries.size());

            for (int i = start; i < end; i++) {
                RankingScreen.Entry e = groupedEntry.entries.get(i);
                int itemH = ROW_H;

                if (e == selectedDetailEntry) {
                    boolean isSingle = "__singleplay__".equals(e.serverAddress());
                    int localExpandH = 16 * 3;

                    boolean showTime = tableW > 250;
                    boolean showBody = tableW > 320;
                    boolean showEngine = tableW > 380;

                    int hiddenCount = 0;
                    if (!showTime) hiddenCount++;
                    if (!showBody) hiddenCount++;
                    if (!showEngine) hiddenCount++;
                    if (hiddenCount > 0) localExpandH += hiddenCount * 16 + 5;

                    if (isSingle && kartSpecExpanded) {
                        String specRaw = e.kartSpecDebug();
                        if (specRaw != null && !specRaw.isEmpty() && !specRaw.equals("없음")) {
                            int lines = 1;
                            if (specRaw.contains("speed")) lines++;
                            if (specRaw.contains("accel") || specRaw.contains("boost:")) lines++;
                            if (specRaw.contains("corner") || specRaw.contains("drift")) lines++;
                            if (specRaw.contains("gauge") || specRaw.contains("boosttime") || specRaw.contains("maxboostcount")) lines++;
                            if (specRaw.contains("defense")) lines++;
                            if (specRaw.contains("draft")) lines++;
                            localExpandH += lines * 16 + 10;
                        } else {
                            localExpandH += 16 + 10;
                        }
                    }

                    if (lapsExpanded) {
                        String lapRaw = e.lapData();
                        if (lapRaw != null && !lapRaw.isEmpty() && !lapRaw.equals("없음")) {
                            int lapCount = lapRaw.split(",").length;
                            localExpandH += lapCount * 16 + 10;
                        } else {
                            localExpandH += 16 + 10;
                        }
                    }

                    itemH += localExpandH + 26;
                }

                if (currentY > tableTop + tableH) break;

                if (mouseY >= currentY - 2 && mouseY <= currentY + itemH - 2) {
                    playUiClick();
                    if (selectedDetailEntry == e) {
                        selectedDetailEntry = null;
                        kartSpecExpanded = false;
                        lapsExpanded = false;
                    } else {
                        selectedDetailEntry = e;
                        kartSpecExpanded = false;
                        lapsExpanded = false;
                    }
                    return true;
                }
                currentY += itemH;
            }
        }

        if (!isInside(mouseX, mouseY, modalX, modalY, modalW, modalH)) {
            playUiClick();
            close();
            return true;
        }

        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        parent.render(context, -100, -100, delta);

        context.getMatrices().push();
        context.getMatrices().translate(0, 0, 400);

        context.fill(0, 0, width, height, 0xAA000000);

        int cx = width / 2;
        int cy = height / 2;
        int modalW = Math.min(width - 40, 500);
        int modalH = Math.min(height - 40, 300);
        int modalX = cx - modalW / 2;
        int modalY = cy - modalH / 2;

        context.fill(modalX, modalY, modalX + modalW, modalY + modalH, 0xEE0B0B0B);
        drawRectBorder(context, modalX, modalY, modalW, modalH, 0xFF444444);

        String titleStr = groupedEntry.entries.get(0).player();
        context.drawTextWithShadow(textRenderer, "👤 " + titleStr, modalX + 15, modalY + 12, 0xFFDDAA);
        context.drawTextWithShadow(textRenderer, "§7" + trackName, modalX + 15, modalY + 26, 0xAAAAAA);
        context.fill(modalX + 10, modalY + 38, modalX + modalW - 10, modalY + 39, 0xFF333333);

        tableX = modalX + 15;
        tableW = modalW - 30;
        tableTop = modalY + 45;
        tableH = modalH - 45 - 35;

        int colRankX   = tableX + (int)(tableW * 0.02);
        int colTimeX   = tableX + (int)(tableW * 0.20);
        int colBodyX   = tableX + (int)(tableW * 0.50);
        int colEngineX = tableX + (int)(tableW * 0.80);

        boolean showTime   = tableW > 150;
        boolean showBody   = tableW > 250;
        boolean showEngine = tableW > 320;

        context.fill(tableX, tableTop, tableX + tableW, tableTop + tableH, 0x66000000);
        drawRectBorder(context, tableX, tableTop, tableW, tableH, 0xFF222222);

        float tableScale = Math.max(0.8f, Math.min(1.0f, tableW / 450.0f));

        context.getMatrices().push();
        context.getMatrices().scale(tableScale, tableScale, 1.0f);

        int headerRowY = tableTop + 8;
        int sHeadY = (int)(headerRowY / tableScale);

        context.drawTextWithShadow(this.textRenderer, "순위", (int)(colRankX / tableScale), sHeadY, 0xDDDDDD);
        if (showTime) context.drawTextWithShadow(this.textRenderer, "기록", (int)(colTimeX / tableScale), sHeadY, 0xDDDDDD);
        if (showBody) context.drawTextWithShadow(this.textRenderer, "카트바디", (int)(colBodyX / tableScale), sHeadY, 0xDDDDDD);
        if (showEngine) context.drawTextWithShadow(this.textRenderer, "엔진", (int)(colEngineX / tableScale), sHeadY, 0xDDDDDD);

        context.getMatrices().pop();

        context.enableScissor(tableX, tableTop + 24, tableX + tableW, tableTop + tableH);

        int startY = tableTop + 24;

        int extraH = 0;
        if (selectedDetailEntry != null) {
            boolean isSingle = "__singleplay__".equals(selectedDetailEntry.serverAddress());
            extraH += 16 * 3;

            int hiddenCount = 0;
            if (!showTime) hiddenCount++;
            if (!showBody) hiddenCount++;
            if (!showEngine) hiddenCount++;
            if (hiddenCount > 0) extraH += hiddenCount * 16 + 5;

            if (isSingle && kartSpecExpanded) {
                String specRaw = selectedDetailEntry.kartSpecDebug();
                if (specRaw != null && !specRaw.isEmpty() && !specRaw.equals("없음")) {
                    int lines = 1;
                    if (specRaw.contains("speed")) lines++;
                    if (specRaw.contains("accel") || specRaw.contains("boost:")) lines++;
                    if (specRaw.contains("corner") || specRaw.contains("drift")) lines++;
                    if (specRaw.contains("gauge") || specRaw.contains("boosttime") || specRaw.contains("maxboostcount")) lines++;
                    if (specRaw.contains("defense")) lines++;
                    if (specRaw.contains("draft")) lines++;
                    extraH += lines * 16 + 10;
                } else {
                    extraH += 16 + 10;
                }
            }

            if (lapsExpanded) {
                String lapRaw = selectedDetailEntry.lapData();
                if (lapRaw != null && !lapRaw.isEmpty() && !lapRaw.equals("없음")) {
                    int lapCount = lapRaw.split(",").length;
                    extraH += lapCount * 16 + 10;
                } else {
                    extraH += 16 + 10;
                }
            }
            extraH += 26;
        }

        int adjustedCapacityRows = Math.max(1, (tableH - 24 - extraH) / ROW_H) + 1;
        int maxScroll = Math.max(0, groupedEntry.entries.size() - adjustedCapacityRows + 1);

        scrollAmount = Math.max(0, Math.min(scrollAmount, maxScroll));
        int start = scrollAmount;
        int end = Math.min(start + Math.max(1, (tableH - 24) / ROW_H) + 3, groupedEntry.entries.size());

        int currentY = startY;

        specBtnScreenW = 0;
        lapsBtnScreenW = 0;

        for (int i = start; i < end; i++) {
            RankingScreen.Entry e = groupedEntry.entries.get(i);
            int rank = groupedEntry.entryRanks.get(i); // 실제 전체 랭킹 순위
            boolean isExpanded = (e == selectedDetailEntry);

            int itemH = ROW_H;
            int localExpandH = 0;
            boolean isSingle = "__singleplay__".equals(e.serverAddress());

            int hiddenCount = 0;
            if (isExpanded) {
                localExpandH += 16 * 3;

                if (!showTime) hiddenCount++;
                if (!showBody) hiddenCount++;
                if (!showEngine) hiddenCount++;
                if (hiddenCount > 0) {
                    localExpandH += hiddenCount * 16 + 5;
                }

                if (isSingle) {
                    if (kartSpecExpanded) {
                        String specRaw = e.kartSpecDebug();
                        if (specRaw != null && !specRaw.isEmpty() && !specRaw.equals("없음")) {
                            int lines = 1;
                            if (specRaw.contains("speed")) lines++;
                            if (specRaw.contains("accel") || specRaw.contains("boost:")) lines++;
                            if (specRaw.contains("corner") || specRaw.contains("drift")) lines++;
                            if (specRaw.contains("gauge") || specRaw.contains("boosttime") || specRaw.contains("maxboostcount")) lines++;
                            if (specRaw.contains("defense")) lines++;
                            if (specRaw.contains("draft")) lines++;
                            localExpandH += lines * 16 + 10;
                        } else {
                            localExpandH += 16 + 10;
                        }
                    }
                }

                if (lapsExpanded) {
                    String lapRaw = e.lapData();
                    if (lapRaw != null && !lapRaw.isEmpty() && !lapRaw.equals("없음")) {
                        int lapCount = lapRaw.split(",").length;
                        localExpandH += lapCount * 16 + 10;
                    } else {
                        localExpandH += 16 + 10;
                    }
                }

                itemH += localExpandH + 26;
            }

            if (currentY > tableTop + tableH) break;

            int bgColor = 0x22000000;
            if (isExpanded) bgColor = 0x550B0B0B;
            else if (((i - start) & 1) == 0) bgColor = 0x00000000;

            context.fill(tableX + 1, currentY - 2, tableX + tableW - 1, currentY + itemH - 2, bgColor);
            if (isExpanded) {
                drawRectBorder(context, tableX + 1, currentY - 2, tableW - 2, itemH, 0xFF444444);
            }

            if (!isExpanded && mouseX >= tableX && mouseX <= tableX + tableW && mouseY >= currentY - 2 && mouseY <= currentY + itemH - 2) {
                context.fill(tableX + 1, currentY - 2, tableX + tableW - 1, currentY + itemH - 2, 0x33FFFFFF);
            }

            context.getMatrices().push();
            context.getMatrices().scale(tableScale, tableScale, 1.0f);

            int sY = (int)((currentY + (ROW_H - 10 * tableScale) / 2) / tableScale);

            context.drawTextWithShadow(this.textRenderer, rank + "위", (int)(colRankX / tableScale), sY, 0xDDDDDD);

            boolean hiddenSomething = false;
            if (showTime) {
                int nextTimeColX = showBody ? colBodyX : (showEngine ? colEngineX : tableX + tableW);
                int maxTimeW = Math.max(0, (int)((nextTimeColX - colTimeX - 5) / tableScale));
                context.drawTextWithShadow(this.textRenderer, trimWithEllipsis(e.timeStr(), maxTimeW), (int)(colTimeX / tableScale), sY, 0xFFFFFF);

                if (isExpanded && e.lapData() != null && !e.lapData().isEmpty() && !e.lapData().equals("없음")) {
                    String btnText = lapsExpanded ? "[-]" : "[+]";
                    int btnX = (int)(colTimeX / tableScale) + this.textRenderer.getWidth(trimWithEllipsis(e.timeStr(), maxTimeW)) + 5;

                    lapsBtnScreenX = (int)(btnX * tableScale);
                    lapsBtnScreenY = (int)(sY * tableScale);
                    lapsBtnScreenW = (int)(this.textRenderer.getWidth(btnText) * tableScale);
                    lapsBtnScreenH = (int)(10 * tableScale);

                    boolean hoveringLaps = isInside(mouseX, mouseY, lapsBtnScreenX, lapsBtnScreenY, lapsBtnScreenW, lapsBtnScreenH);
                    int btnColor = lapsExpanded ? (hoveringLaps ? 0xFFFF7777 : 0xFFFF5555) : (hoveringLaps ? 0xFF77FF77 : 0xFF55FF55);

                    context.drawTextWithShadow(textRenderer, btnText, btnX, sY, btnColor);
                }
            } else hiddenSomething = true;

            if (showBody) {
                int nextBodyColX = showEngine ? colEngineX : tableX + tableW;
                int maxBodyW = Math.max(0, (int)((nextBodyColX - colBodyX - 5) / tableScale));
                String bodyLabel = TireUtil.composeBodyLabel(e.bodyName(), e.tireName());
                context.drawTextWithShadow(this.textRenderer, trimWithEllipsis(bodyLabel, maxBodyW), (int)(colBodyX / tableScale), sY, 0xFFFFFF);

                if (isSingle && isExpanded) {
                    String btnText = kartSpecExpanded ? "[-]" : "[+]";
                    int btnX = (int)(colBodyX / tableScale) + this.textRenderer.getWidth(trimWithEllipsis(bodyLabel, maxBodyW)) + 5;

                    specBtnScreenX = (int)(btnX * tableScale);
                    specBtnScreenY = (int)(sY * tableScale);
                    specBtnScreenW = (int)(this.textRenderer.getWidth(btnText) * tableScale);
                    specBtnScreenH = (int)(10 * tableScale);

                    boolean hoveringSpec = isInside(mouseX, mouseY, specBtnScreenX, specBtnScreenY, specBtnScreenW, specBtnScreenH);
                    int btnColor = kartSpecExpanded ? (hoveringSpec ? 0xFFFF7777 : 0xFFFF5555) : (hoveringSpec ? 0xFF77FF77 : 0xFF55FF55);

                    context.drawTextWithShadow(textRenderer, btnText, btnX, sY, btnColor);
                }
            } else hiddenSomething = true;

            if (showEngine) {
                int maxEngW = Math.max(0, (int)((tableX + tableW - colEngineX - 5) / tableScale));
                context.drawTextWithShadow(this.textRenderer, trimWithEllipsis(normalizeEngine(e.engineName()), maxEngW), (int)(colEngineX / tableScale), sY, 0xFFFFFF);
            } else hiddenSomething = true;

            if (hiddenSomething) {
                context.drawTextWithShadow(this.textRenderer, "+", (int)((tableX + tableW - 20) / tableScale), sY, 0xAAAAAA);
            }
            context.getMatrices().pop();

            if (isExpanded) {
                context.getMatrices().push();
                context.getMatrices().scale(tableScale, tableScale, 1.0f);

                int infoX = (int)((colRankX + 5) / tableScale);
                int infoY = sY + 20;
                int lineH = 16;
                int maxW = (int)((tableW - 50) / tableScale);

                context.drawTextWithShadow(textRenderer, trimWithEllipsis("§7" + selectedProfileDesc, maxW), infoX, infoY, 0xAAAAAA);
                infoY += lineH;
                infoY += 5;

                if (hiddenCount > 0) {
                    if (!showTime) {
                        context.drawTextWithShadow(textRenderer, "§8기록: §e" + e.timeStr(), infoX, infoY, 0xFFFFFF);
                        infoY += lineH;
                    }
                    if (!showBody) {
                        context.drawTextWithShadow(textRenderer, "§8카트: §f" + TireUtil.composeBodyLabel(e.bodyName(), e.tireName()), infoX, infoY, 0xFFFFFF);
                        infoY += lineH;
                    }
                    if (!showEngine) {
                        context.drawTextWithShadow(textRenderer, "§8엔진: §f" + normalizeEngine(e.engineName()), infoX, infoY, 0xFFFFFF);
                        infoY += lineH;
                    }
                }

                context.drawTextWithShadow(textRenderer, "§8모드: §f" + (e.modes() == null || e.modes().isEmpty() ? "없음" : e.modes()), infoX, infoY, 0xFFFFFF);
                infoY += lineH;
                String dateStr = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm").format(new java.util.Date(e.submittedAtMs()));
                context.drawTextWithShadow(textRenderer, "§8등록: §7" + dateStr, infoX, infoY, 0xAAAAAA);
                infoY += lineH;

                if (lapsExpanded) {
                    String lapRaw = e.lapData();
                    if (lapRaw == null || lapRaw.isEmpty() || lapRaw.equals("없음")) {
                        context.drawTextWithShadow(textRenderer, "§c랩타임 정보 없음", infoX, infoY, 0xAAAAAA);
                        infoY += lineH;
                    } else {
                        String headerStr = "§b----- 바퀴별 기록 -----";
                        context.drawTextWithShadow(textRenderer, headerStr, infoX, infoY, 0xFFFFFF);
                        infoY += lineH;

                        String[] laps = lapRaw.split(",");
                        for (String lap : laps) {
                            String[] parts = lap.split(":");
                            if (parts.length >= 2) {
                                String lapNumStr = parts[0].replace("lap", "").trim();
                                String lapTimeStr = lap.substring(parts[0].length() + 1).trim();
                                context.drawTextWithShadow(textRenderer, "§e" + lapNumStr + "바퀴: §f" + lapTimeStr, infoX, infoY, 0xFFFFFF);
                                infoY += lineH;
                            }
                        }
                    }
                }

                if (isSingle && kartSpecExpanded) {
                    String specRaw = e.kartSpecDebug();
                    if (specRaw == null || specRaw.isEmpty() || specRaw.equals("없음")) {
                        context.drawTextWithShadow(textRenderer, "§c스탯 정보 없음", infoX, infoY, 0xAAAAAA);
                        infoY += lineH;
                    } else {
                        String kartName = e.bodyName();
                        if (kartName == null || kartName.equals("UNKNOWN") || kartName.isEmpty()) kartName = "카트바디";
                        String headerStr = "§e----- " + kartName + " 스탯 -----";
                        context.drawTextWithShadow(textRenderer, headerStr, infoX, infoY, 0xFFFFFF);
                        infoY += lineH;

                        String[] specs = specRaw.split(",");
                        Map<String, String> specMap = new HashMap<>();
                        for (String sp : specs) {
                            String[] parts = sp.split(":");
                            if (parts.length == 2) specMap.put(parts[0].trim(), parts[1].trim());
                        }

                        List<String> displayLines = new ArrayList<>();
                        if (specMap.containsKey("speed"))
                            displayLines.add("§b스피드: §f" + specMap.get("speed"));
                        if (specMap.containsKey("accel") || specMap.containsKey("boost"))
                            displayLines.add("§b가속력: §7기본 §f" + specMap.getOrDefault("accel", "-") + " §8| §7부스터 §f" + specMap.getOrDefault("boost", "-"));
                        if (specMap.containsKey("corner") || specMap.containsKey("drift"))
                            displayLines.add("§b코너링: §7기본 §f" + specMap.getOrDefault("corner", "-") + " §8| §7탈출력 §f" + specMap.getOrDefault("drift", "-"));
                        if (specMap.containsKey("gauge") || specMap.containsKey("boosttime") || specMap.containsKey("maxboostcount"))
                            displayLines.add("§b부스터: §7충전 §f" + specMap.getOrDefault("gauge", "-") + " §8| §7지속 §f" + specMap.getOrDefault("boosttime", "-") + " §8| §7슬롯 §f" + specMap.getOrDefault("maxboostcount", "-"));
                        if (specMap.containsKey("defense"))
                            displayLines.add("§b충돌: §7방어력 §f" + specMap.get("defense"));
                        if (specMap.containsKey("draft"))
                            displayLines.add("§b드래프트: §f" + specMap.get("draft"));

                        int col1 = infoX;
                        int col2 = infoX + (int)(150 / tableScale);
                        for (int spIdx = 0; spIdx < displayLines.size(); spIdx++) {
                            int px = (spIdx % 2 == 0) ? col1 : col2;
                            int py = infoY + (spIdx / 2) * lineH;
                            context.drawTextWithShadow(textRenderer, displayLines.get(spIdx), px, py, 0xFFFFFF);
                        }
                        infoY += ((displayLines.size() + 1) / 2) * lineH;
                    }
                }

                context.getMatrices().pop();
            }

            currentY += itemH;
        }

        context.disableScissor();

        if (maxScroll > 0) {
            int barW = 4; // 슬림하고 현대적인 스크롤바 바 너비
            int barX = tableX + tableW - barW - 3;
            int barY = tableTop + 24;
            int barH = tableH - 26;

            // 트랙 배경 (더 정교하고 우아한 어두운 반투명 색상)
            context.fill(barX, barY, barX + barW, barY + barH, 0x33000000);

            float scrollProgress = maxScroll > 0 ? (float) scrollAmount / maxScroll : 0;

            // 콘텐츠 용량 비율을 안전하게 바인딩하여 계산식 오작동 원천 차단
            float visibleRatio = (float) adjustedCapacityRows / (float) groupedEntry.entries.size();
            int thumbH = (int) (barH * Math.min(1.0f, visibleRatio));

            // 트랙 높이보다 넘치지 않고 최소 15픽셀 이상을 유지하도록 정밀 보정
            thumbH = Math.max(15, Math.min(barH - 4, thumbH));

            int thumbY = barY + (int) ((barH - thumbH) * scrollProgress);
            context.fill(barX, thumbY, barX + barW, thumbY + thumbH, 0xFF777777);
        }

        int closeX = modalX + modalW - 15 - 40;
        int closeY = modalY + modalH - 26;
        boolean hoverClose = isInside(mouseX, mouseY, closeX, closeY, 40, 20);
        context.fill(closeX, closeY, closeX + 40, closeY + 20, hoverClose ? 0xFF333333 : 0xFF111111);
        drawRectBorder(context, closeX, closeY, 40, 20, hoverClose ? 0xFF666666 : 0xFF444444);
        context.drawCenteredTextWithShadow(textRenderer, "닫기", closeX + 20, closeY + 6, 0xFFFFFF);

        context.getMatrices().pop();
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