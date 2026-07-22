package com.example.mixin.client;

import com.example.rankinglog.*;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.entity.decoration.DisplayEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.block.Block;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.util.Identifier;
import net.minecraft.world.GameMode;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.resource.Resource;
import net.minecraft.resource.ResourceManager;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.io.BufferedReader;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Mixin(InGameHud.class)
public class InGameHudMixin {

	@Shadow private Text title;
	@Shadow private Text subtitle;

	@Unique private String lastTitle = "";
	@Unique private String lastSubtitle = "";
	@Unique private boolean modWarningShown = false;

	@Unique private boolean pendingTrackRetry = false;
	@Unique private long trackRetryStartMs = 0;
	@Unique private int trackRetryCount = 0;
	@Unique private String pendingTimeStr = null;
	@Unique private long pendingTimeMillis = -1;

	@Unique private record AllowedServer(String ip, int port, boolean isDevServer) {}
	@Unique private static volatile List<AllowedServer> cachedAllowedServers = null;
	@Unique private static volatile java.util.Set<String> cachedDevWhitelist = null;
	@Unique private static volatile boolean serverListFetching = false;
	@Unique private static volatile long lastServerListFetchMs = 0L;
	@Unique private static final long SERVER_LIST_TTL_MS = 5 * 60 * 1000L;

	@Unique private static String cachedTrackName = null;
	@Unique private static List<DisplayEntity.TextDisplayEntity> cachedList = null;

	@Unique private static String cachedEngineName = null;
	@Unique private static long lastEngineScanMs = 0;

	@Unique private boolean soloOk = true;
	@Unique private long lastSoloScanMs = 0;

	@Unique private String lastSoloFailTimeStr = null;
	@Unique private long lastSoloFailMsgMs = 0;
	@Unique private static final long SOLO_FAIL_COOLDOWN_MS = 1500;

	@Unique private static String lastDebugKey = null;
	@Unique private static long lastDebugAtMs = 0;

	@Unique private boolean random_text = true;

	@Unique private static long lastTrackLogMs = 0;
	@Unique private static String lastTrackLogValue = null;

	@Unique private static long lastEngineLogMs = 0;
	@Unique private static String lastEngineLogValue = null;

	@Unique private static ClientWorld lastWorld = null;
	@Unique private static boolean cachedSingleplayerGameSystem = false;

	// ★ 가짜 기록(관전 버그) 방지를 위한 상태 변수
	@Unique private boolean isRacing = false;
	@Unique private ClientWorld currentRenderWorld = null;

	// ★ 랩타임 연동 및 서브타이틀 반복 방지 플래그
	@Unique private boolean multiPlayerSubmitArmed = false;

	// ★ 기록 등록 도배 방지를 위한 10초 쿨타임 변수
	@Unique private long lastSubmitAttemptMs = 0L;
	@Unique private static final long SUBMIT_ATTEMPT_COOLDOWN_MS = 10_000L;

	@Unique private static final TagKey<Block> BLACK_BLOCKS = TagKey.of(
			RegistryKeys.BLOCK,
			Identifier.of("kartmobil", "blackblocks")
	);

	@Unique private static final long LOG_COOLDOWN_MS = 600;

	@Unique
	private static final Pattern ENGINE_NAME_PATTERN =
			Pattern.compile("\\[[A-Z0-9.+]+\\s*엔진", Pattern.CASE_INSENSITIVE);

	@Unique private static final double ENGINE_SCAN_RADIUS_XZ = 3.0;
	@Unique private static final double ENGINE_SCAN_RADIUS_Y  = 6.0;

	@Unique private static final double NEAR_PLAYER_RADIUS = 25.0;
	@Unique private static final double NEAR_PLAYER_RADIUS_SQ = NEAR_PLAYER_RADIUS * NEAR_PLAYER_RADIUS;

	@Unique private static String cachedTireName = "UNKNOWN";
	@Unique private static long lastTireScanMs = 0;
	@Unique private static final long TIRE_SCAN_INTERVAL_MS = 800;

	// 타이어 인식이 실패할 경우를 대비한 백업(캐싱) 변수
	@Unique private static String fallbackTireName = "UNKNOWN";
	@Unique private static long lastFallbackScanMs = 0;

	@Unique
	private static final Box LOBBY_BOX = new Box(-21, 3, 155, -15, -1, 152);

	// ★ 새로 추가된 타임어택 구역 (실제 좌표로 반드시 수정해 주세요!)
	@Unique
	private static final Box ATTACK_BOX = new Box(0, 0, 0, 0, 0, 0);

	@Unique
	private static final Box DEV_ATTACK_BOX = new Box(0, 0, 0, 0, 0, 0);

	@Unique
	private static final Pattern ALLOWED_KART_FILE_PATTERN = Pattern.compile("get(classic|common|rare|legend|unique|special)kart(?:-\\d+)?\\.mcfunction");

	@Inject(method = "render", at = @At("HEAD"))
	private void onRender(CallbackInfo ci) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.player == null || client.world == null) {
			if (this.isRacing && DebugLog.enabled()) DebugLog.chat("§c[Debug] 서버/월드 퇴장으로 인해 isRacing 강제 초기화");
			this.isRacing = false;
			this.multiPlayerSubmitArmed = false;
			AutoSubmitter.multiPlayerSubmitArmed = false;
			return;
		}

		if (this.currentRenderWorld != client.world) {
			if (this.isRacing && DebugLog.enabled()) DebugLog.chat("§c[Debug] 월드/차원 변경 감지로 인해 isRacing 강제 초기화");
			this.currentRenderWorld = client.world;
			this.isRacing = false;
			this.multiPlayerSubmitArmed = false;
			AutoSubmitter.multiPlayerSubmitArmed = false;
		}

		GameMode currentGameMode = client.interactionManager != null ? client.interactionManager.getCurrentGameMode() : null;

		if (currentGameMode != GameMode.ADVENTURE) {
			if (this.isRacing && DebugLog.enabled()) DebugLog.chat("§c[Debug] 게임모드 변경(모험 아님)으로 인해 isRacing 강제 종료");
			this.isRacing = false;
			this.multiPlayerSubmitArmed = false;
			AutoSubmitter.multiPlayerSubmitArmed = false;
		}

		updateCurrentServerHolder(client);

		long currentMs = System.currentTimeMillis();
		if (currentMs - lastFallbackScanMs > 1000) {
			lastFallbackScanMs = currentMs;
			String bgTire = findTireNameFromAttribute();
			if (!"UNKNOWN".equals(bgTire)) {
				fallbackTireName = bgTire;
			}
		}

		String t = title != null ? title.getString() : "";
		String s = subtitle != null ? subtitle.getString() : "";

		if (isAllowedServer()) {
			if ("로딩중...".equals(t)) {
				try { ModGatekeeper.onLoadingTitle(); } catch (Throwable ignored) {}
			}

			BodyCaptureManager.tickScan();

			// ★ 기존에 바깥에 꺼내져 있던 타이틀 "3" 감지 관련 처리를
			// 도배 방지를 위해 상태가 바뀌었을 때(!t.equals(lastTitle)) 실행되는 블록 내부로 통합했습니다.
			// (아래쪽 코드 블록 참고)

			if ("완주 실패".equals(t)) {
				if (isRacing && DebugLog.enabled()) DebugLog.chat("§c[Debug] '완주 실패' 타이틀 감지로 인해 isRacing 강제 종료");
				isRacing = false;
				multiPlayerSubmitArmed = false;
				AutoSubmitter.multiPlayerSubmitArmed = false;
				BodyCaptureManager.onRaceFailed();
				try { ModGatekeeper.freezeNow(); } catch (Throwable ignored) {}
			}

			if ("1".equals(t)) {
				try { ModGatekeeper.freezeNow(); } catch (Throwable ignored) {}
			}
		}

		if (pendingTrackRetry) {
			long now = System.currentTimeMillis();
			if (now - trackRetryStartMs >= 200) {
				trackRetryStartMs = now;
				trackRetryCount++;

				boolean ok = tryCacheTrackName();
				logTrack(ok ? ("§7[Track] 재시도 성공: " + safeShow(cachedTrackName)) : "§7[Track] 재시도 실패(list 부족/빈값)");

				if (ok && cachedTrackName != null && !cachedTrackName.isBlank()) {
					pendingTrackRetry = false;

					String track = cachedTrackName.replace("\n", " ").replaceAll("\\s+", " ").trim();

					if (track.toUpperCase().contains("RANDOM")) {
						if (random_text) {
							random_text = false;
						}
					} else if (track.isBlank()) {
						client.player.sendMessage(Text.literal("§c[MCRiderRanking] 트랙 이름을 찾을 수 없어 기록되지 않습니다."), false);
					} else if (pendingTimeMillis < 0 || pendingTimeStr == null) {
						client.player.sendMessage(Text.literal("§c[MCRiderRanking] 기록 데이터를 분실하여 등록되지 않습니다."), false);
					} else {
						String engineName = (cachedEngineName == null) ? "UNKNOWN" : cachedEngineName;
						String modesCsv = "없음";
						try { modesCsv = ModGatekeeper.getModesCsv(); } catch (Throwable ignored) {}

						String player = client.player.getGameProfile().getName();
						if (ModConfig.get().autoSubmitEnabled) {
							String bodyName = BodyCaptureManager.getCachedKartBodyNameOrUnknown();

							if (isValidKartStat(client, bodyName)) {
								if (DebugLog.enabled()) DebugLog.chat("§a[Debug] (재시도) 스탯 검증 완료! AutoSubmitter 로 전송!");
								String kartSpecDebug = buildKartSpecString(client);
								String lapsCsv = String.join(",", AutoSubmitter.lapTimes);
								AutoSubmitter.submitAsync(
										player, track, pendingTimeStr, pendingTimeMillis,
										0, engineName, bodyName, cachedTireName, modesCsv, kartSpecDebug, lapsCsv
								);
								lastSubmitAttemptMs = System.currentTimeMillis();
							} else {
								if (DebugLog.enabled()) DebugLog.chat("§c[Debug] (재시도) 등록 차단: 카트 스탯 검증(isValidKartStat) 실패");
							}
						}
					}
					pendingTimeStr = null;
					pendingTimeMillis = -1;

				} else if (trackRetryCount >= 5) {
					pendingTrackRetry = false;
					if (DebugLog.enabled()) DebugLog.chat("§c[Debug] (재시도) 5회 실패로 최종 등록 취소됨.");
					client.player.sendMessage(Text.literal("§c[MCRiderRanking] 트랙 이름을 찾을 수 없어 기록되지 않습니다."), false);
					pendingTimeStr = null;
					pendingTimeMillis = -1;
				}
			}
		}

		if (isAllowedServer()) {
			if (!t.equals(lastTitle)) {
				if (t.equals("시작")) {
					long now = System.currentTimeMillis();
					if (now - lastSoloScanMs > 800) {
						lastSoloScanMs = now;
						boolean nearOther = hasOtherPlayerNearMe();
						soloOk = !nearOther;
					}
				}

				if (t.equals("1")) {
					// ★ '로딩중' 타이틀이 뜨지 않는 싱글플레이/제작자 대결 대응: 카트바디 캡처를 '3' 타이틀 시점에 활성화.
					//   (멀티 로비도 이 시점에 함께 활성화되며, onTitle3()의 그레이스 기간이 스캔 시간을 벌어줍니다)
					BodyCaptureManager.onLoadingDetected("title_3");

					// ★ [도배 방지용 통합] 타이틀이 "1"으로 막 바뀌었을 때 단 한 번만 검사 및 디버그 출력
					BodyCaptureManager.onTitle3();
				}

				if (t.equals("3")) {

					boolean isAdventure = currentGameMode == GameMode.ADVENTURE;

					if (isAdventure) {
						isRacing = true;
						multiPlayerSubmitArmed = true;
						AutoSubmitter.multiPlayerSubmitArmed = true;
						AutoSubmitter.lapTimes.clear(); // 무장 시 랩타임 초기화
						if (DebugLog.enabled()) DebugLog.chat("§b[Debug] 레이싱 시작 감지! (isRacing = true) Adv:" + isAdventure);
					} else {
						isRacing = false;
						multiPlayerSubmitArmed = false;
						AutoSubmitter.multiPlayerSubmitArmed = false;
						if (DebugLog.enabled()) DebugLog.chat("§c[Debug] '3' 감지했으나 조건 미달로 레이싱 무시. Adv:" + isAdventure);
					}

					long now = System.currentTimeMillis();
					if (now - lastEngineScanMs > 800) {
						lastEngineScanMs = now;

						String engineRaw = findEngineNameNearPlayer();
						if (engineRaw != null) {
							String engine = engineRaw.replace("[", "").replace("]", "").replace("엔진", "").trim();
							cachedEngineName = engine.isBlank() ? "UNKNOWN" : engine;
							logEngine("§a[Engine] 감지 성공(텍스트디스플레이): " + cachedEngineName);
						} else {
							// ★ 싱글플레이/제작자 대결 등 텍스트디스플레이가 없는 환경 대비 어트리뷰트 백업 조회
							String engineFromAttr = findEngineNameFromAttribute();
							if (!"UNKNOWN".equals(engineFromAttr)) {
								cachedEngineName = engineFromAttr;
								logEngine("§a[Engine] 감지 성공(어트리뷰트 백업): " + cachedEngineName);
							} else {
								logEngine("§c[Engine] 감지 실패(텍스트디스플레이 없음/어트리뷰트도 UNKNOWN)");
								// ★ 엔진 감지 실패 시 등록 무장 강제 해제
								multiPlayerSubmitArmed = false;
								AutoSubmitter.multiPlayerSubmitArmed = false;
							}
						}
					}

					long nowTire = System.currentTimeMillis();
					if (nowTire - lastTireScanMs > TIRE_SCAN_INTERVAL_MS) {
						lastTireScanMs = nowTire;
						String tire = findTireNameFromAttribute();

						if ("UNKNOWN".equals(tire) && !"UNKNOWN".equals(fallbackTireName)) {
							cachedTireName = fallbackTireName;
						} else {
							cachedTireName = tire;
							if (!"UNKNOWN".equals(tire)) fallbackTireName = tire;
						}

						if (DebugLog.enabled()) {
							DebugLog.chat("§d[Tire] 감지: " + cachedTireName + ("UNKNOWN".equals(tire) ? " (백업 사용됨)" : ""));
						}
					}
				}
			}

			if (!s.equals(lastSubtitle) && s.matches("^\\d{2}:\\d{2}\\.\\d{3}$")) {
				if (DebugLog.enabled()) DebugLog.chat("§e[Debug] 서브타이틀 기록 형식 감지: " + s);
				boolean shouldSubmit = true;

				boolean wasArmed = multiPlayerSubmitArmed;
				multiPlayerSubmitArmed = false;
				AutoSubmitter.multiPlayerSubmitArmed = false; // 상태 동기화

				if (!wasArmed) {
					shouldSubmit = false;
					if (DebugLog.enabled()) DebugLog.chat("§c[Debug] 등록 차단: multiPlayerSubmitArmed 가 false 입니다.");
				}

				if (System.currentTimeMillis() - lastSubmitAttemptMs < SUBMIT_ATTEMPT_COOLDOWN_MS) {
					shouldSubmit = false;
					isRacing = false;
					if (DebugLog.enabled()) DebugLog.chat("§c[Debug] 등록 차단: 10초 쿨타임 걸림 (" + (System.currentTimeMillis() - lastSubmitAttemptMs) + "ms 경과)");
				}

				String currentBodyName = BodyCaptureManager.getCachedKartBodyNameOrUnknown();

				if (!isRacing) {
					shouldSubmit = false;
					if (DebugLog.enabled()) DebugLog.chat("§c[Debug] 등록 차단: isRacing 플래그가 false 입니다. (관전 중이거나 오류)");
				} else if (currentBodyName == null || currentBodyName.equals("UNKNOWN") || currentBodyName.isBlank()) {
					shouldSubmit = false;
					isRacing = false; // 정보 누락 시 플래그 즉시 회수
					if (DebugLog.enabled()) DebugLog.chat("§c[Debug] 등록 차단: 카트바디 정보 누락 (" + currentBodyName + ")");
				} else {
					isRacing = false; // 정상 상태일 때 플래그 회수
					if (DebugLog.enabled()) DebugLog.chat("§a[Debug] 기본 레이싱/카트 검증 통과 (isRacing 플래그 안전 회수)");
				}

				if (!soloOk && shouldSubmit) {
					boolean ghostOk = false;
					try { ghostOk = ModGatekeeper.isGhostTimeAttackEnvironment(); } catch (Throwable ignored) {}

					if (!ghostOk) {
						if (DebugLog.enabled()) DebugLog.chat("§c[Debug] 등록 차단: 혼자 주행하지 않았으며 고스트 타임어택도 아님.");
						long now = System.currentTimeMillis();
						boolean sameTimeAsLast = (lastSoloFailTimeStr != null && lastSoloFailTimeStr.equals(s));
						boolean inCooldown = (now - lastSoloFailMsgMs) < SOLO_FAIL_COOLDOWN_MS;

						if (!sameTimeAsLast && !inCooldown) {
							lastSoloFailTimeStr = s;
							lastSoloFailMsgMs = now;
						}
						shouldSubmit = false;
					} else {
						if (DebugLog.enabled()) DebugLog.chat("§a[Debug] 고스트 타임어택 환경 확인됨.");
					}
				}

				if (shouldSubmit && !ModConfig.get().autoSubmitEnabled) {
					if (DebugLog.enabled()) DebugLog.chat("§c[Debug] 등록 차단: 설정에서 '자동 기록 등록'이 꺼져 있음.");
					shouldSubmit = false;
				}

				if (shouldSubmit) {
					String modesCsv = "없음";
					try { modesCsv = ModGatekeeper.getModesCsv(); } catch (Throwable ignored) {}

					boolean ok = tryCacheTrackName();
					logTrack(ok ? ("§a[Track] 감지 성공: " + safeShow(cachedTrackName)) : "§c[Track] 감지 실패(list 부족/빈값)");

					if (!ok) {
						if (DebugLog.enabled()) DebugLog.chat("§e[Debug] 트랙 정보 없음! 대기열(pendingTrackRetry)로 전환.");
						pendingTrackRetry = true;
						trackRetryStartMs = System.currentTimeMillis();
						trackRetryCount = 0;
						pendingTimeStr = s;
						pendingTimeMillis = AddRankingScreen.parseTimeToMillis(s);
						shouldSubmit = false;
						// ★ 트랙 감지 실패 시 1차 등록 방지
						multiPlayerSubmitArmed = false;
						AutoSubmitter.multiPlayerSubmitArmed = false;
					}
				}

				if (shouldSubmit) {
					String track = cachedTrackName.replace("\n", " ").replaceAll("\\s+", " ").trim();

					if (track.toUpperCase().contains("RANDOM")) {
						if (DebugLog.enabled()) DebugLog.chat("§c[Debug] 등록 차단: 랜덤 트랙 (" + track + ")");
						if (random_text) {
							random_text = false;
						}
						shouldSubmit = false;
					} else if (track.isBlank()) {
						if (DebugLog.enabled()) DebugLog.chat("§c[Debug] 등록 차단: 빈 트랙 이름");
						client.player.sendMessage(Text.literal("§c[MCRiderRanking] 트랙 이름을 찾을 수 없어 기록되지 않습니다."), false);
						shouldSubmit = false;
					} else {
						String engineName = (cachedEngineName == null) ? "UNKNOWN" : cachedEngineName;

						// ★ 엔진이 UNKNOWN일 경우에도 최종 기록 등록 차단
						if (engineName.equals("UNKNOWN") || engineName.isBlank()) {
							if (DebugLog.enabled()) DebugLog.chat("§c[Debug] 등록 차단: 엔진 정보를 찾을 수 없습니다.");
							shouldSubmit = false;
						}

						long timeMillis = AddRankingScreen.parseTimeToMillis(s);

						if (timeMillis >= 0 && shouldSubmit) {
							if (DebugLog.enabled()) DebugLog.chat("§a[Debug] 최종 스탯 검증(isValidKartStat) 전송 대기 중... (Track: " + track + ")");
							String player = client.player.getGameProfile().getName();
							String modesCsv = "없음";
							try { modesCsv = ModGatekeeper.getModesCsv(); } catch (Throwable ignored) {}

							if (isValidKartStat(client, currentBodyName)) {
								if (DebugLog.enabled()) DebugLog.chat("§a[Debug] 스탯 검증 완료! AutoSubmitter 로 전송!");
								String kartSpecDebug = buildKartSpecString(client);
								String lapsCsv = String.join(",", AutoSubmitter.lapTimes);

								AutoSubmitter.submitAsync(
										player, track, s, timeMillis,
										0, engineName, currentBodyName, cachedTireName, modesCsv, kartSpecDebug, lapsCsv
								);
								lastSubmitAttemptMs = System.currentTimeMillis();
							} else {
								if (DebugLog.enabled()) DebugLog.chat("§c[Debug] 등록 차단: 카트 스탯 검증(isValidKartStat) 실패");
							}
						}
					}
				}
			}

			lastTitle = t;
			lastSubtitle = s;
		}
	}

	@Unique
	private String buildKartSpecString(MinecraftClient client) {
		if (client.getServer() == null) return "없음";

		NbtCompound itemNbt = BodyCaptureManager.getCachedKartItemNbt();
		if (itemNbt == null) return "없음";

		String[] statsToCompare = {
				"speed", "accel", "boost", "corner", "drift",
				"gauge", "boosttime", "maxboostcount", "defense", "draft"
		};

		StringBuilder sb = new StringBuilder();
		for (String stat : statsToCompare) {
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

			if (itemVal != -1) {
				if (sb.length() > 0) sb.append(",");
				sb.append(stat).append(":").append(itemVal);
			}
		}

		return sb.length() > 0 ? sb.toString() : "없음";
	}

	@Unique
	private boolean isValidKartStat(MinecraftClient client, String bodyName) {
		if (client.getServer() == null) {
			return true;
		}

		NbtCompound itemNbt = BodyCaptureManager.getCachedKartItemNbt();
		if (itemNbt == null) {
			client.player.sendMessage(Text.literal("§c[MCRiderRanking] 카트 스탯 정보가 없어 기록이 거부되었습니다."), false);
			return false;
		}

		ResourceManager rm = client.getServer().getResourceManager();
		Map<Identifier, Resource> resources = rm.findResources("function/getkartitem", id -> {
			if (!id.getNamespace().equals("kartmodel")) return false;
			String path = id.getPath();
			if (!path.endsWith(".mcfunction")) return false;
			String fileName = path.substring(path.lastIndexOf('/') + 1);
			return ALLOWED_KART_FILE_PATTERN.matcher(fileName).matches();
		});

		if (resources.isEmpty()) {
			client.player.sendMessage(Text.literal("§c[MCRiderRanking] 서버에 허용된 카트 정보가 없어 기록이 거부되었습니다."), false);
			return false;
		}

		String targetLine = null;
		searchLoop:
		for (Map.Entry<Identifier, Resource> entry : resources.entrySet()) {
			try (BufferedReader reader = entry.getValue().getReader()) {
				String line;
				while ((line = reader.readLine()) != null) {
					line = line.trim();
					if (line.startsWith("function kartmain:makekart") && line.contains("translate:\"" + bodyName + "\"")) {
						targetLine = line;
						break searchLoop;
					}
				}
			} catch (Exception ignored) {}
		}

		if (targetLine == null) {
			client.player.sendMessage(Text.literal("§c[MCRiderRanking] 허용되지 않거나 스탯이 없는 카트여서 기록이 거부되었습니다."), false);
			return false;
		}

		String[] statsToCompare = {
				"speed", "accel", "boost", "corner", "drift",
				"gauge", "boosttime", "maxboostcount", "defense", "draft"
		};

		for (String stat : statsToCompare) {
			int dpVal = extractStatFromLine(targetLine, stat);
			if (dpVal == -1) continue;

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

			if (itemVal != dpVal) {
				client.player.sendMessage(Text.literal("§c[MCRiderRanking] 비정상적인 카트 스탯이 감지되어 기록이 거부되었습니다."), false);
				return false;
			}
		}

		return true;
	}

	@Unique
	private static int extractStatFromLine(String line, String statName) {
		Matcher m = Pattern.compile(statName + ":(\\d+)").matcher(line);
		if (m.find()) {
			return Integer.parseInt(m.group(1));
		}
		return -1;
	}

	@Unique
	private static String findTireNameFromAttribute() {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.player == null || client.world == null) return "UNKNOWN";

		net.minecraft.entity.LivingEntity closestTarget = null;

		Entity rootVehicle = client.player.getRootVehicle();
		if (rootVehicle != null && rootVehicle != client.player) {
			for (Entity candidate : collectVehicleChain(rootVehicle)) {
				if (candidate == client.player) continue;
				if (!(candidate instanceof net.minecraft.entity.LivingEntity livingEntity)) continue;

				String entityName = candidate.getName().getString().toLowerCase();

				if (entityName.contains("안장") || entityName.contains("saddle") ||
						entityName.contains("대구") || entityName.contains("cod")) {
					closestTarget = livingEntity;
					break;
				}
			}
		}

		if (closestTarget != null) {
			var inst = closestTarget.getAttributeInstance(
					net.minecraft.entity.attribute.EntityAttributes.ARMOR
			);
			if (inst != null) {
				for (var mod : inst.getModifiers()) {
					var id = mod.id();
					if (id != null) {
						String idStr = id.toString();
						if (idStr.contains("data-tire")) {
							int value = (int) mod.value();
							return mapTireValueToName(value);
						}
					}
				}
			}
		}

		var playerInst = client.player.getAttributeInstance(
				net.minecraft.entity.attribute.EntityAttributes.EXPLOSION_KNOCKBACK_RESISTANCE
		);
		if (playerInst != null) {
			for (var mod : playerInst.getModifiers()) {
				var id = mod.id();
				if (id != null && id.toString().contains("kart-tire")) {
					int value = (int) mod.value();
					return mapTireValueToName(value);
				}
			}
		}

		return "UNKNOWN";
	}

	@Unique
	private static List<Entity> collectVehicleChain(Entity root) {
		List<Entity> chain = new ArrayList<>();
		java.util.Deque<Entity> queue = new java.util.ArrayDeque<>();
		queue.add(root);
		while (!queue.isEmpty()) {
			Entity cur = queue.poll();
			chain.add(cur);
			for (Entity p : cur.getPassengerList()) {
				queue.add(p);
			}
		}
		return chain;
	}

	@Unique
	private static String mapTireValueToName(int value) {
		return switch (value) {
			case 0 -> "레이싱 타이어";
			case 1 -> "스파이크 타이어";
			default -> "UNKNOWN";
		};
	}

	// ★ 싱글플레이/제작자 대결 등, 텍스트디스플레이가 없는 환경에서
	//   data-engine-real 어트리뷰트 값으로 엔진명을 알아내기 위한 백업 함수 (findTireNameFromAttribute와 동일한 구조)
	@Unique
	private static String findEngineNameFromAttribute() {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.player == null || client.world == null) return "UNKNOWN";

		net.minecraft.entity.LivingEntity closestTarget = null;

		Entity rootVehicle = client.player.getRootVehicle();
		if (rootVehicle != null && rootVehicle != client.player) {
			for (Entity candidate : collectVehicleChain(rootVehicle)) {
				if (candidate == client.player) continue;
				if (!(candidate instanceof net.minecraft.entity.LivingEntity livingEntity)) continue;

				String entityName = candidate.getName().getString().toLowerCase();

				if (entityName.contains("안장") || entityName.contains("saddle") ||
						entityName.contains("대구") || entityName.contains("cod")) {
					closestTarget = livingEntity;
					break;
				}
			}
		}

		if (closestTarget != null) {
			var inst = closestTarget.getAttributeInstance(
					net.minecraft.entity.attribute.EntityAttributes.ARMOR
			);
			if (inst != null) {
				for (var mod : inst.getModifiers()) {
					var id = mod.id();
					if (id != null) {
						String idStr = id.toString();
						if (idStr.contains("data-engine-real")) {
							int value = (int) mod.value();
							return mapEngineValueToName(value);
						}
					}
				}
			}
		}

		var playerInst = client.player.getAttributeInstance(
				net.minecraft.entity.attribute.EntityAttributes.EXPLOSION_KNOCKBACK_RESISTANCE
		);
		if (playerInst != null) {
			for (var mod : playerInst.getModifiers()) {
				var id = mod.id();
				if (id != null && id.toString().contains("data-engine-real")) {
					int value = (int) mod.value();
					return mapEngineValueToName(value);
				}
			}
		}

		return "UNKNOWN";
	}

	// ★ main.mcfunction의 kart-engine 점수(data-engine-real 값)를 실제 표시 이름으로 정규화
	//   KRP -> RUSH+ 로 개명, A2-D-KEYBOARD -> KEY 로 축약, 1004(주석 처리된 보트 엔진) -> BOAT
	@Unique
	private static String mapEngineValueToName(int value) {
		return switch (value) {
			case 0 -> "X";
			case 1 -> "EX";
			case 2 -> "JIU";
			case 3 -> "NEW";
			case 4 -> "Z7";
			case 5 -> "V1";
			case 6 -> "A2";
			case 7 -> "1.0";
			case 8 -> "PRO";
			case 9 -> "RUSH+";
			case 10 -> "CHARGE";
			case 11 -> "SR";
			case 1000 -> "N1";
			case 1001 -> "RX";
			case 1002 -> "KEY";
			case 1003 -> "MK";
			case 1004 -> "BOAT";
			case 1005 -> "GEAR";
			case 1006 -> "F1";
			case 1007 -> "RALLY";
			case 1008 -> "DS";
			default -> "UNKNOWN";
		};
	}

	@Unique
	private String findEngineNameNearPlayer() {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.world == null || client.player == null) return null;

		Vec3d p = client.player.getPos();
		Box box = new Box(
				p.x - ENGINE_SCAN_RADIUS_XZ, p.y - ENGINE_SCAN_RADIUS_Y,  p.z - ENGINE_SCAN_RADIUS_XZ,
				p.x + ENGINE_SCAN_RADIUS_XZ, p.y + ENGINE_SCAN_RADIUS_Y,  p.z + ENGINE_SCAN_RADIUS_XZ
		);

		List<DisplayEntity.TextDisplayEntity> list = new ArrayList<>();
		for (Entity e : client.world.getEntities()) {
			if (e instanceof DisplayEntity.TextDisplayEntity td) {
				if (box.contains(td.getPos())) list.add(td);
			}
		}

		if (list.isEmpty()) return null;

		for (DisplayEntity.TextDisplayEntity td : list) {
			String text = td.getText().getString().replace("\n", " ").trim();
			var m = ENGINE_NAME_PATTERN.matcher(text);
			if (m.find()) return m.group(0).toUpperCase();
		}

		list.sort(Comparator.comparingDouble(Entity::getY).reversed());
		String top = list.get(0).getText().getString().replace("\n", " ").replaceAll("\\s+", " ").trim();
		return top.isBlank() ? null : top;
	}

	@Unique
	private static void updateCurrentServerHolder(MinecraftClient client) {
		if (client.getServer() != null) {
			CurrentServerHolder.set(CurrentServerHolder.SINGLEPLAY);
			return;
		}

		ClientPlayNetworkHandler networkHandler = client.getNetworkHandler();
		if (networkHandler == null || networkHandler.getConnection() == null) {
			CurrentServerHolder.set(null);
			return;
		}

		SocketAddress socketAddress = networkHandler.getConnection().getAddress();
		if (!(socketAddress instanceof InetSocketAddress inetAddress)
				|| inetAddress.getAddress() == null) {
			CurrentServerHolder.set(null);
			return;
		}

		String ip   = inetAddress.getAddress().getHostAddress();
		int    port = inetAddress.getPort();
		CurrentServerHolder.set(ip + ":" + port);
	}

	@Unique private static final Set<String> ALLOWED_PLAYERS = Set.of("BKGpolar");
	@Unique private static boolean isAllowedPlayer() {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.player == null) return false;
		return ALLOWED_PLAYERS.contains(client.player.getGameProfile().getName());
	}

	@Unique private static final boolean USE_PLAYER_LIMIT = false;

	@Unique
	private static boolean isAllowedServer() {
		MinecraftClient client = MinecraftClient.getInstance();

		if (USE_PLAYER_LIMIT) {
			if (isAllowedPlayer()) {
				showServerDebugOnce("multi_ok_forced", Text.of("§a[MCRiderRanking] 플레이어 제한 모드 활성화 (모든 서버 기록 가능)"));
				return true;
			} else {
				showServerDebugOnce("blocked_player", Text.of("§c[MCRiderRanking] 권한이 없는 플레이어입니다. (자동 기록 비활성화)"));
				return false;
			}
		}

		if (client.getServer() != null) {

			if (client.world != lastWorld) {
				lastWorld = client.world;

				fallbackTireName = findTireNameFromAttribute();

				boolean hasBlack = false;

				try {
					for (Block block : net.minecraft.registry.Registries.BLOCK) {
						if (block.getDefaultState().isIn(BLACK_BLOCKS)) {
							hasBlack = true;
							break;
						}
					}
				} catch (Exception ignored) {}

				cachedSingleplayerGameSystem = hasBlack;
			}

			if (cachedSingleplayerGameSystem) {
				// ★ 알림 복구됨
				Text alertMsg = Text.literal("§a[MCRiderRanking] 자동 기록 활성화! (싱글플레이) - [")
						.append(Text.keybind("key.rankinglog.open_ranking").formatted(net.minecraft.util.Formatting.YELLOW))
						.append(Text.literal("§a]키를 눌러 랭킹을 확인할 수 있습니다"));
				showServerDebugOnce("single_ok", alertMsg);
				return true;
			} else {
				return false;
			}
		}

		ClientPlayNetworkHandler networkHandler = client.getNetworkHandler();
		if (networkHandler == null || networkHandler.getConnection() == null) return false;

		SocketAddress socketAddress = networkHandler.getConnection().getAddress();
		if (!(socketAddress instanceof InetSocketAddress inetAddress)) return false;
		if (inetAddress.getAddress() == null) return false;

		String realIp = inetAddress.getAddress().getHostAddress();
		int realPort = inetAddress.getPort();

		List<AllowedServer> servers = getOrFetchAllowedServers();
		if (servers == null) return false;

		AllowedServer matchedServer = null;
		for (AllowedServer s : servers) {
			if (s.ip().equals(realIp) && s.port() == realPort) { matchedServer = s; break; }
		}

		if (matchedServer == null) return false;

		if (matchedServer.isDevServer()) {
			java.util.Set<String> wl = cachedDevWhitelist;
			if (wl == null) {
				return false;
			}
			String myName = MinecraftClient.getInstance() != null
					&& MinecraftClient.getInstance().getSession() != null
					? MinecraftClient.getInstance().getSession().getUsername() : "";
			if (!wl.contains(myName)) {
				showServerDebugOnce("dev_whitelist_blocked:" + myName,
						Text.of("§c[MCRiderRanking] 개발 서버 기록 등록 불가 — 화이트리스트에 없는 플레이어입니다."));
				return false;
			}
		}

		// ★ 알림 복구됨
		Text alertMsg = Text.literal("§a[MCRiderRanking] 자동 기록 활성화! - [")
				.append(Text.keybind("key.rankinglog.open_ranking").formatted(net.minecraft.util.Formatting.YELLOW))
				.append(Text.literal("§a]키를 눌러 랭킹을 확인할 수 있습니다"));
		showServerDebugOnce("multi_ok:" + realIp + ":" + realPort, alertMsg);
		return true;
	}

	@Unique
	private static List<AllowedServer> getOrFetchAllowedServers() {
		long now = System.currentTimeMillis();
		if (cachedAllowedServers != null && (now - lastServerListFetchMs) < SERVER_LIST_TTL_MS) {
			return cachedAllowedServers;
		}
		if (serverListFetching) return cachedAllowedServers;

		serverListFetching = true;
		new Thread(() -> {
			try {
				com.google.gson.JsonObject res = RankingScreen.Net.postJson(
						RankingScreen.SUPABASE_RPC_URL + "get_allowed_servers", "{}"
				);
				List<AllowedServer> list = new java.util.ArrayList<>();
				java.util.Set<String> whitelist = new java.util.HashSet<>();
				if (res != null && res.has("ok") && res.get("ok").getAsBoolean()
						&& res.has("servers")) {
					com.google.gson.JsonArray arr = res.getAsJsonArray("servers");
					for (int i = 0; i < arr.size(); i++) {
						com.google.gson.JsonObject entry = arr.get(i).getAsJsonObject();
						String val = entry.get("value").getAsString().trim();
						boolean isDev = entry.has("is_dev_server") && !entry.get("is_dev_server").isJsonNull()
								&& entry.get("is_dev_server").getAsBoolean();
						int colon = val.lastIndexOf(':');
						if (colon <= 0) continue;
						String ip = val.substring(0, colon).trim();
						int port;
						try { port = Integer.parseInt(val.substring(colon + 1).trim()); }
						catch (NumberFormatException ignored) { continue; }
						list.add(new AllowedServer(ip, port, isDev));
					}
					if (res.has("whitelist") && !res.get("whitelist").isJsonNull()) {
						String raw = res.get("whitelist").getAsString().replace(" ", "");
						for (String name : raw.split(",")) {
							if (!name.isBlank()) whitelist.add(name.trim());
						}
					}
				}
				cachedAllowedServers = list;
				cachedDevWhitelist = whitelist;
				lastServerListFetchMs = System.currentTimeMillis();
			} catch (Exception ignored) {
			} finally {
				serverListFetching = false;
			}
		}, "AllowedServers-Fetch").start();

		return cachedAllowedServers;
	}

	@Unique
	private static void showServerDebugOnce(String key, Text msg) {
		MinecraftClient client = MinecraftClient.getInstance();
		var me = client.player;
		if (me == null || !isNewKey(key)) return;

		long now = System.currentTimeMillis();
		if (now - lastDebugAtMs < 300) return;
		lastDebugAtMs = now;
		lastDebugKey = key;

		me.sendMessage(msg, false);
	}

	@Unique
	private static boolean isNewKey(String key) {
		return lastDebugKey == null || !lastDebugKey.equals(key);
	}

	@Unique
	private boolean tryCacheTrackName() {
		List<DisplayEntity.TextDisplayEntity> list = getTextDisplaysSortedByY();
		cachedList = list;

		if (list.size() < 3) { cachedTrackName = null; return false; }

		String raw = list.get(2).getText().getString();
		if (raw == null || raw.isBlank()) { cachedTrackName = null; return false; }

		cachedTrackName = raw;
		return true;
	}

	@Unique
	private List<DisplayEntity.TextDisplayEntity> getTextDisplaysSortedByY() {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.world == null || client.player == null) return List.of();

		Vec3d p = client.player.getPos();
		Box[] boxes = { LOBBY_BOX, ATTACK_BOX, DEV_ATTACK_BOX };
		Box closestBox = boxes[0];
		double minDst = closestBox.getCenter().squaredDistanceTo(p);

		for (int i = 1; i < boxes.length; i++) {
			double dst = boxes[i].getCenter().squaredDistanceTo(p);
			if (dst < minDst) {
				minDst = dst;
				closestBox = boxes[i];
			}
		}

		List<DisplayEntity.TextDisplayEntity> result = new ArrayList<>();
		for (Entity e : client.world.getEntities()) {
			if (e instanceof DisplayEntity.TextDisplayEntity td) {
				if (closestBox.contains(td.getPos())) result.add(td);
			}
		}

		result.sort(
				Comparator
						.comparingDouble(Entity::getY).reversed()
						.thenComparingDouble(Entity::getX)
						.thenComparingDouble(Entity::getZ)
		);
		return result;
	}

	@Unique
	private boolean hasOtherPlayerNearMe() {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.world == null || client.player == null) return false;

		var me = client.player;
		double mx = me.getX(); double my = me.getY(); double mz = me.getZ();

		for (var p : client.world.getPlayers()) {
			if (p == null || p == me) continue;
			double dx = p.getX() - mx;
			double dy = p.getY() - my;
			double dz = p.getZ() - mz;
			if (dx*dx + dy*dy + dz*dz <= NEAR_PLAYER_RADIUS_SQ) return true;
		}
		return false;
	}

	@Unique
	private void logTrack(String msg) {
		if (!DebugLog.enabled()) return;
		long now = System.currentTimeMillis();
		if (msg.equals(lastTrackLogValue) && (now - lastTrackLogMs) < LOG_COOLDOWN_MS) return;
		lastTrackLogValue = msg;
		lastTrackLogMs = now;
		DebugLog.chat(msg);
	}

	@Unique
	private void logEngine(String msg) {
		if (!DebugLog.enabled()) return;
		long now = System.currentTimeMillis();
		if (msg.equals(lastEngineLogValue) && (now - lastEngineLogMs) < LOG_COOLDOWN_MS) return;
		lastEngineLogValue = msg;
		lastEngineLogMs = now;
		DebugLog.chat(msg);
	}

	@Unique
	private String safeShow(String v) {
		if (v == null) return "null";
		String s = v.replace("\n", " ").replaceAll("\\s+", " ").trim();
		return s.isEmpty() ? "(blank)" : s;
	}
}