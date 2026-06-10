/* (C) TeslaMate play-engine-core */
package io.teslamate.play;

import io.teslamate.play.PlayDefinition.ComputeStep;
import io.teslamate.play.PlayDefinition.ExprStep;
import io.teslamate.play.PlayDefinition.LevelStep;
import io.teslamate.play.PlayDefinition.LookupStep;
import io.teslamate.play.PlayDefinition.TemplateStep;
import io.teslamate.play.PlayDefinition.Threshold;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 对单行 SQL 结果跑 compute 顺序流水线（设计定稿 §1.2）。
 *
 * <p>上下文 ctx 初始 = SQL 返回列（key 统一小写）+ 内置 {@code window_days}；每步产出
 * {@code var → value} 追加进 ctx，后续步骤可引用。四种步骤：
 *
 * <ul>
 *   <li>{@code expr} → Double（{@link PlayExpr}，除零返 0 + WARN）
 *   <li>{@code level} → String label（首个 {@code value < lt} 命中；末项兜底）
 *   <li>{@code template} → String（{@code ${var}} 替换；数字格式化去尾 .0）
 *   <li>{@code lookup} → Map（字符串 key 查 tables；未命中取 default）
 * </ul>
 *
 * <p>运行时未知标识符 / 类型不符 → {@link PlayComputeException}（→ 500 PLAY_COMPUTE_ERROR）。
 */
public final class PlayComputeEngine {

  private static final Logger log = LoggerFactory.getLogger(PlayComputeEngine.class);

  private static final Pattern PLACEHOLDER_RE = Pattern.compile("\\$\\{([a-z_][a-z0-9_]*)}");

  private PlayComputeEngine() {}

  /**
   * @param row SQL 第一行（列名小写）；调用方保证非 null（unscored 路径不会进来）
   * @param windowDays 内置变量 {@code window_days}
   * @return ctx：SQL 列 + window_days + 全部 compute var（LinkedHashMap 保序）
   */
  public static Map<String, Object> run(PlayDefinition play, Map<String, Object> row, long windowDays) {
    Map<String, Object> ctx = new LinkedHashMap<>();
    for (Map.Entry<String, Object> e : row.entrySet()) {
      ctx.put(e.getKey().toLowerCase(Locale.ROOT), e.getValue());
    }
    ctx.put("window_days", (double) windowDays);

    for (ComputeStep step : play.compute()) {
      switch (step) {
        case ExprStep s -> ctx.put(s.var(), s.expr().eval(ctx));
        case LevelStep s -> ctx.put(s.var(), evalLevel(s, ctx));
        case TemplateStep s -> ctx.put(s.var(), substitute(s.template(), ctx));
        case LookupStep s -> ctx.put(s.var(), evalLookup(play, s, ctx));
      }
    }
    return ctx;
  }

  private static String evalLevel(LevelStep s, Map<String, Object> ctx) {
    Object raw = resolve(s.input(), ctx);
    if (!(raw instanceof Number n)) {
      throw new PlayComputeException(
          "level('" + s.var() + "') input '" + s.input() + "' 不是数字");
    }
    double v = n.doubleValue();
    for (Threshold t : s.thresholds()) {
      if (t.lt() == null || v < t.lt()) return t.label();
    }
    throw new PlayComputeException("level('" + s.var() + "') 无兜底项");
  }

  private static Map<String, String> evalLookup(
      PlayDefinition play, LookupStep s, Map<String, Object> ctx) {
    Object raw = resolve(s.key(), ctx);
    if (!(raw instanceof String key)) {
      throw new PlayComputeException(
          "lookup('" + s.var() + "') key '" + s.key() + "' 不是字符串");
    }
    Map<String, Map<String, String>> table = play.tables().get(s.table());
    Map<String, String> hit = table == null ? null : table.get(key);
    if (hit != null) return hit;
    Map<String, String> def = new LinkedHashMap<>();
    for (Map.Entry<String, Object> e : s.defaultValue().entrySet()) {
      def.put(e.getKey(), String.valueOf(e.getValue()));
    }
    log.warn("play lookup('{}') key '{}' 未命中 table '{}'，取 default", s.var(), key, s.table());
    return def;
  }

  /** {@code ${var}} 替换：var 须存在于 ctx（未知 → PlayComputeException）。 */
  public static String substitute(String template, Map<String, Object> ctx) {
    Matcher m = PLACEHOLDER_RE.matcher(template);
    StringBuilder sb = new StringBuilder();
    while (m.find()) {
      String var = m.group(1);
      Object v = resolve(var, ctx);
      m.appendReplacement(sb, Matcher.quoteReplacement(formatValue(v)));
    }
    m.appendTail(sb);
    return sb.toString();
  }

  private static Object resolve(String ident, Map<String, Object> ctx) {
    if (!ctx.containsKey(ident)) {
      throw new PlayComputeException("unknown identifier '" + ident + "'");
    }
    return ctx.get(ident);
  }

  /**
   * 数字格式化：整值去尾 {@code .0}（86.0 → "86"），其余保留两位内有效小数。
   *
   * <p>{@code public} 访问：供 controller 层（可能在不同 package 如 bridge）格式化 output
   * 字段值（{@code output.type=string} 场景）。
   */
  public static String formatValue(Object v) {
    if (v == null) return "";
    if (v instanceof Number n) {
      double d = n.doubleValue();
      if (d == Math.rint(d) && !Double.isInfinite(d)) {
        return String.valueOf((long) d);
      }
      String s = java.math.BigDecimal.valueOf(d).stripTrailingZeros().toPlainString();
      return s;
    }
    return String.valueOf(v);
  }
}
