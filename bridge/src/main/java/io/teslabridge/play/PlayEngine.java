package io.teslabridge.play;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.ColumnMapRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 玩法 SQL 执行 + compute 流水线编排（设计定稿 §2.2）。
 *
 * <p><b>不复用共享 bean</b>：用 {@code teslamateJdbc} 的 DataSource 新建专属
 * {@link JdbcTemplate}（{@code setQueryTimeout(5)}、{@code setMaxRows(100)} ——
 * 比共享 bean 的 15s 更紧；play SQL 是聚合查询，正常只返 1 行），外包
 * {@link NamedParameterJdbcTemplate}；整个查询跑在 readOnly
 * {@link TransactionTemplate}（{@code teslamateTx}）里。
 *
 * <p>绑定参数恒为 4 个（SQL 用不用都提供，多余的被 NamedParameter 忽略）：
 *
 * <ul>
 *   <li>{@code :car_id}（long，{@link PlaySqlGuard} 强制 SQL 必引用）
 *   <li>{@code :tz}（恒 {@code "Asia/Shanghai"}，v1 不接受用户输入）
 *   <li>{@code :start} / {@code :end}（LocalDateTime，UTC 语义与 compat controller 一致）
 * </ul>
 */
@Component
public class PlayEngine {

    /** play SQL 比共享 bean（15s）更紧的查询超时：聚合 query 5s 不出结果就该砍。 */
    static final int QUERY_TIMEOUT_SECONDS = 5;

    /** 聚合查询正常只返 1 行；100 行上限防 play SQL 误写成明细查询拖内存。 */
    static final int MAX_ROWS = 100;

    /** v1 时区锁死（不接受用户输入，杜绝 tz 注入 / 歧义）。 */
    static final String TZ = "Asia/Shanghai";

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate readOnlyTx;

    @Autowired
    public PlayEngine(
            @Qualifier("teslamateJdbc") JdbcTemplate teslamateJdbc,
            @Qualifier("teslamateTx") PlatformTransactionManager teslamateTx) {
        JdbcTemplate own =
                new JdbcTemplate(
                        Objects.requireNonNull(
                                teslamateJdbc.getDataSource(), "teslamateJdbc has no DataSource"));
        own.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
        own.setMaxRows(MAX_ROWS);
        NamedParameterJdbcTemplate named = new NamedParameterJdbcTemplate(own);
        TransactionTemplate tx = new TransactionTemplate(teslamateTx);
        tx.setReadOnly(true);
        this.jdbc = named;
        this.readOnlyTx = tx;
    }

    /** 测试构造器：直接注入 mock 的 named template + tx template。 */
    PlayEngine(NamedParameterJdbcTemplate jdbc, TransactionTemplate readOnlyTx) {
        this.jdbc = jdbc;
        this.readOnlyTx = readOnlyTx;
    }

    /** 运行结果：采样足够 → {@link Scored}（compute ctx）；不足 → {@link Unscored}。 */
    public sealed interface RunResult permits Scored, Unscored {}

    public record Scored(Map<String, Object> vars, long windowDays) implements RunResult {}

    public record Unscored(int sample, int minSample, long windowDays) implements RunResult {}

    /**
     * 查 SQL（readOnly 事务）→ min_sample gate → compute 流水线。
     *
     * <p>SQL 返回 0 行视为 sample=0 → unscored（聚合 SQL 习惯返 1 行，但 WHERE 全空窗口
     * 某些写法会 0 行）。
     */
    public RunResult run(PlayDefinition play, long carId, LocalDateTime start, LocalDateTime end) {
        long windowDays = Math.max(1L, ChronoUnit.DAYS.between(start, end));

        // TransactionTemplate.execute() 声明返回 @Nullable T；readOnly 事务正常不会回滚/取消，
        // 此处 null 仅在事务管理器主动回滚（极罕见）时出现，等价于 0 行 → unscored 路径。
        // 用 requireNonNullElse 而非裸赋值，让 sampleOf 内的 null 分支不成为"隐式路径"。
        Map<String, Object> row =
                Objects.requireNonNullElse(
                        readOnlyTx.execute(status -> queryFirstRow(play, carId, start, end)),
                        Map.of());

        int sample = sampleOf(play, row.isEmpty() ? null : row);
        if (sample < play.minSampleMin()) {
            return new Unscored(sample, play.minSampleMin(), windowDays);
        }
        return new Scored(PlayComputeEngine.run(play, row, windowDays), windowDays);
    }

    /** 查 TeslaMate cars 表拿车名 / 车型（卡片内置变量 car_name / car_model）。 */
    public CarLabel carLabel(long carId) {
        try {
            return readOnlyTx.execute(
                    status ->
                            jdbc.queryForObject(
                                    "SELECT name, model, trim_badging FROM public.cars WHERE id = :car_id",
                                    new MapSqlParameterSource("car_id", carId),
                                    (rs, n) -> {
                                        String name = rs.getString("name");
                                        String model = rs.getString("model");
                                        String trim = rs.getString("trim_badging");
                                        return new CarLabel(name, model, trim);
                                    }));
        } catch (EmptyResultDataAccessException e) {
            return new CarLabel(null, null, null);
        }
    }

    /** cars 行（可全 null —— 渲染层有占位兜底）。 */
    public record CarLabel(String name, String model, String trimBadging) {

        /** 优先用户起的名，回退 "Model 3 LR" 形态，最后 "我的特斯拉"。 */
        public String displayName() {
            if (name != null && !name.isBlank()) return name;
            StringBuilder sb = new StringBuilder();
            if (model != null && !model.isBlank()) sb.append("Model ").append(model);
            if (trimBadging != null && !trimBadging.isBlank()) {
                if (sb.length() > 0) sb.append(' ');
                sb.append(trimBadging);
            }
            return sb.length() == 0 ? "我的特斯拉" : sb.toString();
        }

        public String displayModel() {
            return model == null || model.isBlank() ? "Tesla" : "Model " + model;
        }
    }

    private Map<String, Object> queryFirstRow(
            PlayDefinition play, long carId, LocalDateTime start, LocalDateTime end) {
        MapSqlParameterSource params =
                new MapSqlParameterSource()
                        .addValue("car_id", carId)
                        .addValue("tz", TZ)
                        .addValue("start", start)
                        .addValue("end", end);
        return jdbc.query(
                play.sql(),
                params,
                rs -> {
                    if (!rs.next()) return null;
                    Map<String, Object> raw = new ColumnMapRowMapper().mapRow(rs, 0);
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (Map.Entry<String, Object> e : raw.entrySet()) {
                        row.put(e.getKey().toLowerCase(Locale.ROOT), e.getValue());
                    }
                    return row;
                });
    }

    private static int sampleOf(PlayDefinition play, Map<String, Object> row) {
        if (row == null) return 0;
        Object v = row.get(play.minSampleField().toLowerCase(Locale.ROOT));
        if (v == null) return 0;
        if (v instanceof Number n) return n.intValue();
        throw new PlayComputeException(
                "min_sample.field '" + play.minSampleField() + "' 不是数字列");
    }
}
