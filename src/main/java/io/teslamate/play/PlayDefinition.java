/* (C) TeslaMate play-engine-core */
package io.teslamate.play;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 一个已加载并通过全部校验的玩法（play）—— manifest 字段 + 已编译表达式 + SVG 模板文本 +
 * 内容 SHA-256（进 ETag，play 更新自动 bust 缓存）。
 *
 * <p>由 {@link PlayLoader} 构造；{@link PlayRegistry} 启动后持有不可变 Map（v1 无热加载，
 * 改 {@code PLAYS_DIR} 需重启 —— 设计定稿，简单可审计）。
 *
 * @param name 玩法名（同目录名），{@code ^[a-z0-9][a-z0-9-]{1,38}[a-z0-9]$}
 * @param title 卡片标题（≤40 字符）
 * @param emoji 可选 emoji（≤8 字符，可空串）
 * @param description LLM 友好描述（≤300 字符）
 * @param scope v1 锁死 {@code read:drives}（schema 单值 enum）
 * @param defaultDays 缺省时间窗（params.default_days，无则 30）
 * @param sql 聚合 SQL（已过 {@link PlaySqlGuard}；绑定参数 :car_id/:tz/:start/:end）
 * @param minSampleField SQL 返回列名 —— 采样数
 * @param minSampleMin 最小采样数，不足返 unscored
 * @param compute 顺序流水线步骤
 * @param tables lookup 步骤的查表数据：table → key → field → string
 * @param outputFields JSON envelope 暴露的字段
 * @param cardTemplate card.svg.tmpl 文本；play 无卡片时为 null
 * @param contentSha256 play.yaml + card.svg.tmpl 字节的 SHA-256 hex
 * @param requiredScopes 所需 scope 集合。SaaS loader 通过 {@link PlayLoader} 的
 *     {@code requiredScopesExtractor} 扩展点填充（从 SQL 自动派生）；bridge 侧 loader
 *     传 {@code Set.of()} 空集（bridge 无 OAuth scope 概念，{@link PlayScopeChecker}
 *     接口的 bridge 实现永远放行）。字段保留在 record 签名里让两侧共用同一 record 类型。
 */
public record PlayDefinition(
    String name,
    String title,
    String emoji,
    String description,
    String scope,
    int defaultDays,
    String sql,
    String minSampleField,
    int minSampleMin,
    List<ComputeStep> compute,
    Map<String, Map<String, Map<String, String>>> tables,
    List<OutputField> outputFields,
    String cardTemplate,
    String contentSha256,
    Set<String> requiredScopes) {

  public boolean hasCard() {
    return cardTemplate != null;
  }

  /** output.fields 条目：{@code name} 暴露名、{@code from} 来源 var/列、{@code type} 三选一。 */
  public record OutputField(String name, String from, String type) {}

  /** compute 流水线步骤（四选一，schema oneOf）。 */
  public sealed interface ComputeStep permits ExprStep, LevelStep, TemplateStep, LookupStep {
    String var();
  }

  /** 纯算术（自研 mini-expr，见 {@link PlayExpr}）。 */
  public record ExprStep(String var, PlayExpr expr) implements ComputeStep {}

  /**
   * 有序阈值映射：首个 {@code value < lt} 命中即取 label；末项无 lt 为兜底（loader 强制，
   * 保证全覆盖）。条件逻辑全部走 level，表达式语言保持纯算术。
   */
  public record LevelStep(String var, String input, List<Threshold> thresholds)
      implements ComputeStep {}

  /** level 阈值条目；{@code lt == null} 仅允许出现在末项（兜底）。 */
  public record Threshold(Double lt, String label) {}

  /** 字符串拼接 {@code ${var}}。 */
  public record TemplateStep(String var, String template) implements ComputeStep {}

  /**
   * 字符串 key 查 {@link PlayDefinition#tables}；{@code defaultValue} 必填（loader 强制，
   * 保证 key 未命中时仍有 object 兜底）。
   */
  public record LookupStep(String var, String key, String table, Map<String, Object> defaultValue)
      implements ComputeStep {}
}
