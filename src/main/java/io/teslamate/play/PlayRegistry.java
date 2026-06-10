/* (C) TeslaMate play-engine-core */
package io.teslamate.play;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

/**
 * 玩法注册表（设计定稿 §2.1）：启动时扫描 → 校验 → 不可变 Map。
 *
 * <p>扫描两条路径：
 *
 * <ol>
 *   <li>classpath {@code plays/&lt;name&gt;/play.yaml}（repo 根 {@code plays/} 经 maven
 *       resource 打进 jar；{@code _} 前缀目录如 {@code _template} 跳过）
 *   <li>env {@code PLAYS_DIR} 非空时再扫 {@code ${PLAYS_DIR}/&lt;name&gt;/play.yaml}
 *       （ECS 热修通道）。<b>同名时 PLAYS_DIR 覆盖 classpath</b>，打 WARN。
 * </ol>
 *
 * <p>任何一步校验失败：{@code log.warn} 并继续 —— <b>坏 play 不挡启动</b>。v1 无热加载：
 * 改 PLAYS_DIR 需重启（定案，简单可审计）。
 *
 * <p><b>scope 提取注入</b>：{@code scopeExtractor} 参数为 SQL → 所需 scope 集合的函数，
 * 由上层模块注入。SaaS 传 {@code PlaySqlScopeExtractor::extract}；bridge 传
 * {@code sql -> Set.of()}（无 OAuth 概念）。默认构造器（Spring {@code @Value} 单参）
 * 使用 {@code sql -> Set.of()}，供 bridge 场景直接用；SaaS 通过
 * {@link PlayRegistry#PlayRegistry(String, Function)} 注入 extractor。
 */
@Component
public class PlayRegistry {

  private static final Logger log = LoggerFactory.getLogger(PlayRegistry.class);

  private final Map<String, PlayDefinition> plays;

  /**
   * 默认构造器：scope extractor = {@code sql -> Set.of()}（bridge / 无 OAuth 场景）。
   * Spring {@code @Value} 注入 {@code PLAYS_DIR} env。
   */
  public PlayRegistry(@Value("${PLAYS_DIR:}") String playsDir) {
    this(playsDir, sql -> Set.of());
  }

  /**
   * 带 scope extractor 的构造器（SaaS 场景，通过 {@code @Bean} 或子类覆盖注入）。
   *
   * @param playsDir plays 外部目录（空串 = 不扫描）
   * @param scopeExtractor SQL → 所需 scope 集合（SaaS 传 PlaySqlScopeExtractor::extract）
   */
  public PlayRegistry(String playsDir, Function<String, Set<String>> scopeExtractor) {
    Map<String, PlayDefinition> loaded = new LinkedHashMap<>();
    scanClasspath(loaded, scopeExtractor);
    if (playsDir != null && !playsDir.isBlank()) {
      scanExternalDir(Path.of(playsDir), loaded, scopeExtractor);
    }
    this.plays = Collections.unmodifiableMap(new TreeMap<>(loaded));
    log.info("PlayRegistry loaded {} play(s): {}", plays.size(), plays.keySet());
  }

  public Optional<PlayDefinition> find(String name) {
    return Optional.ofNullable(plays.get(name));
  }

  public List<PlayDefinition> all() {
    return List.copyOf(plays.values());
  }

  public int size() {
    return plays.size();
  }

  // ====== classpath 扫描 ======

  private void scanClasspath(
      Map<String, PlayDefinition> out, Function<String, Set<String>> scopeExtractor) {
    PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
    Resource[] resources;
    try {
      resources = resolver.getResources("classpath*:plays/*/play.yaml");
    } catch (IOException e) {
      log.warn("classpath plays 扫描失败: {}", e.getMessage());
      return;
    }
    for (Resource r : resources) {
      String dirName = dirNameOf(r);
      if (dirName == null) {
        log.warn("play 资源 {} 解析不出目录名，跳过", r);
        continue;
      }
      if (dirName.startsWith("_")) {
        continue;
      }
      try {
        byte[] yamlBytes = readAll(r);
        byte[] cardBytes = null;
        Resource card = r.createRelative("card.svg.tmpl");
        if (card.exists()) {
          cardBytes = readAll(card);
        }
        PlayDefinition def = PlayLoader.load(dirName, yamlBytes, cardBytes, scopeExtractor);
        out.put(def.name(), def);
        log.info("play '{}' loaded from classpath", def.name());
      } catch (PlayLoadException | IOException e) {
        log.warn("play '{}' 跳过: {}", dirName, e.getMessage());
      }
    }
  }

  private static String dirNameOf(Resource r) {
    try {
      String url = r.getURL().toString();
      int end = url.lastIndexOf("/play.yaml");
      if (end < 0) return null;
      int start = url.lastIndexOf('/', end - 1);
      if (start < 0) return null;
      return url.substring(start + 1, end);
    } catch (IOException e) {
      return null;
    }
  }

  private static byte[] readAll(Resource r) throws IOException {
    try (InputStream in = r.getInputStream()) {
      return in.readAllBytes();
    }
  }

  // ====== PLAYS_DIR 外部目录扫描（热修通道，覆盖 classpath） ======

  private void scanExternalDir(
      Path dir,
      Map<String, PlayDefinition> out,
      Function<String, Set<String>> scopeExtractor) {
    if (!Files.isDirectory(dir)) {
      log.warn("PLAYS_DIR '{}' 不是目录，跳过外部扫描", dir);
      return;
    }
    try (Stream<Path> entries = Files.list(dir)) {
      entries
          .filter(Files::isDirectory)
          .sorted()
          .forEach(playDir -> loadExternal(playDir, out, scopeExtractor));
    } catch (IOException e) {
      log.warn("PLAYS_DIR '{}' 扫描失败: {}", dir, e.getMessage());
    }
  }

  private void loadExternal(
      Path playDir,
      Map<String, PlayDefinition> out,
      Function<String, Set<String>> scopeExtractor) {
    String dirName = playDir.getFileName().toString();
    if (dirName.startsWith("_")) return;
    Path yaml = playDir.resolve("play.yaml");
    if (!Files.isRegularFile(yaml)) return;
    try {
      byte[] yamlBytes = Files.readAllBytes(yaml);
      Path card = playDir.resolve("card.svg.tmpl");
      byte[] cardBytes = Files.isRegularFile(card) ? Files.readAllBytes(card) : null;
      PlayDefinition def = PlayLoader.load(dirName, yamlBytes, cardBytes, scopeExtractor);
      if (out.containsKey(def.name())) {
        log.warn("play '{}' 被 PLAYS_DIR 覆盖 classpath 版本（热修通道）", def.name());
      }
      out.put(def.name(), def);
      log.info("play '{}' loaded from PLAYS_DIR", def.name());
    } catch (PlayLoadException | IOException e) {
      log.warn("play '{}' 跳过: {}", dirName, e.getMessage());
    }
  }
}
