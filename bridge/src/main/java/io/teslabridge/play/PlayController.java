package io.teslabridge.play;

import io.teslamate.play.CarWhitelistProvider;
import io.teslamate.play.PlayAuditLogger;
import io.teslamate.play.PlayCardRenderer;
import io.teslamate.play.PlayComputeException;
import io.teslamate.play.PlayDefinition;
import io.teslamate.play.PlayDefinition.OutputField;
import io.teslamate.play.PlayEngine;
import io.teslamate.play.PlayRegistry;
import io.teslamate.play.PlayScopeChecker;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
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
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 玩法引擎 3 端点（设计定稿 §2.5，五件套照 {@code TeslamateDrivingScoreController}）：
 *
 * <pre>
 * GET /api/v1/plays                                  → listPlays（纯元数据）
 * GET /api/v1/cars/{carId}/play/{playName}           → runPlay（JSON envelope）
 * GET /api/v1/cars/{carId}/play/{playName}/card.png  → renderPlayCard（PNG）
 * </pre>
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

    /** If-None-Match 长度上限（防 CPU amplification，照 heatmap F5）。 */
    static final int IF_NONE_MATCH_MAX_LEN = 128;

    /** card.png 缓存（photo 类比 score-card 短：play 内容可热修，5 分钟）。 */
    static final Duration CACHE_MAX_AGE = Duration.ofMinutes(5);

    /** 卡片 generated_at 内置变量的时区（与 :tz 一致）。 */
    private static final ZoneId CARD_TZ = ZoneId.of("Asia/Shanghai");

    /** 404 占位体（与 SaaS 侧跨租户同语，不泄露资源存在性）。 */
    private static final Map<String, Object> NO_INFO = Map.of("data", Map.of());

    private final PlayRegistry registry;
    private final PlayEngine engine;
    private final PlayCardRenderer renderer;
    private final CarWhitelistProvider carWhitelistProvider;
    private final PlayAuditLogger auditLogger;
    private final PlayScopeChecker scopeChecker;

    public PlayController(
            PlayRegistry registry,
            PlayEngine engine,
            PlayCardRenderer renderer,
            CarWhitelistProvider carWhitelistProvider,
            PlayAuditLogger auditLogger,
            PlayScopeChecker scopeChecker) {
        this.registry = registry;
        this.engine = engine;
        this.renderer = renderer;
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
            item.put("has_card", p.hasCard());
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

    // ====== 3) GET /api/v1/cars/{carId}/play/{playName}/card.png ======

    @GetMapping("/api/v1/cars/{carId:\\d+}/play/{playName}/card.png")
    public ResponseEntity<byte[]> renderPlayCard(
            @PathVariable("carId") Long carId,
            @PathVariable("playName") String playName,
            @RequestParam(value = "start_date", required = false) String startDate,
            @RequestParam(value = "end_date", required = false) String endDate,
            @RequestHeader(value = "If-None-Match", required = false) String ifNoneMatch) {
        auditLogger.log(
                "/api/v1/cars/" + carId + "/play/" + safeAuditName(playName) + "/card.png");
        if (carWhitelistProvider.outOfWhitelist(carId)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        Optional<PlayDefinition> found = lookupPlay(playName);
        if (found.isEmpty() || !found.get().hasCard()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        PlayDefinition play = found.get();

        if (scopeChecker.insufficientScope(play)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        Window w = resolveWindow(play, startDate, endDate);

        // If-None-Match 长度 guard（防 CPU amplification）。
        // ETag 比对移到查库之后：需纳入真实数据修订因子，在此仅做长度截断，
        // 以防超大值被带入后续 matchesAnyEtag 的字符串分割。
        String safeIfNoneMatch =
                (ifNoneMatch != null && ifNoneMatch.length() > IF_NONE_MATCH_MAX_LEN)
                        ? null
                        : ifNoneMatch;

        byte[] png;
        try {
            PlayEngine.RunResult result = engine.run(play, carId, w.start, w.end);
            // ETag 在查库后基于渲染输入计算：
            //   - Scored：vars map（SQL 聚合结果 + compute 中间变量）稳定序列化 hash，
            //     数据更新后 vars 变化 → ETag 自然 bust，客户端不会拿旧卡。
            //   - Unscored：用 sample + minSample 作为数据修订因子（数据不足情况）。
            // 两种情况的 ETag 都包含 play 内容哈希（play 定义更新自动 bust）。
            // ETag 修复：Scored 卡片的 dataRevision 须纳入 carLabel + 日期桶。
            // carLabel 在 Scored 分支预拉取一次，后续渲染直接复用，避免双次 DB 查询。
            PlayEngine.CarLabel carLabelForEtag =
                    (result instanceof PlayEngine.Scored) ? engine.carLabel(carId) : null;
            String dataRevision;
            if (result instanceof PlayEngine.Scored scored) {
                // ETag 修复：dataRevision 除 vars 外还须纳入：
                //   1. carLabel 三字段（车名改变 → 卡面变 → ETag 必须 bust）
                //   2. generated_at 的「日期桶」（addBuiltinCardVars 写入的是 LocalDate 字符串，
                //      即 yyyy-MM-dd 精度；ETag 用相同粒度，同天多次请求可 304，跨天自动 bust）。
                String dateBucket = LocalDate.now(CARD_TZ).toString();
                dataRevision = stableHash(scored.vars())
                        + ":" + carLabelForEtag.name()
                        + ":" + carLabelForEtag.model()
                        + ":" + carLabelForEtag.trimBadging()
                        + ":" + dateBucket;
            } else {
                PlayEngine.Unscored u = (PlayEngine.Unscored) result;
                dataRevision = u.sample() + ":" + u.minSample();
            }
            String etag = computeEtag(play, carId, w.start, w.end, dataRevision);
            if (safeIfNoneMatch != null && matchesAnyEtag(safeIfNoneMatch, etag)) {
                return ResponseEntity.status(HttpStatus.NOT_MODIFIED)
                        .eTag(etag)
                        .cacheControl(CacheControl.maxAge(CACHE_MAX_AGE).cachePrivate())
                        .build();
            }
            if (result instanceof PlayEngine.Unscored u) {
                // 数据不足不报错：灰色「数据不足」兜底卡 HTTP 200，LLM 平台流程不中断。
                png = renderer.renderInsufficient(play, u.sample(), u.minSample());
                return ResponseEntity.ok()
                        .contentType(MediaType.IMAGE_PNG)
                        .eTag(etag)
                        .cacheControl(CacheControl.maxAge(CACHE_MAX_AGE).cachePrivate())
                        .body(png);
            } else {
                PlayEngine.Scored scored = (PlayEngine.Scored) result;
                Map<String, Object> vars = new LinkedHashMap<>(scored.vars());
                addOutputAliases(play, vars);
                // 复用 carLabelForEtag 避免二次查 DB
                addBuiltinCardVarsWithLabel(vars, carLabelForEtag, scored.windowDays());
                png = renderer.render(play, vars);
                return ResponseEntity.ok()
                        .contentType(MediaType.IMAGE_PNG)
                        .eTag(etag)
                        .cacheControl(CacheControl.maxAge(CACHE_MAX_AGE).cachePrivate())
                        .body(png);
            }
        } catch (PlayComputeException | PlayCardRenderer.PlayRenderException e) {
            log.error("play '{}' card render failed carId={}: {}", play.name(), carId, e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                    .body(
                            "{\"type\":\"render_failure\",\"title\":\"play card render failed\"}"
                                    .getBytes(StandardCharsets.UTF_8));
        } catch (DataAccessException e) {
            // SQL 层错误：聊天流里返「暂时无法生成」灰卡（200 + no-store，不缓存错误卡、
            // 不带 ETag），比裸 500 破图标体面；render 自身也挂才降级 500 problem+json。
            log.error("play '{}' card sql failed carId={}: {}", play.name(), carId, e.getMessage());
            try {
                return ResponseEntity.ok()
                        .contentType(MediaType.IMAGE_PNG)
                        .cacheControl(CacheControl.noStore())
                        .body(renderer.renderUnavailable(play));
            } catch (RuntimeException re) {
                log.error(
                        "play '{}' unavailable-card render failed carId={}: {}",
                        play.name(),
                        carId,
                        re.getMessage());
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                        .body(
                                "{\"type\":\"play_sql_error\",\"title\":\"play data query failed\"}"
                                        .getBytes(StandardCharsets.UTF_8));
            }
        }
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

    /**
     * 卡片 car_name 注入前的最大保证长度（code point 计，CJK 全角按 1 字计）。车名来自
     * 用户在 Tesla App 的自定义输入，长度不受控；SVG &lt;text&gt; 无自动换行 / 省略，
     * 不截断必然溢出画布或压到水印。模板按「最长 12 字 + …」排版（_template 注释 +
     * bridge spec §7 同步写明）。
     */
    static final int CAR_NAME_MAX_CODEPOINTS = 12;

    /**
     * 把 output 字段 alias 注入渲染 ctx（spec §5：output 字段可用于 card 模板）。
     *
     * <p>compute ctx（{@code scored.vars()}）的 key 是 SQL 列名 / compute 变量名 ——
     * <b>不含 output.name alias</b>。loader 的模板 lint（{@code PlayLoader.lintTemplate}）
     * 把 {@code output.name} 也算作合法占位符放行，于是当 {@code output.name != output.from}
     * （例 output 字段 {@code total_km} from {@code total_km_r}）时，play 能加载，但卡片
     * 模板里的 {@code ${total_km}} 在渲染期解析不到 → 每次 card 请求 500。
     *
     * <p>这里对每个 output 字段做 {@code vars[output.name] = vars[output.from]}（仅当
     * source 存在且 alias 尚未被占用，{@code putIfAbsent} 不覆盖同名 compute 变量），
     * 让模板用 {@code output.name} 或源变量名都能渲染。{@code from} 不在 ctx 的非法定义在
     * runPlay 的 outputValue 已会显式 500，这里安全跳过即可。
     */
    private static void addOutputAliases(PlayDefinition play, Map<String, Object> vars) {
        for (OutputField f : play.outputFields()) {
            if (!f.name().equals(f.from()) && vars.containsKey(f.from())) {
                vars.putIfAbsent(f.name(), vars.get(f.from()));
            }
        }
    }

    /**
     * 卡片内置变量（设计定稿 §2.3）：car_name / car_model / window_label / generated_at /
     * watermark。
     *
     * <p>{@code watermark} 来自 {@code play.card-watermark} 配置，默认空字符串（模板可选渲染）。
     */
    private void addBuiltinCardVarsWithLabel(
            Map<String, Object> vars, PlayEngine.CarLabel car, long windowDays) {
        vars.putIfAbsent("car_name", truncateDisplay(car.displayName(), CAR_NAME_MAX_CODEPOINTS));
        vars.putIfAbsent("car_model", car.displayModel());
        vars.putIfAbsent("window_label", "近 " + windowDays + " 天");
        vars.putIfAbsent("generated_at", LocalDate.now(CARD_TZ).toString());
        vars.putIfAbsent("watermark", renderer.watermark());
    }

    /** 按 code point 截断（防 surrogate pair 截半）超长部分换 '…'。 */
    static String truncateDisplay(String s, int maxCodePoints) {
        if (s == null) return "";
        if (s.codePointCount(0, s.length()) <= maxCodePoints) return s;
        return s.substring(0, s.offsetByCodePoints(0, maxCodePoints)) + "…";
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

    // ====== ETag ======

    /**
     * ETag = SHA-256(playName + carId + start + end + play 内容哈希 + dataRevision)。
     *
     * <p>{@code dataRevision} 是数据修订因子：Scored 时为 vars map 的稳定 hash（SQL 聚合结果
     * 变化即 bust），Unscored 时为 "sample:minSample"。这样数据更新后客户端不会拿旧卡。
     */
    static String computeEtag(
            PlayDefinition play, long carId, LocalDateTime start, LocalDateTime end, String dataRevision) {
        String input =
                "play-card:"
                        + play.name()
                        + ":"
                        + carId
                        + ":"
                        + start
                        + ":"
                        + end
                        + ":"
                        + play.contentSha256()
                        + ":"
                        + dataRevision;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * vars map 稳定序列化 hash（用于 ETag 数据修订因子）。
     *
     * <p>按 key 排序后拼 "key=value" 字符串，再取 SHA-256 前 16 字节（32 hex）。
     * 只需「变化可辨识」，不需密码学强度，16 字节已足够。
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

    /** If-None-Match 支持逗号列表 + 通配 '*' + W/ 弱前缀（照 heatmap F10）。 */
    static boolean matchesAnyEtag(String ifNoneMatch, String etag) {
        String bare = stripQuotesAndWeak(etag);
        for (String part : ifNoneMatch.split(",")) {
            String candidate = stripQuotesAndWeak(part.trim());
            if ("*".equals(candidate) || candidate.equals(bare)) return true;
        }
        return false;
    }

    private static String stripQuotesAndWeak(String s) {
        String v = s;
        if (v.startsWith("W/")) v = v.substring(2);
        if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) {
            v = v.substring(1, v.length() - 1);
        }
        return v;
    }
}
