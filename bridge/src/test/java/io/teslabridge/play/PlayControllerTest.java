package io.teslabridge.play;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.teslamate.play.CarWhitelistProvider;
import io.teslamate.play.PlayAuditLogger;
import io.teslamate.play.PlayCardRenderer;
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
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * PlayController 五件套行为（bridge 适配版）：Unscored window_days、ETag 数据修订、
 * card.png 兜底卡、304 缓存、stableHash 幂等性。
 *
 * <p>bridge 无 AuditService / OAuth scope（StaticTokenFilter 层面），
 * 测试仅覆盖引擎修复带来的行为变化，不重复 registry 级别的加载测试。
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
            card:
              template: card.svg.tmpl
            """;

    private static final String CARD_SVG =
            "<svg xmlns=\"http://www.w3.org/2000/svg\"><text>${score}</text></svg>";

    private final PlayRegistry registry = mock(PlayRegistry.class);
    private final PlayEngine engine = mock(PlayEngine.class);
    private final PlayCardRenderer renderer = mock(PlayCardRenderer.class);
    // bridge 三接口实现：NoopPlayScopeChecker(永远false) + LogPlayAuditLogger + EnvCarWhitelistProvider
    // 测试中用简单 lambda stub，与真实 bridge 实现语义一致
    private final CarWhitelistProvider whitelist = carId -> false; // allow all
    private final PlayAuditLogger audit = path -> {}; // no-op
    private final PlayScopeChecker scope = play -> false; // always allow
    private final PlayController controller =
            new PlayController(registry, engine, renderer, whitelist, audit, scope);

    private final PlayDefinition play =
            PlayLoader.load(
                    "test-play",
                    PLAY_YAML.getBytes(StandardCharsets.UTF_8),
                    CARD_SVG.getBytes(StandardCharsets.UTF_8));

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

    // ====== renderPlayCard：ETag 数据修订 ======

    @Test
    @SuppressWarnings("unchecked")
    void renderPlayCard_scored_etagReflectsDataRevision() {
        // 同一窗口，第二次 engine.run 返不同 vars → ETag 变化 → 客户端拿新卡。
        Map<String, Object> vars1 = new LinkedHashMap<>();
        vars1.put("score", 86.0);
        Map<String, Object> vars2 = new LinkedHashMap<>();
        vars2.put("score", 90.0);

        when(engine.run(eq(play), eq(2L), any(), any()))
                .thenReturn(new PlayEngine.Scored(vars1, 30))
                .thenReturn(new PlayEngine.Scored(vars2, 30));
        when(engine.carLabel(2L)).thenReturn(new PlayEngine.CarLabel("小特", "3", "SR+"));
        when(renderer.render(eq(play), any(Map.class))).thenReturn(new byte[] {1});

        ResponseEntity<byte[]> first =
                controller.renderPlayCard(2L, "test-play", "2026-05-01", "2026-05-31", null);
        ResponseEntity<byte[]> second =
                controller.renderPlayCard(2L, "test-play", "2026-05-01", "2026-05-31", null);

        assertThat(first.getHeaders().getETag()).isNotNull();
        assertThat(second.getHeaders().getETag()).isNotNull();
        assertThat(first.getHeaders().getETag()).isNotEqualTo(second.getHeaders().getETag());
    }

    @Test
    @SuppressWarnings("unchecked")
    void renderPlayCard_etagChangesWhenCarNameChanges() {
        // 同窗口同数据，车名不同 → ETag 应不同（ETag 修复验证）。
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("score", 86.0);
        when(engine.run(eq(play), eq(2L), any(), any()))
                .thenReturn(new PlayEngine.Scored(vars, 30))
                .thenReturn(new PlayEngine.Scored(vars, 30));
        when(engine.carLabel(2L))
                .thenReturn(new PlayEngine.CarLabel("小特", "3", "SR+"))
                .thenReturn(new PlayEngine.CarLabel("大特", "3", "SR+"));
        when(renderer.render(eq(play), any(Map.class))).thenReturn(new byte[] {1});

        ResponseEntity<byte[]> first =
                controller.renderPlayCard(2L, "test-play", "2026-05-01", "2026-05-31", null);
        ResponseEntity<byte[]> second =
                controller.renderPlayCard(2L, "test-play", "2026-05-01", "2026-05-31", null);

        assertThat(first.getHeaders().getETag()).isNotNull();
        assertThat(second.getHeaders().getETag()).isNotNull();
        assertThat(first.getHeaders().getETag())
                .as("车名改变后 ETag 应不同")
                .isNotEqualTo(second.getHeaders().getETag());
    }

    @Test
    void renderPlayCard_insufficientSample_unscoredCardReturns200WithEtag() {
        when(engine.run(eq(play), eq(2L), any(), any()))
                .thenReturn(new PlayEngine.Unscored(7, 50, 30));
        when(renderer.renderInsufficient(eq(play), eq(7), eq(50))).thenReturn(new byte[] {1, 2});
        ResponseEntity<byte[]> resp =
                controller.renderPlayCard(2L, "test-play", null, null, null);
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getHeaders().getContentType()).isEqualTo(MediaType.IMAGE_PNG);
        // Unscored 卡片也应有 ETag（修复后）
        assertThat(resp.getHeaders().getETag()).isNotNull();
    }

    @Test
    void renderPlayCard_ifNoneMatchHit_returns304() {
        when(engine.run(eq(play), eq(2L), any(), any()))
                .thenReturn(new PlayEngine.Unscored(7, 50, 30));
        when(renderer.renderInsufficient(eq(play), anyInt(), anyInt())).thenReturn(new byte[] {1});
        ResponseEntity<byte[]> first =
                controller.renderPlayCard(2L, "test-play", "2026-05-01", "2026-05-31", null);
        String etag = first.getHeaders().getETag();
        assertThat(etag).isNotNull();

        // 带同一 ETag 再请求 → 304
        when(engine.run(eq(play), eq(2L), any(), any()))
                .thenReturn(new PlayEngine.Unscored(7, 50, 30));
        ResponseEntity<byte[]> second =
                controller.renderPlayCard(2L, "test-play", "2026-05-01", "2026-05-31", etag);
        assertThat(second.getStatusCode().value()).isEqualTo(304);
        assertThat(second.getBody()).isNull();
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

    @Test
    void etag_changesWithDataRevision() {
        java.time.LocalDateTime s = java.time.LocalDateTime.of(2026, 5, 1, 0, 0);
        java.time.LocalDateTime e = java.time.LocalDateTime.of(2026, 5, 31, 0, 0);
        assertThat(PlayController.computeEtag(play, 2L, s, e, "rev-a"))
                .isNotEqualTo(PlayController.computeEtag(play, 2L, s, e, "rev-b"));
    }

    // ====== renderPlayCard：SQL 错误兜底 ======

    @Test
    void renderPlayCard_sqlError_returns200UnavailableCardNoStore() {
        when(engine.run(eq(play), eq(2L), any(), any()))
                .thenThrow(new org.springframework.dao.QueryTimeoutException("5s timeout"));
        byte[] png = new byte[] {7, 7, 7};
        when(renderer.renderUnavailable(eq(play))).thenReturn(png);
        ResponseEntity<byte[]> resp =
                controller.renderPlayCard(2L, "test-play", null, null, null);
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getHeaders().getContentType()).isEqualTo(MediaType.IMAGE_PNG);
        assertThat(resp.getBody()).isEqualTo(png);
        assertThat(resp.getHeaders().getCacheControl()).contains("no-store");
        assertThat(resp.getHeaders().getETag()).isNull();
    }
}
