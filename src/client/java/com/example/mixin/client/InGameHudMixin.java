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

	@Unique private static final double NEAR_PLAYER_RADIUS = 25.0;
	@Unique private static final double NEAR_PLAYER_RADIUS_SQ = NEAR_PLAYER_RADIUS * NEAR_PLAYER_RADIUS;

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

	// ★ 기록 등록 도배 방지를 위한 10초 쿨타임 변수
	@Unique private long lastSubmitAttemptMs = 0;
	@Unique private static final long SUBMIT_COOLDOWN_MS = 10000;

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

	@Unique private static String cachedTireName = "UNKNOWN";
	@Unique private static long lastTireScanMs = 0;
	@Unique private static final long TIRE_SCAN_INTERVAL_MS = 800;

	// 타이어 인식이 실패할 경우를 대비한 백업(캐싱) 변수
	@Unique private static String fallbackTireName = "UNKNOWN";
	@Unique private static long lastFallbackScanMs = 0;

	@Unique
	private static final Box LOBBY_BOX = new Box(
			-21, 3, 155,
			-15, -1, 152
	);

	@Unique
	private static final Pattern ALLOWED_KART_FILE_PATTERN = Pattern.compile("get(classic|common|rare|legend|unique|special)kart(?:-\\d+)?\\.mcfunction");

	@Inject(method = "render", at = @At("HEAD"))
	private void onRender(CallbackInfo ci) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.player == null || client.world == null) {
			// ★ 서버/월드를 나갔을 때 레이싱 상태 강제 초기화
			this.isRacing = false;
			return;
		}

		// ★ 월드 변경(차원 이동 등) 감지 시 레이싱 상태 초기화
		if (this.currentRenderWorld != client.world) {
			this.currentRenderWorld = client.world;
			this.isRacing = false;
		}

		// ★ 실시간 게임모드 감지 (관전 모드 등으로 변경 시 즉시 레이싱 상태 종료)
		if (this.isRacing && client.interactionManager != null) {
			if (client.interactionManager.getCurrentGameMode() != GameMode.ADVENTURE) {
				this.isRacing = false;
			}
		}

		updateCurrentServerHolder(client);

		// [백그라운드 타이어 스캔] 1초마다 유효한 타이어 값을 찾으면 미리 캐싱
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
				BodyCaptureManager.onLoadingDetected("hud_title");
				try { ModGatekeeper.onLoadingTitle(); } catch (Throwable ignored) {}
			}

			BodyCaptureManager.tickScan();

			if ("3".equals(t)) {
				BodyCaptureManager.onTitle3();
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

			// ★ 완주 실패 시 레이싱 상태 종료 (남의 기록 낚임 방지)
			if ("완주 실패".equals(t)) {
				isRacing = false;
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
								String kartSpecDebug = buildKartSpecString(client);
								AutoSubmitter.submitAsync(
										player, track, pendingTimeStr, pendingTimeMillis,
										0, engineName, bodyName, cachedTireName, modesCsv, kartSpecDebug
								);
								lastSubmitAttemptMs = System.currentTimeMillis(); // ★ 쿨타임 갱신
							}
						}
					}
					pendingTimeStr = null;
					pendingTimeMillis = -1;

				} else if (trackRetryCount >= 5) {
					pendingTrackRetry = false;
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

				if (t.equals("3")) {
					// ★ 관전 버그 방지: 모험 모드 + 특정 태그 보유 확인
					boolean hasTag = client.player.getCommandTags().contains("kart-multi-player");
					boolean isAdventure = client.interactionManager != null && client.interactionManager.getCurrentGameMode() == GameMode.ADVENTURE;

					if (hasTag && isAdventure) {
						isRacing = true;
					} else {
						isRacing = false;
					}

					long now = System.currentTimeMillis();
					if (now - lastEngineScanMs > 800) {
						lastEngineScanMs = now;

						String engineRaw = findEngineNameNearPlayer();
						if (engineRaw != null) {
							String engine = engineRaw.replace("[", "").replace("]", "").replace("엔진", "").trim();
							cachedEngineName = engine.isBlank() ? "UNKNOWN" : engine;
							logEngine("§a[Engine] 감지 성공: " + cachedEngineName);
						} else {
							logEngine("§c[Engine] 감지 실패(주변 텍스트디스플레이 없음/패턴 불일치)");
						}
					}
				}
			}

			// ★ 기록 서브타이틀 감지 시
			if (!s.equals(lastSubtitle) && s.matches("^\\d{2}:\\d{2}\\.\\d{3}$")) {
				boolean shouldSubmit = true;

				// ★ 10초 쿨타임 검사 (연속 등록 도배 방지)
				if (System.currentTimeMillis() - lastSubmitAttemptMs < SUBMIT_COOLDOWN_MS) {
					shouldSubmit = false;
					isRacing = false; // 등록 시도 무시 및 초기화
				}

				// 기록 체크 전 카트바디를 가져옵니다.
				String currentBodyName = BodyCaptureManager.getCachedKartBodyNameOrUnknown();

				// ★ 1. 레이싱 상태 검증 (관전 상태면 무시)
				// ★ 2. 카트 정보 정상 검증
				if (!isRacing) {
					shouldSubmit = false;
				} else if (currentBodyName == null || currentBodyName.equals("UNKNOWN") || currentBodyName.isBlank()) {
					shouldSubmit = false;
					isRacing = false; // 정보 누락 시 플래그 즉시 회수
				} else {
					// 모든 검증 통과 시 다음 기록에 반응하지 않도록 플래그 즉시 회수
					isRacing = false;
				}

				if (!soloOk && shouldSubmit) {
					// 혼자가 아닐 때 고스트 타임어택 환경(3개 모드 모두 포함)인지 확인
					boolean ghostOk = false;
					try { ghostOk = ModGatekeeper.isGhostTimeAttackEnvironment(); } catch (Throwable ignored) {}

					if (!ghostOk) {
						long now = System.currentTimeMillis();
						boolean sameTimeAsLast = (lastSoloFailTimeStr != null && lastSoloFailTimeStr.equals(s));
						boolean inCooldown = (now - lastSoloFailMsgMs) < SOLO_FAIL_COOLDOWN_MS;

						if (!sameTimeAsLast && !inCooldown) {
							lastSoloFailTimeStr = s;
							lastSoloFailMsgMs = now;
						}
						shouldSubmit = false;
					}
				}

				if (shouldSubmit && !ModConfig.get().autoSubmitEnabled) {
					shouldSubmit = false;
				}

				if (shouldSubmit) {
					String modesCsv = "없음";
					try { modesCsv = ModGatekeeper.getModesCsv(); } catch (Throwable ignored) {}

					boolean ok = tryCacheTrackName();
					logTrack(ok ? ("§a[Track] 감지 성공: " + safeShow(cachedTrackName)) : "§c[Track] 감지 실패(list 부족/빈값)");

					if (!ok) {
						pendingTrackRetry = true;
						trackRetryStartMs = System.currentTimeMillis();
						trackRetryCount = 0;
						pendingTimeStr = s;
						pendingTimeMillis = AddRankingScreen.parseTimeToMillis(s);
						shouldSubmit = false;
					}
				}

				if (shouldSubmit) {
					String track = cachedTrackName.replace("\n", " ").replaceAll("\\s+", " ").trim();

					if (track.toUpperCase().contains("RANDOM")) {
						if (random_text) {
							random_text = false;
						}
						shouldSubmit = false;
					} else if (track.isBlank()) {
						client.player.sendMessage(Text.literal("§c[MCRiderRanking] 트랙 이름을 찾을 수 없어 기록되지 않습니다."), false);
						shouldSubmit = false;
					} else {
						String engineName = (cachedEngineName == null) ? "UNKNOWN" : cachedEngineName;
						long timeMillis = AddRankingScreen.parseTimeToMillis(s);

						if (timeMillis >= 0) {
							String player = client.player.getGameProfile().getName();
							String modesCsv = "없음";
							try { modesCsv = ModGatekeeper.getModesCsv(); } catch (Throwable ignored) {}

							if (isValidKartStat(client, currentBodyName)) {
								String kartSpecDebug = buildKartSpecString(client);
								AutoSubmitter.submitAsync(
										player, track, s, timeMillis,
										0, engineName, currentBodyName, cachedTireName, modesCsv, kartSpecDebug
								);
								lastSubmitAttemptMs = System.currentTimeMillis(); // ★ 쿨타임 갱신
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

		// 1. 신식 감지 방법: 플레이어가 실제로 탑승 중인 차량(getVehicle 체인)에서 대구/안장 엔티티를 탐색
		//    (반경 스캔 방식은 멀티플레이 환경에서 주변의 다른 플레이어 카트를 잘못 잡아내는 문제가 있어
		//     실제 탑승 관계(루트 비히클 + 모든 승객 체인)를 기준으로 탐색하도록 변경)
		net.minecraft.entity.LivingEntity closestTarget = null;

		Entity rootVehicle = client.player.getRootVehicle();
		if (rootVehicle != null && rootVehicle != client.player) {
			for (Entity candidate : collectVehicleChain(rootVehicle)) {
				if (candidate == client.player) continue;
				if (!(candidate instanceof net.minecraft.entity.LivingEntity livingEntity)) continue;

				String entityName = candidate.getName().getString().toLowerCase();

				// 이름에 '안장'(saddle) 또는 '대구'(cod)가 포함되어 있는지 확인
				if (entityName.contains("안장") || entityName.contains("saddle") ||
						entityName.contains("대구") || entityName.contains("cod")) {
					closestTarget = livingEntity;
					break;
				}
			}
		}

		// 가장 가까운 타겟을 찾았다면 ARMOR 어트리뷰트에서 data-tire 값을 검색
		if (closestTarget != null) {
			var inst = closestTarget.getAttributeInstance(
					net.minecraft.entity.attribute.EntityAttributes.ARMOR
			);
			if (inst != null) {
				for (var mod : inst.getModifiers()) {
					var id = mod.id();
					if (id != null) {
						String idStr = id.toString();
						// data-tire 식별자 확인
						if (idStr.contains("data-tire")) {
							int value = (int) mod.value();
							return mapTireValueToName(value);
						}
					}
				}
			}
		}

		// 2. 구버전 감지 방법: 플레이어 본인의 폭발 넉백 저항 속성 확인
		var playerInst = client.player.getAttributeInstance(
				net.minecraft.entity.attribute.EntityAttributes.EXPLOSION_KNOCKBACK_RESISTANCE
		);
		if (playerInst != null) {
			for (var mod : playerInst.getModifiers()) {
				var id = mod.id();
				// 이전 버전 호환을 위해 minecraft:kart-tire 포함 여부 확인
				if (id != null && id.toString().contains("kart-tire")) {
					int value = (int) mod.value();
					return mapTireValueToName(value);
				}
			}
		}

		return "UNKNOWN";
	}

	/**
	 * 루트 비히클로부터 시작해 모든 승객(passenger) 체인을 BFS로 수집합니다.
	 * 카트가 여러 엔티티가 중첩 탑승된 구조(예: 본체 위에 대구/안장이 추가로 탑승)인 경우에도
	 * 전체 탑승 구조를 빠짐없이 탐색할 수 있도록 합니다.
	 */
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
		if (client.world == null) return List.of();

		List<DisplayEntity.TextDisplayEntity> result = new ArrayList<>();
		for (Entity e : client.world.getEntities()) {
			if (e instanceof DisplayEntity.TextDisplayEntity td) {
				if (LOBBY_BOX.contains(td.getPos())) result.add(td);
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