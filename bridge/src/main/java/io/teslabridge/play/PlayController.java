package io.teslabridge.play;

import io.teslamate.play.CarWhitelistProvider;
import io.teslamate.play.PlayAuditLogger;
import io.teslamate.play.PlayComputeException;
import io.teslamate.play.PlayDefinition;
import io.teslamate.play.PlayDefinition.OutputField;
import io.teslamate.play.PlayEngine;
import io.teslamate.play.PlayRegistry;
import io.teslamate.play.PlayScopeChecker;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 玩法引擎 2 端点（开源版，SVG 卡片渲染仅活在 SaaS 私有层）：
 *
 * <pre>
 * GET /api/v1/plays                              → listPlays（纯元数据）
 * GET /api/v1/cars/{carId}/play/{playName}       → runPlay（JSON envelope）
 * </pre>
 *
 * <p>生图通过 接口二（AGENTS.md）路径完成：Agent 拿到 JSON 后，自行调用生图模型或
 * 通过 {@code generate_play_image} MCP tool 调 Seedream API 生成分享图。
 *
 * <p>每个请求顺序：audit log → carId 不在白名单 → 404 → playName 先过正则再查 registry，
 * 未注册 → 404 → parseLdt 时间窗（缺省 {@code params.default_days}，无则 30）
 * → 查 SQL → {@code min_sample} 不足返 unscored → compute → envelope {@code Map.of("data", ...)}。
 *
 * <p>鉴权由 {@link io.teslabridge.auth.StaticTokenFilter} 处理（Bearer API_TOKEN）。
 *
 * <p>三个 SaaS/bridge 差异点通过接口注入（均来自 play-engine-core，bridge 侧
 * bean 在同包 {@code io.teslabridge.play} 里）：
 * <ul>
 *   <li>{@link CarWhitelistProvider} → {@link EnvCarWhitelistProvider}（读 CAR_IDS env）
 *   <li>{@link PlayAuditLogger} → {@link LogPlayAuditLogger}（log.info）
 *   <li>{@link PlayScopeChecker} → {@link NoopPlayScopeChecker}（永远 false）
 * </ul>
 */
@RestController
public class PlayController {

    private static final Logger log = LoggerFactory.getLogger(PlayController.class);

    /** playName 正则（与 manifest name schema 同源）—— 先过正则再查 registry。 */
    static final Pattern PLAY_NAME_RE = Pattern.compile("^[a-z0-9][a-z0-9-]{1,38}[a-z0-9]$");

    /** 窗口硬上限：schema params.default_days 上限同值（365），防 PG 拖死。 */
    static final long MAX_WINDOW_DAYS = 365;

    /** 404 占位体（与 SaaS 侧跨租户同语，不泄露资源存在性）。 */
    private static final Map<String, Object> NO_INFO = Map.of("data", Map.of());

    private final PlayRegistry registry;
    private final PlayEngine engine;
    private final CarWhitelistProvider carWhitelistProvider;
    private final PlayAuditLogger auditLogger;
    private final PlayScopeChecker scopeChecker;

    public PlayController(
            PlayRegistry registry,
            PlayEngine engine,
            CarWhitelistProvider carWhitelistProvider,
            PlayAuditLogger auditLogger,
            PlayScopeChecker scopeChecker) {
        this.registry = registry;
        this.engine = engine;
        this.carWhitelistProvider = carWhitelistProvider;
        this.auditLogger = auditLogger;
        this.scopeChecker = scopeChecker;
    }

    // ====== 1) GET /api/v1/plays ======

    @GetMapping("/api/v1/plays")
    public ResponseEntity<Map<String, Object>> listPlays() {
        auditLogger.log("/api/v1/plays");
        List<Map<String, Object>> plays = new ArrayList<>();
        for (PlayDefinition p : registry.all()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", p.name());
            item.put("title", p.title());
            item.put("emoji", p.emoji());
            item.put("description", p.description());
            item.put("scope", p.scope());
            item.put("default_days", p.defaultDays());
            plays.add(item);
        }
        return ResponseEntity.ok(Map.of("data", Map.of("plays", plays)));
    }

    // ====== 2) GET /api/v1/cars/{carId}/play/{playName} ======

    @GetMapping("/api/v1/cars/{carId:\\d+}/play/{playName}")
    public ResponseEntity<Map<String, Object>> runPlay(
            @PathVariable("carId") Long carId,
            @PathVariable("playName") String playName,
            @RequestParam(value = "start_date", required = false) String startDate,
            @RequestParam(value = "end_date", required = false) String endDate) {
        auditLogger.log("/api/v1/cars/" + carId + "/play/" + safeAuditName(playName));
        // defense-in-depth: CAR_IDS whitelist check is fail-closed; 404 status matches
        // unlisted-car response to prevent status-code information leakage.
        if (carWhitelistProvider.outOfWhitelist(carId)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(NO_INFO);
        }
        Optional<PlayDefinition> found = lookupPlay(playName);
        if (found.isEmpty()) {
            // 未注册与跨租户同语 404 NO_INFO —— 不泄露玩法存在性。
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(NO_INFO);
        }
        PlayDefinition play = found.get();

        // scope 校验（bridge 侧 NoopPlayScopeChecker 永远 false，不影响请求）
        if (scopeChecker.insufficientScope(play)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "INSUFFICIENT_SCOPE"));
        }

        Window w = resolveWindow(play, startDate, endDate);

        PlayEngine.RunResult result;
        try {
            result = engine.run(play, carId, w.start, w.end);
        } catch (PlayComputeException e) {
            log.error("play '{}' compute failed carId={}: {}", play.name(), carId, e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "PLAY_COMPUTE_ERROR"));
        } catch (DataAccessException e) {
            // SQL 层错误（列名 typo / 查询超时 / 连接断）：fixtures 测不到 SQL 真伪
            // （play-compat CI 才测），必须显式接住 —— 否则裸冒泡成 Spring 默认 500 页。
            log.error("play '{}' sql failed carId={}: {}", play.name(), carId, e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "PLAY_SQL_ERROR"));
        }

        if (result instanceof PlayEngine.Unscored u) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("play", play.name());
            data.put("scored", false);
            data.put("sample", u.sample());
            data.put("min_sample", u.minSample());
            data.put("window_days", u.windowDays());
            return ResponseEntity.ok(Map.of("data", data));
        }

        PlayEngine.Scored scored = (PlayEngine.Scored) result;
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("play", play.name());
        data.put("scored", true);
        data.put("window_days", scored.windowDays());
        try {
            for (OutputField f : play.outputFields()) {
                data.put(f.name(), outputValue(play, f, scored.vars()));
            }
        } catch (PlayComputeException e) {
            log.error("play '{}' output mapping failed carId={}: {}", play.name(), carId, e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "PLAY_COMPUTE_ERROR"));
        }
        return ResponseEntity.ok(Map.of("data", data));
    }

    // ====== helpers ======

    private Optional<PlayDefinition> lookupPlay(String playName) {
        if (playName == null || !PLAY_NAME_RE.matcher(playName).matches()) {
            return Optional.empty();
        }
        return registry.find(playName);
    }

    /** audit target 不写入未经验证的 playName 原文（防日志注入），非法名记 "_invalid"。 */
    private static String safeAuditName(String playName) {
        return playName != null && PLAY_NAME_RE.matcher(playName).matches() ? playName : "_invalid";
    }

    private Object outputValue(PlayDefinition play, OutputField f, Map<String, Object> vars) {
        if (!vars.containsKey(f.from())) {
            throw new PlayComputeException(
                    "output field '" + f.name() + "' from '" + f.from() + "' 不在 compute 结果里");
        }
        Object v = vars.get(f.from());
        return switch (f.type()) {
            case "number" -> {
                if (!(v instanceof Number)) {
                    throw new PlayComputeException(
                            "output field '" + f.name() + "' 声明 number 但运行时值非数字");
                }
                yield v;
            }
            case "string" -> io.teslamate.play.PlayComputeEngine.formatValue(v);
            case "object" -> {
                if (!(v instanceof Map)) {
                    throw new PlayComputeException(
                            "output field '" + f.name() + "' 声明 object 但运行时值非 object");
                }
                yield v;
            }
            default -> throw new PlayComputeException("bad output type " + f.type());
        };
    }

    // ====== 时间窗 ======

    private record Window(LocalDateTime start, LocalDateTime end) {}

    private static Window resolveWindow(PlayDefinition play, String startDate, String endDate) {
        // now 截到 HOUR bucket：缺省请求 ETag 在 1 小时内稳定（照 score-card F7）。
        Instant nowBucket = Instant.now().truncatedTo(ChronoUnit.HOURS);
        LocalDateTime start =
                parseLdt(startDate, nowBucket.minusSeconds(86400L * play.defaultDays()), false);
        LocalDateTime end = parseLdt(endDate, nowBucket, true);
        if (start.isAfter(end)) {
            LocalDateTime tmp = start;
            start = end;
            end = tmp;
        }
        long requestedDays = ChronoUnit.DAYS.between(start, end);
        if (requestedDays > MAX_WINDOW_DAYS) {
            start = end.minusDays(MAX_WINDOW_DAYS);
        }
        return new Window(start, end);
    }

    /** 与 driving-score 同 parser：支持 ISO offset datetime / Instant / 纯日期。 */
    static LocalDateTime parseLdt(String s, Instant fallback, boolean endOfDay) {
        Instant i = fallback;
        if (s != null && !s.isBlank()) {
            try {
                i = OffsetDateTime.parse(s).toInstant();
            } catch (DateTimeParseException e) {
                try {
                    i = Instant.parse(s);
                } catch (DateTimeParseException e2) {
                    try {
                        LocalDate d = LocalDate.parse(s);
                        i =
                                endOfDay
                                        ? d.atTime(23, 59, 59, 999_999_999)
                                                .atOffset(ZoneOffset.UTC)
                                                .toInstant()
                                        : d.atStartOfDay().atOffset(ZoneOffset.UTC).toInstant();
                    } catch (DateTimeParseException e3) {
                        // keep fallback
                    }
                }
            }
        }
        return i.atOffset(ZoneOffset.UTC).toLocalDateTime();
    }

    // ====== ETag（runPlay JSON 端点无 ETag；保留 stableHash 供测试引用）======

    /**
     * vars map 稳定序列化 hash（供测试验证用）。
     *
     * <p>按 key 排序后拼 "key=value" 字符串，再取 SHA-256 前 16 字节（32 hex）。
     */
    static String stableHash(Map<String, Object> vars) {
        StringBuilder sb = new StringBuilder();
        vars.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> sb.append(e.getKey()).append('=').append(e.getValue()).append(';'));
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 16; i++) hex.append(String.format("%02x", digest[i]));
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    // unused — kept for ZoneId reference in tests
    private static final ZoneId CARD_TZ = ZoneId.of("Asia/Shanghai");
}
