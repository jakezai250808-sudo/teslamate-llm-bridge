package io.teslabridge.play;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import io.teslamate.play.PlayComputeEngine;
import io.teslamate.play.PlayDefinition;
import io.teslamate.play.PlayLoader;
import io.teslamate.play.PlayRegistry;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * PlayRegistry 扫描 + PlayLoader 校验：坏 play 跳过不挡启动、SQL 门禁、
 * PLAYS_DIR 加载、_template 文档目录 + fixtures.yaml 契约可执行。
 *
 * <p>SVG card 模板已从开源版移除；生图通过接口二（AGENTS.md）完成。
 */
class PlayRegistryTest {

    @TempDir Path tmp;

    private static final String VALID_YAML =
            """
            schema_version: 1
            name: %s
            title: 测试玩法
            emoji: "🦉"
            description: test play
            scope: read:drives
            params: { default_days: 30 }
            sql: "SELECT COUNT(*) AS sample_points FROM positions WHERE car_id = :car_id AND date BETWEEN :start AND :end"
            min_sample: { field: sample_points, min: 50 }
            compute:
              - var: score
                expr: "LEAST(100, sample_points / 10)"
            output:
              fields:
                - { name: score, from: score, type: number }
            """;

    private void writePlay(String dirName, String yaml) throws IOException {
        Path dir = tmp.resolve(dirName);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("play.yaml"), yaml);
    }

    private PlayRegistry registry() {
        return new PlayRegistry(tmp.toString());
    }

    // ====== 加载 + 跳过策略 ======

    @Test
    void validPlay_loads() throws IOException {
        writePlay("good-play", VALID_YAML.formatted("good-play"));
        PlayRegistry reg = registry();
        assertThat(reg.find("good-play")).isPresent();
        PlayDefinition def = reg.find("good-play").orElseThrow();
        assertThat(def.title()).isEqualTo("测试玩法");
        assertThat(def.defaultDays()).isEqualTo(30);
        assertThat(def.minSampleMin()).isEqualTo(50);
        assertThat(def.contentSha256()).hasSize(64);
    }

    @Test
    void brokenYaml_skippedWithoutBlockingOthers() throws IOException {
        writePlay("bad-yaml", "schema_version: [unclosed");
        writePlay("good-play", VALID_YAML.formatted("good-play"));
        PlayRegistry reg = registry();
        assertThat(reg.find("bad-yaml")).isEmpty();
        assertThat(reg.find("good-play")).isPresent();
    }

    @Test
    void sqlWithoutCarId_rejected() throws IOException {
        String yaml =
                VALID_YAML
                        .formatted("no-car-id")
                        .replace(
                                "WHERE car_id = :car_id AND date BETWEEN :start AND :end",
                                "WHERE date BETWEEN :start AND :end");
        writePlay("no-car-id", yaml);
        assertThat(registry().find("no-car-id")).isEmpty();
    }

    @Test
    void sqlCarIdOnlyInComment_rejected() throws IOException {
        // :car_id 只出现在注释里 —— 注释先剥再查，骗不过门禁。
        String yaml =
                VALID_YAML
                        .formatted("comment-car-id")
                        .replace(
                                "WHERE car_id = :car_id AND date BETWEEN :start AND :end",
                                "WHERE date BETWEEN :start AND :end -- car_id = :car_id");
        writePlay("comment-car-id", yaml);
        assertThat(registry().find("comment-car-id")).isEmpty();
    }

    @Test
    void sqlCarIdTriviallyTruePredicate_rejected() throws IOException {
        // :car_id = :car_id 恒真，不构成租户过滤 —— 会聚合全部车主数据，必须拒。
        String yaml =
                VALID_YAML
                        .formatted("trivial-car-id")
                        .replace(
                                "WHERE car_id = :car_id AND date BETWEEN :start AND :end",
                                "WHERE :car_id = :car_id AND date BETWEEN :start AND :end");
        writePlay("trivial-car-id", yaml);
        assertThat(registry().find("trivial-car-id")).isEmpty();
    }

    @Test
    void sqlCarIdFilterOnlyInStringLiteral_rejected() throws IOException {
        // 'car_id = :car_id' 是字符串字面量里的假比较，对 positions 无任何 car_id 过滤
        // → 会聚合全部车主数据跨租户泄露。门禁剥字符串字面量后必须拒载。
        String yaml =
                VALID_YAML
                        .formatted("literal-car-id")
                        .replace(
                                "WHERE car_id = :car_id AND date BETWEEN :start AND :end",
                                "WHERE 'car_id = :car_id' <> '' AND date BETWEEN :start AND :end");
        writePlay("literal-car-id", yaml);
        assertThat(registry().find("literal-car-id")).isEmpty();
    }

    @Test
    void sqlRealCarIdFilterWithBenignStringLiteral_accepted() throws IOException {
        // 真过滤仍在（WHERE car_id = :car_id），SELECT 里另有个无关字符串字面量 ——
        // 剥字面量不应误伤真过滤，必须放行。
        String yaml =
                VALID_YAML
                        .formatted("literal-ok")
                        .replace(
                                "SELECT COUNT(*) AS sample_points FROM positions",
                                "SELECT COUNT(*) AS sample_points, 'car_id = :car_id' AS label FROM positions");
        writePlay("literal-ok", yaml);
        assertThat(registry().find("literal-ok")).isPresent();
    }

    @Test
    void sqlTableQualifiedCarIdFilter_accepted() throws IOException {
        String yaml =
                VALID_YAML
                        .formatted("qualified-car-id")
                        .replace(
                                "WHERE car_id = :car_id AND date BETWEEN :start AND :end",
                                "WHERE positions.car_id = :car_id AND date BETWEEN :start AND :end");
        writePlay("qualified-car-id", yaml);
        assertThat(registry().find("qualified-car-id")).isPresent();
    }

    @Test
    void sqlWithWriteKeyword_rejected() throws IOException {
        String yaml =
                VALID_YAML
                        .formatted("evil-sql")
                        .replace(
                                "SELECT COUNT(*) AS sample_points FROM positions",
                                "SELECT COUNT(*) AS sample_points, (DELETE FROM cars) FROM positions");
        writePlay("evil-sql", yaml);
        assertThat(registry().find("evil-sql")).isEmpty();
    }

    @Test
    void sqlWithUnion_rejected() throws IOException {
        // UNION 的 second leg 不受 car_id 约束，加载期应直接拒绝（跨数据泄露防线）。
        String yaml =
                VALID_YAML
                        .formatted("union-sql")
                        .replace(
                                "WHERE car_id = :car_id AND date BETWEEN :start AND :end",
                                "WHERE car_id = :car_id AND date BETWEEN :start AND :end"
                                        + " UNION ALL SELECT 0, 0");
        writePlay("union-sql", yaml);
        assertThat(registry().find("union-sql")).isEmpty();
    }

    @Test
    void sqlWithExcept_rejected() throws IOException {
        String yaml =
                VALID_YAML
                        .formatted("except-sql")
                        .replace(
                                "WHERE car_id = :car_id AND date BETWEEN :start AND :end",
                                "WHERE car_id = :car_id AND date BETWEEN :start AND :end"
                                        + " EXCEPT SELECT 0");
        writePlay("except-sql", yaml);
        assertThat(registry().find("except-sql")).isEmpty();
    }

    @Test
    void sqlWithSemicolon_rejected() throws IOException {
        String yaml =
                VALID_YAML
                        .formatted("multi-stmt")
                        .replace(":start AND :end", ":start AND :end ; SELECT 1");
        writePlay("multi-stmt", yaml);
        assertThat(registry().find("multi-stmt")).isEmpty();
    }

    @Test
    void nameDirMismatch_rejected() throws IOException {
        writePlay("dir-a", VALID_YAML.formatted("name-b"));
        // registry 同时扫 classpath（repo plays/ 真实玩法），不能断言 size()==0，
        // 只断言错配目录的两个名字都没被注册。
        PlayRegistry reg = registry();
        assertThat(reg.find("dir-a")).isEmpty();
        assertThat(reg.find("name-b")).isEmpty();
    }

    @Test
    void underscoreDir_skipped() throws IOException {
        writePlay("_wip", VALID_YAML.formatted("_wip"));
        assertThat(registry().find("_wip")).isEmpty();
    }

    @Test
    void unknownRootField_rejected() throws IOException {
        writePlay("extra-field", VALID_YAML.formatted("extra-field") + "\nhacks: true\n");
        assertThat(registry().find("extra-field")).isEmpty();
    }

    @Test
    void scopeOtherThanReadDrives_rejected() throws IOException {
        writePlay(
                "bad-scope",
                VALID_YAML.formatted("bad-scope").replace("read:drives", "read:all"));
        assertThat(registry().find("bad-scope")).isEmpty();
    }

    @Test
    void levelWithoutFallback_rejected() throws IOException {
        String yaml =
                """
                schema_version: 1
                name: no-fallback
                title: t
                description: d
                sql: "SELECT 1 AS sample_points FROM positions WHERE car_id = :car_id"
                min_sample: { field: sample_points, min: 1 }
                compute:
                  - var: tier
                    level:
                      input: sample_points
                      thresholds:
                        - { lt: 10, label: low }
                        - { lt: 20, label: high }
                output:
                  fields: [ { name: tier, from: tier, type: string } ]
                """;
        writePlay("no-fallback", yaml);
        assertThat(registry().find("no-fallback")).isEmpty();
    }

    @Test
    void lookupWithoutDefault_rejected() throws IOException {
        String yaml =
                """
                schema_version: 1
                name: no-default
                title: t
                description: d
                sql: "SELECT 'x' AS k, 1 AS sample_points FROM positions WHERE car_id = :car_id"
                min_sample: { field: sample_points, min: 1 }
                compute:
                  - var: hit
                    lookup: { key: k, table: t1 }
                tables:
                  t1:
                    x: { name: y }
                output:
                  fields: [ { name: hit, from: hit, type: object } ]
                """;
        writePlay("no-default", yaml);
        assertThat(registry().find("no-default")).isEmpty();
    }

    // ====== compute template 步骤 lint ======

    @Test
    void computeTemplate_cardOnlyBuiltin_rejected() throws IOException {
        // car_name 等内置变量仅 SaaS 私有层 card 路径注入；compute 引用 = 每次请求 500，加载期拒。
        String yaml =
                VALID_YAML
                        .formatted("bad-compute-tmpl")
                        .replace(
                                "compute:\n"
                                        + "  - var: score\n"
                                        + "    expr: \"LEAST(100, sample_points / 10)\"",
                                "compute:\n"
                                        + "  - var: score\n"
                                        + "    expr: \"LEAST(100, sample_points / 10)\"\n"
                                        + "  - var: summary\n"
                                        + "    template: \"${car_name} 得了 ${score} 分\"");
        writePlay("bad-compute-tmpl", yaml);
        assertThat(registry().find("bad-compute-tmpl")).isEmpty();
    }

    @Test
    void computeTemplate_windowDaysBuiltin_accepted() throws IOException {
        String yaml =
                VALID_YAML
                        .formatted("good-compute-tmpl")
                        .replace(
                                "compute:\n"
                                        + "  - var: score\n"
                                        + "    expr: \"LEAST(100, sample_points / 10)\"",
                                "compute:\n"
                                        + "  - var: score\n"
                                        + "    expr: \"LEAST(100, sample_points / 10)\"\n"
                                        + "  - var: summary\n"
                                        + "    template: \"近 ${window_days} 天得分 ${score}\"");
        writePlay("good-compute-tmpl", yaml);
        assertThat(registry().find("good-compute-tmpl")).isPresent();
    }

    // ====== _template 目录契约：fixtures.yaml 可对 play.yaml 跑通 ======

    /**
     * _template 是贡献者复制起点：play.yaml 必须本身合法（除目录名外），fixtures.yaml
     * 的每条 fixture 在 compute 流水线上跑出期望值。这保证文档与引擎语义不漂移。
     */
    @Test
    void templateDir_playYamlAndFixturesAreConsistent() throws IOException {
        Path repoTemplate = repoTemplateDir();
        byte[] yaml = Files.readAllBytes(repoTemplate.resolve("play.yaml"));
        // 目录名 _template 不合法是有意的（防被加载）；按 manifest 自带 name 加载。
        String manifestName = "night-owl-example";
        PlayDefinition def = PlayLoader.load(manifestName, yaml);

        Map<String, Object> fixtures = loadYaml(repoTemplate.resolve("fixtures.yaml"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> list = (List<Map<String, Object>>) fixtures.get("fixtures");
        assertThat(list).isNotEmpty();
        for (Map<String, Object> fixture : list) {
            runFixture(def, fixture);
        }
    }

    // ====== repo 真实玩法：每个 plays/<name>/ 必须可加载 + 自带 fixtures 全过 ======

    /**
     * 通用 fixture harness：扫 repo 根 plays/ 下全部非下划线目录，逐个 PlayLoader.load
     * （校验 schema / SQL 门禁）+ 跑 fixtures.yaml。新增玩法自动纳入，
     * 不需要每个玩法手写测试。
     */
    @Test
    void repoPlays_loadAndFixturesAllPass() throws IOException {
        Path playsRoot = repoTemplateDir().getParent();
        List<Path> playDirs;
        try (Stream<Path> s = Files.list(playsRoot)) {
            playDirs =
                    s.filter(Files::isDirectory)
                            .filter(d -> !d.getFileName().toString().startsWith("_"))
                            .sorted()
                            .toList();
        }
        assertThat(playDirs).as("repo plays/ 至少要有一个真实玩法").isNotEmpty();
        assertThat(playDirs)
                .extracting(d -> d.getFileName().toString())
                .contains("driving-personality");

        for (Path dir : playDirs) {
            String name = dir.getFileName().toString();
            byte[] yaml = Files.readAllBytes(dir.resolve("play.yaml"));
            PlayDefinition def = PlayLoader.load(name, yaml);

            Path fx = dir.resolve("fixtures.yaml");
            assertThat(Files.isRegularFile(fx)).as("play '%s' 缺 fixtures.yaml", name).isTrue();
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> list =
                    (List<Map<String, Object>>) loadYaml(fx).get("fixtures");
            assertThat(list).as("play '%s' fixtures 为空", name).isNotEmpty();
            for (Map<String, Object> fixture : list) {
                runFixture(def, fixture);
            }
        }
    }

    /** driving-personality：16 型表全覆盖（4 轴每个标签组合都有 persona 词条）。 */
    @Test
    void drivingPersonality_personaTableCoversAll16Codes() throws IOException {
        Path dir = repoTemplateDir().getParent().resolve("driving-personality");
        byte[] yaml = Files.readAllBytes(dir.resolve("play.yaml"));
        PlayDefinition def = PlayLoader.load("driving-personality", yaml);

        Map<String, Map<String, String>> personas = def.tables().get("personas");
        assertThat(personas).isNotNull();
        for (String p : List.of("F", "C")) {
            for (String t : List.of("N", "D")) {
                for (String d : List.of("L", "S")) {
                    for (String f : List.of("E", "O")) {
                        String code = p + t + d + f;
                        assertThat(personas)
                                .as("16 型缺人格码 %s", code)
                                .containsKey(code);
                        assertThat(personas.get(code))
                                .as("人格 %s 必须有 name/desc/tag", code)
                                .containsKeys("name", "desc", "tag");
                    }
                }
            }
        }
        assertThat(personas).hasSize(16);
    }

    @SuppressWarnings("unchecked")
    private void runFixture(PlayDefinition def, Map<String, Object> fixture) {
        String name = String.valueOf(fixture.get("name"));
        Map<String, Object> row = (Map<String, Object>) fixture.get("row");
        Number windowDays = (Number) fixture.getOrDefault("window_days", 30);

        int sample =
                row == null ? 0 : ((Number) row.getOrDefault(def.minSampleField(), 0)).intValue();
        if (Boolean.TRUE.equals(fixture.get("expect_unscored"))) {
            assertThat(sample)
                    .as("fixture '%s' 应走 unscored 分支", name)
                    .isLessThan(def.minSampleMin());
            return;
        }
        Map<String, Object> ctx = PlayComputeEngine.run(def, row, windowDays.longValue());
        Map<String, Object> expect = (Map<String, Object>) fixture.get("expect");
        for (Map.Entry<String, Object> e : expect.entrySet()) {
            Object actual = resolveDotted(ctx, e.getKey());
            if (e.getValue() instanceof Number n) {
                assertThat(((Number) actual).doubleValue())
                        .as("fixture '%s' var '%s'", name, e.getKey())
                        .isCloseTo(n.doubleValue(), within(0.001));
            } else {
                assertThat(String.valueOf(actual))
                        .as("fixture '%s' var '%s'", name, e.getKey())
                        .isEqualTo(String.valueOf(e.getValue()));
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Object resolveDotted(Map<String, Object> ctx, String path) {
        Object cur = ctx;
        for (String seg : path.split("\\.")) {
            cur = ((Map<String, Object>) cur).get(seg);
        }
        return cur;
    }

    private static Path repoTemplateDir() {
        // backend/ 模块测试 cwd = backend → repo 根 plays/_template
        Path p = Path.of("..", "plays", "_template").toAbsolutePath().normalize();
        assertThat(Files.isDirectory(p)).as("plays/_template 必须存在于 repo 根").isTrue();
        return p;
    }

    private static Map<String, Object> loadYaml(Path file) throws IOException {
        Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
        @SuppressWarnings("unchecked")
        Map<String, Object> doc =
                (Map<String, Object>)
                        yaml.load(Files.readString(file, StandardCharsets.UTF_8));
        return doc;
    }
}
