package io.teslabridge.play;

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
 *   <li>不得含 {@code ;}（杜绝多语句；<b>原文级检查</b>，注释 / 字符串里也不许 —— 在剥离前做）
 *   <li>必须含真实的租户过滤比较 {@code car_id = :car_id}（可带表前缀，如
 *       {@code p.car_id = :car_id}）。仅出现 {@code :car_id}（投影列 / 注释 / 字符串字面量 /
 *       恒真式 {@code :car_id = :car_id}）不算 —— 缺真实过滤会把全部车主数据聚合进结果
 *       （跨租户泄露）
 *   <li>必须以 {@code SELECT} 或 {@code WITH} 开头
 *   <li>词边界正则拒写操作 / 危险函数关键字
 * </ol>
 *
 * <p><b>为何要剥字符串字面量</b>：{@code CAR_ID_FILTER} 正则只是在文本上找
 * {@code car_id = :car_id} 子串，无法区分它出现在真实 WHERE 比较里还是出现在一个字符串
 * 常量里。半信任 play 可构造
 * <pre>SELECT count(*) FROM positions WHERE 'car_id = :car_id' &lt;&gt; '' AND ...</pre>
 * —— 那个 {@code 'car_id = :car_id'} 只是个恒真的字符串比较，对 positions 毫无 car_id
 * 过滤，却会让门禁误判通过 → 聚合全部车主数据跨租户泄露。因此在租户过滤 / 开头 / 禁词
 * 检查前先把单引号字符串字面量（PG 语法，内部 {@code ''} 转义两个单引号）整体替换成空格。
 *
 * <p><b>本门禁是 best-effort 静态防线，不能替代 review</b>：它验证「存在一个
 * {@code car_id = :car_id} 比较」，但无法静态证明该比较真的约束了每一张被查的表
 * （如 OR 短路 / 笛卡尔积侧表未过滤）。PLAYS_DIR 与外部贡献的 play SQL 依旧必须人工
 * review 租户过滤语义。
 *
 * <p>运行时防御另有两层（{@link PlayEngine}）：专属 JdbcTemplate
 * {@code setQueryTimeout(5)} + {@code setMaxRows(100)}、readOnly TransactionTemplate。
 */
final class PlaySqlGuard {

    /**
     * 词边界拒绝表：写操作 + DDL + 权限 + 危险函数 + 集合操作。大小写不敏感。
     *
     * <p>UNION/EXCEPT/INTERSECT：第二个 leg 完全不受 {@code car_id = :car_id} 约束，可拼接
     * 任意第二查询跨数据泄露；加入 FORBIDDEN 统一在加载期拒绝，不依赖 review 兜底。
     * 单租户场景同样需要拦截，防止社区贡献的 play SQL 通过 UNION 拼出越权查询。
     */
    private static final Pattern FORBIDDEN =
            Pattern.compile(
                    "\\b(INSERT|UPDATE|DELETE|DROP|ALTER|CREATE|TRUNCATE|GRANT|COPY|DO|CALL|EXECUTE|PG_SLEEP"
                            + "|UNION|EXCEPT|INTERSECT)\\b",
                    Pattern.CASE_INSENSITIVE);

    /** 行注释 + 块注释（DOTALL 让块注释可跨行）。 */
    private static final Pattern COMMENTS =
            Pattern.compile("--[^\\n]*|/\\*.*?\\*/", Pattern.DOTALL);

    /**
     * PG 单引号字符串字面量：{@code '...'}，内部 {@code ''}（两个单引号）是转义后的字面量
     * 单引号。{@code (?:[^']|'')*} 匹配「非引号字符」或「转义的双单引号」任意次，确保整段
     * 字面量（含内部转义）被完整吃掉。剥离它防止字符串常量里的假 {@code car_id = :car_id}
     * 比较骗过门禁（跨租户泄露面）。在去注释之后做（注释里的引号不构成字面量）。
     */
    private static final Pattern STRING_LITERAL = Pattern.compile("'(?:[^']|'')*'");

    /**
     * 租户过滤比较：{@code car_id = :car_id}（容空白、可带 {@code 表.} 前缀）。
     * 负向后顾 {@code (?<!:)} 排除恒真式 {@code :car_id = :car_id}（左侧是绑定参数
     * 而非列引用，过滤不到任何行）。
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
        // 顺序：去注释 → 去字符串字面量 → 再做租户过滤 / 开头 / 禁词检查。
        // 注释里的 :car_id 不算数；字符串字面量里的假 'car_id = :car_id' 比较也不算数
        // （否则会骗过门禁 → 跨租户泄露，见类 javadoc）。
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
