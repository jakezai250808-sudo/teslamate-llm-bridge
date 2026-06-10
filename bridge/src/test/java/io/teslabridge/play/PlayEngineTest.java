package io.teslabridge.play;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.teslamate.play.PlayComputeEngine;
import io.teslamate.play.PlayComputeException;
import io.teslamate.play.PlayDefinition;
import io.teslamate.play.PlayEngine;
import io.teslamate.play.PlayExpr;
import io.teslamate.play.PlayLoadException;
import io.teslamate.play.PlayLoader;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * PlayEngine（SQL 执行编排）+ PlayComputeEngine（流水线语义）+ PlayExpr（mini 表达式）。
 *
 * <p>jdbc 用 mock（NamedParameterJdbcTemplate）；事务用真 TransactionTemplate 包 mock
 * PlatformTransactionManager —— execute 回调真实跑，commit 打在 mock 上。
 */
class PlayEngineTest {

    // ====== PlayExpr ======

    @Test
    void expr_precedenceAndParens() {
        assertThat(PlayExpr.compile("1 + 2 * 3").eval(Map.of())).isEqualTo(7.0);
        assertThat(PlayExpr.compile("(1 + 2) * 3").eval(Map.of())).isEqualTo(9.0);
        assertThat(PlayExpr.compile("-2 + 5").eval(Map.of())).isEqualTo(3.0);
    }

    @Test
    void expr_functions() {
        assertThat(PlayExpr.compile("GREATEST(1, 5, 3)").eval(Map.of())).isEqualTo(5.0);
        assertThat(PlayExpr.compile("LEAST(4, 2)").eval(Map.of())).isEqualTo(2.0);
        assertThat(PlayExpr.compile("ROUND(3.6)").eval(Map.of())).isEqualTo(4.0);
        assertThat(PlayExpr.compile("ROUND(3.14159, 2)").eval(Map.of())).isEqualTo(3.14);
    }

    @Test
    void expr_divisionByZero_returnsZero() {
        // 设计定稿 §1.1：除零返 0 + WARN，保证卡片可渲染。
        assertThat(PlayExpr.compile("5 / 0").eval(Map.of())).isEqualTo(0.0);
        assertThat(PlayExpr.compile("5 / x").eval(Map.of("x", 0))).isEqualTo(0.0);
    }

    @Test
    void expr_identifierResolution() {
        assertThat(PlayExpr.compile("a + b").eval(Map.of("a", 2, "b", 3.5))).isEqualTo(5.5);
    }

    @Test
    void expr_unknownIdentifier_throwsComputeException() {
        PlayExpr e = PlayExpr.compile("missing_col + 1");
        assertThatThrownBy(() -> e.eval(Map.of()))
                .isInstanceOf(PlayComputeException.class)
                .hasMessageContaining("missing_col");
    }

    @Test
    void expr_nullValue_treatedAsZero() {
        // SQL NULL 列按 0 处理（与除零同"卡片可渲染"哲学），不 500。
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("x", null);
        assertThat(PlayExpr.compile("x + 1").eval(ctx)).isEqualTo(1.0);
    }

    @Test
    void expr_rejectsUnknownFunctionAndBadSyntax() {
        assertThatThrownBy(() -> PlayExpr.compile("EVIL(1)"))
                .isInstanceOf(PlayLoadException.class);
        assertThatThrownBy(() -> PlayExpr.compile("1 +")).isInstanceOf(PlayLoadException.class);
        assertThatThrownBy(() -> PlayExpr.compile("a; b")).isInstanceOf(PlayLoadException.class);
        assertThatThrownBy(() -> PlayExpr.compile("a > b")).isInstanceOf(PlayLoadException.class);
    }

    // ====== PlayComputeEngine 流水线 ======

    private static final String PIPELINE_YAML =
            """
            schema_version: 1
            name: pipe-play
            title: 流水线测试
            description: test
            sql: "SELECT COUNT(*) AS sample_points FROM positions WHERE car_id = :car_id"
            min_sample: { field: sample_points, min: 10 }
            compute:
              - var: ratio
                expr: "night_points / sample_points"
              - var: score
                expr: "LEAST(100, ROUND(ratio * 250))"
              - var: persona_key
                level:
                  input: score
                  thresholds:
                    - { lt: 20, label: early_bird }
                    - { lt: 60, label: balanced }
                    - { label: night_owl }
              - var: persona
                lookup:
                  key: persona_key
                  table: personas
                  default: { name: 神秘车主 }
              - var: summary
                template: "${score} 分，${window_days} 天，type=${persona_key}"
            tables:
              personas:
                night_owl: { name: 夜猫子 }
                early_bird: { name: 早起鸟 }
            output:
              fields:
                - { name: score, from: score, type: number }
                - { name: persona, from: persona, type: object }
                - { name: summary, from: summary, type: string }
            """;

    private static PlayDefinition pipelinePlay() {
        return PlayLoader.load(
                "pipe-play", PIPELINE_YAML.getBytes(StandardCharsets.UTF_8), null);
    }

    @Test
    void compute_fullPipeline_nightOwl() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("sample_points", 1000);
        row.put("night_points", 400);
        Map<String, Object> ctx = PlayComputeEngine.run(pipelinePlay(), row, 30);

        assertThat((Double) ctx.get("score")).isEqualTo(100.0);
        assertThat(ctx.get("persona_key")).isEqualTo("night_owl");
        @SuppressWarnings("unchecked")
        Map<String, String> persona = (Map<String, String>) ctx.get("persona");
        assertThat(persona).containsEntry("name", "夜猫子");
        // 数字格式化：100.0 → "100"、window_days 30.0 → "30"
        assertThat(ctx.get("summary")).isEqualTo("100 分，30 天，type=night_owl");
    }

    @Test
    void compute_levelThresholds_firstHitWins() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("sample_points", 1000);
        row.put("night_points", 40); // ratio=0.04 → score=10 → <20 early_bird
        Map<String, Object> ctx = PlayComputeEngine.run(pipelinePlay(), row, 30);
        assertThat(ctx.get("persona_key")).isEqualTo("early_bird");
        assertThat((Double) ctx.get("score")).isCloseTo(10.0, within(0.001));
    }

    @Test
    void compute_lookupMiss_fallsBackToDefault() {
        // balanced 不在 personas 表里 → default
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("sample_points", 1000);
        row.put("night_points", 160); // ratio=0.16 → score=40 → balanced
        Map<String, Object> ctx = PlayComputeEngine.run(pipelinePlay(), row, 30);
        assertThat(ctx.get("persona_key")).isEqualTo("balanced");
        @SuppressWarnings("unchecked")
        Map<String, String> persona = (Map<String, String>) ctx.get("persona");
        assertThat(persona).containsEntry("name", "神秘车主");
    }

    @Test
    void compute_unknownTemplateVar_throws() {
        assertThatThrownBy(() -> PlayComputeEngine.substitute("${nope}", Map.of()))
                .isInstanceOf(PlayComputeException.class)
                .hasMessageContaining("nope");
    }

    @Test
    void formatValue_trimsTrailingZero() {
        assertThat(PlayComputeEngine.formatValue(86.0)).isEqualTo("86");
        assertThat(PlayComputeEngine.formatValue(3.25)).isEqualTo("3.25");
        assertThat(PlayComputeEngine.formatValue("abc")).isEqualTo("abc");
    }

    // ====== PlayEngine.run（mock jdbc）======

    @SuppressWarnings("unchecked")
    private PlayEngine engineReturningRow(Map<String, Object> row) {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        when(jdbc.query(
                        anyString(),
                        any(SqlParameterSource.class),
                        ArgumentMatchers.<ResultSetExtractor<Map<String, Object>>>any()))
                .thenReturn(row);
        PlatformTransactionManager tm = mock(PlatformTransactionManager.class);
        when(tm.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        return new PlayEngine(jdbc, new TransactionTemplate(tm));
    }

    @Test
    void run_sampleBelowMin_returnsUnscored() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("sample_points", 5);
        row.put("night_points", 0);
        PlayEngine engine = engineReturningRow(row);
        PlayEngine.RunResult r =
                engine.run(
                        pipelinePlay(),
                        2L,
                        LocalDateTime.of(2026, 5, 1, 0, 0),
                        LocalDateTime.of(2026, 5, 31, 0, 0));
        assertThat(r).isInstanceOf(PlayEngine.Unscored.class);
        PlayEngine.Unscored u = (PlayEngine.Unscored) r;
        assertThat(u.sample()).isEqualTo(5);
        assertThat(u.minSample()).isEqualTo(10);
        assertThat(u.windowDays()).isEqualTo(30);
    }

    @Test
    void run_zeroRows_returnsUnscoredSampleZero() {
        PlayEngine engine = engineReturningRow(null);
        PlayEngine.RunResult r =
                engine.run(
                        pipelinePlay(),
                        2L,
                        LocalDateTime.of(2026, 5, 1, 0, 0),
                        LocalDateTime.of(2026, 5, 31, 0, 0));
        assertThat(r).isInstanceOf(PlayEngine.Unscored.class);
        assertThat(((PlayEngine.Unscored) r).sample()).isZero();
    }

    @Test
    void run_enoughSample_returnsScoredWithComputedVars() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("sample_points", 1000);
        row.put("night_points", 400);
        PlayEngine engine = engineReturningRow(row);
        PlayEngine.RunResult r =
                engine.run(
                        pipelinePlay(),
                        2L,
                        LocalDateTime.of(2026, 5, 1, 0, 0),
                        LocalDateTime.of(2026, 5, 31, 0, 0));
        assertThat(r).isInstanceOf(PlayEngine.Scored.class);
        PlayEngine.Scored s = (PlayEngine.Scored) r;
        assertThat((Double) s.vars().get("score")).isEqualTo(100.0);
        assertThat(s.windowDays()).isEqualTo(30);
    }

    @Test
    void carLabel_displayName_fallbackChain() {
        assertThat(new PlayEngine.CarLabel("小特", "3", "SR+").displayName()).isEqualTo("小特");
        assertThat(new PlayEngine.CarLabel(null, "3", "SR+").displayName()).isEqualTo("Model 3 SR+");
        assertThat(new PlayEngine.CarLabel(null, null, null).displayName()).isEqualTo("我的特斯拉");
        assertThat(new PlayEngine.CarLabel(null, "Y", null).displayModel()).isEqualTo("Model Y");
        assertThat(new PlayEngine.CarLabel(null, null, null).displayModel()).isEqualTo("Tesla");
    }
}
