/* (C) TeslaMate play-engine-core */
package io.teslamate.play;

import java.util.regex.Pattern;

/**
 * play.yaml SQL 静态门禁（设计定稿 §2.2）。信任边界 = PR review，但引擎仍设防 ——
 * {@code PLAYS_DIR} 外部目录（ECS 热修通道）的信任弱于 repo，必须按"半信任输入"处理。
 *
 * <p>任一不过即 {@link PlayLoadException}（加载期拒载，不挡启动）。<b>租户过滤 / 开头 /
 * 禁词检查都在「去注释 + 去字符串字面量」后的 SQL 上做</b>（防止 {@code :car_id} 只出现在
 * 注释或字符串字面量里骗过门禁）：
 *
 * <ol>
 *   <li>不得含 {@code ;}（杜绝多语句；<b>原文级检查</b>，注释 / 字符串里也不许）
 *   <li>必须含真实的租户过滤比较 {@code car_id = :car_id}（可带表前缀）
 *   <li>必须以 {@code SELECT} 或 {@code WITH} 开头
 *   <li>词边界正则拒写操作 / 危险函数关键字（含 {@code EXECUTE}）
 * </ol>
 *
 * <p><b>EXECUTE 关键字</b>：bridge 侧原本多出此项，核心模块统一纳入禁词超集，SaaS 侧同样
 * 拦截，无副作用。
 */
final class PlaySqlGuard {

  /**
   * 词边界拒绝表：写操作 + DDL + 权限 + 危险函数 + 集合操作 + EXECUTE。大小写不敏感。
   *
   * <p>UNION/EXCEPT/INTERSECT：第二个 leg 完全不受 {@code car_id = :car_id} 约束，可拼接
   * 任意第二查询跨租户泄露；加入 FORBIDDEN 统一在加载期拒绝，不依赖 review 兜底。
   *
   * <p>EXECUTE：防止通过 {@code EXECUTE} 绕过 SELECT/WITH 开头限制动态执行任意 SQL。
   */
  private static final Pattern FORBIDDEN =
      Pattern.compile(
          "\\b(INSERT|UPDATE|DELETE|DROP|ALTER|CREATE|TRUNCATE|GRANT|COPY|DO|CALL|PG_SLEEP"
              + "|EXECUTE|UNION|EXCEPT|INTERSECT)\\b",
          Pattern.CASE_INSENSITIVE);

  /** 行注释 + 块注释（DOTALL 让块注释可跨行）。 */
  private static final Pattern COMMENTS =
      Pattern.compile("--[^\\n]*|/\\*.*?\\*/", Pattern.DOTALL);

  /**
   * PG 单引号字符串字面量：{@code '...'}，内部 {@code ''}（两个单引号）是转义后的字面量
   * 单引号。剥离它防止字符串常量里的假 {@code car_id = :car_id} 比较骗过门禁。
   */
  private static final Pattern STRING_LITERAL = Pattern.compile("'(?:[^']|'')*'");

  /**
   * 租户过滤比较：{@code car_id = :car_id}（容空白、可带 {@code 表.} 前缀）。
   * 负向后顾 {@code (?<!:)} 排除恒真式 {@code :car_id = :car_id}。
   */
  private static final Pattern CAR_ID_FILTER =
      Pattern.compile("(?<!:)\\bcar_id\\s*=\\s*:car_id\\b", Pattern.CASE_INSENSITIVE);

  private PlaySqlGuard() {}

  /** 校验通过返回（无返回值）；任一规则不过抛 {@link PlayLoadException}。 */
  static void check(String sql) {
    if (sql == null || sql.isBlank()) {
      throw new PlayLoadException("sql 为空");
    }
    // ';' 在原文上检查（剥离前）—— 字符串字面量 / 注释里也不许出现，杜绝多语句。
    if (sql.indexOf(';') >= 0) {
      throw new PlayLoadException("sql 不得含 ';'（禁多语句）");
    }
    String noComments = COMMENTS.matcher(sql).replaceAll(" ");
    String stripped = STRING_LITERAL.matcher(noComments).replaceAll(" ").strip();
    if (!CAR_ID_FILTER.matcher(stripped).find()) {
      throw new PlayLoadException(
          "sql 必须含租户过滤比较 'car_id = :car_id'（注释 / 字符串字面量里的不算；"
              + "仅引用 :car_id 而不过滤会聚合全部车主数据）");
    }
    String upper = stripped.toUpperCase(java.util.Locale.ROOT);
    if (!upper.startsWith("SELECT") && !upper.startsWith("WITH")) {
      throw new PlayLoadException("sql 去注释后必须以 SELECT 或 WITH 开头");
    }
    var m = FORBIDDEN.matcher(stripped);
    if (m.find()) {
      throw new PlayLoadException("sql 含禁用关键字 '" + m.group(1) + "'");
    }
  }
}
