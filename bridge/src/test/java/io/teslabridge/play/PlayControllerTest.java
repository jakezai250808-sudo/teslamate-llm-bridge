package io.teslabridge.play;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.teslamate.play.CarWhitelistProvider;
import io.teslamate.play.PlayAuditLogger;
import io.teslamate.play.PlayDefinition;
import io.teslamate.play.PlayEngine;
import io.teslamate.play.PlayLoader;
import io.teslamate.play.PlayRegistry;
import io.teslamate.play.PlayScopeChecker;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

/**
 * PlayController 行为（bridge 开源版）：Unscored window_days、Scored fields、stableHash 幂等性。
 *
 * <p>bridge 版无 SVG 卡片渲染端点（该能力仅活在 SaaS 私有层）。测试覆盖 JSON 端点行为。
 */
class PlayControllerTest {

    private static final String PLAY_YAML =
            """
            schema_version: 1
            name: test-play
            title: 测试玩法
            description: test
            sql: "SELECT COUNT(*) AS sample_points FROM positions WHERE car_id = :car_id"
            min_sample: { field: sample_points, min: 50 }
            compute:
              - var: score
                expr: "sample_points / 10"
            output:
              fields:
                - { name: score, from: score, type: number }
            """;

    private final PlayRegistry registry = mock(PlayRegistry.class);
    private final PlayEngine engine = mock(PlayEngine.class);
    // bridge 三接口实现：NoopPlayScopeChecker(永远false) + LogPlayAuditLogger + EnvCarWhitelistProvider
    // 测试中用简单 lambda stub，与真实 bridge 实现语义一致
    private final CarWhitelistProvider whitelist = carId -> false; // allow all
    private final PlayAuditLogger audit = path -> {}; // no-op
    private final PlayScopeChecker scope = play -> false; // always allow
    private final PlayController controller =
            new PlayController(registry, engine, whitelist, audit, scope);

    private final PlayDefinition play =
            PlayLoader.load(
                    "test-play",
                    PLAY_YAML.getBytes(StandardCharsets.UTF_8));

    @BeforeEach
    void setup() {
        when(registry.find("test-play")).thenReturn(Optional.of(play));
    }

    // ====== runPlay：Unscored 分支补 window_days ======

    @Test
    @SuppressWarnings("unchecked")
    void runPlay_insufficientSample_unscoredEnvelopeContainsWindowDays() {
        when(engine.run(eq(play), eq(2L), any(), any()))
                .thenReturn(new PlayEngine.Unscored(7, 50, 30));
        ResponseEntity<Map<String, Object>> resp = controller.runPlay(2L, "test-play", null, null);
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        Map<String, Object> data = (Map<String, Object>) resp.getBody().get("data");
        assertThat(data)
                .containsEntry("play", "test-play")
                .containsEntry("scored", false)
                .containsEntry("sample", 7)
                .containsEntry("min_sample", 50)
                .containsEntry("window_days", 30L);
    }

    @Test
    @SuppressWarnings("unchecked")
    void runPlay_scored_returnsOutputFieldsWithWindowDays() {
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("sample_points", 860);
        vars.put("score", 86.0);
        when(engine.run(eq(play), eq(2L), any(), any()))
                .thenReturn(new PlayEngine.Scored(vars, 30));
        ResponseEntity<Map<String, Object>> resp = controller.runPlay(2L, "test-play", null, null);
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        Map<String, Object> data = (Map<String, Object>) resp.getBody().get("data");
        assertThat(data)
                .containsEntry("window_days", 30L)
                .containsEntry("score", 86.0);
    }

    // ====== stableHash 幂等性 ======

    @Test
    void stableHash_deterministicAndChangesWithVars() {
        Map<String, Object> vars1 = new LinkedHashMap<>();
        vars1.put("score", 86.0);
        vars1.put("total_km", 1200.0);
        Map<String, Object> vars2 = new LinkedHashMap<>();
        vars2.put("score", 87.0);
        vars2.put("total_km", 1200.0);
        // 相同 vars → 相同 hash（幂等）
        assertThat(PlayController.stableHash(vars1)).isEqualTo(PlayController.stableHash(vars1));
        // 数据变化 → hash 变化
        assertThat(PlayController.stableHash(vars1)).isNotEqualTo(PlayController.stableHash(vars2));
    }

    // ====== listPlays：不再含 has_card 字段 ======

    @Test
    @SuppressWarnings("unchecked")
    void listPlays_doesNotContainHasCard() {
        when(registry.all()).thenReturn(java.util.List.of(play));
        ResponseEntity<Map<String, Object>> resp = controller.listPlays();
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        Map<String, Object> outer = resp.getBody();
        Map<String, Object> dataMap = (Map<String, Object>) outer.get("data");
        java.util.List<Map<String, Object>> plays =
                (java.util.List<Map<String, Object>>) dataMap.get("plays");
        assertThat(plays).hasSize(1);
        assertThat(plays.get(0)).doesNotContainKey("has_card");
    }
}
