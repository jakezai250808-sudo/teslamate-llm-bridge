/* (C) TeslaMate play-engine-core */
package io.teslamate.play;

import io.teslamate.play.PlayDefinition.ComputeStep;
import io.teslamate.play.PlayDefinition.ExprStep;
import io.teslamate.play.PlayDefinition.LevelStep;
import io.teslamate.play.PlayDefinition.LookupStep;
import io.teslamate.play.PlayDefinition.OutputField;
import io.teslamate.play.PlayDefinition.TemplateStep;
import io.teslamate.play.PlayDefinition.Threshold;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * play.yaml → {@link PlayDefinition}：YAML 解析（SnakeYAML SafeConstructor，禁任意类型
 * 实例化）→ 手写 schema 校验（镜像 {@code plays/play.schema.json}）→ {@link PlaySqlGuard}
 * → 表达式编译 → 模板 lint。
 *
 * <p>SaaS 专属逻辑（OAuth scope 提取）通过 {@code scopeExtractor} 函数接口注入，核心模块
 * 不依赖 {@code OAuthScope} 等 SaaS 专有类。bridge 侧传入 {@code sql -> Set.of()}。
 *
 * <p>比 schema 更严的三条（语义层约束，JSON Schema 表达不了，schema 文件里有 $comment）：
 *
 * <ul>
 *   <li>level.thresholds 仅末项可省 {@code lt} 且末项必须省（保证全覆盖、无不可达项）
 *   <li>lookup.default 必填（设计定稿 prose "default 必填以保证全覆盖"）
 *   <li>compute var 不允许重名（避免 shadowing 混乱）
 * </ul>
 */
public final class PlayLoader {

  static final Pattern NAME_RE = Pattern.compile("^[a-z0-9][a-z0-9-]{1,38}[a-z0-9]$");
  private static final Pattern VAR_RE = Pattern.compile("^[a-z_][a-z0-9_]*$");

  /** compute template 步骤的占位符（与 {@link PlayComputeEngine} 同源：纯 ident 无点路径）。 */
  private static final Pattern COMPUTE_PLACEHOLDER_RE =
      Pattern.compile("\\$\\{([a-z_][a-z0-9_]*)}");

  /**
   * card-only 内置变量（compute template 步骤不可引用）。
   *
   * <p>这些变量仅在 SaaS 私有层 card 渲染路径注入，compute 流水线 ctx 不含它们。
   * compute template 步骤引用会在每次请求时 500 PLAY_COMPUTE_ERROR，必须在加载期拒掉。
   */
  static final Set<String> CARD_ONLY_BUILTINS =
      Set.of("car_name", "car_model", "window_label", "generated_at", "watermark");

  private static final Set<String> ROOT_KEYS =
      Set.of(
          "schema_version",
          "name",
          "title",
          "emoji",
          "description",
          "scope",
          "params",
          "sql",
          "min_sample",
          "compute",
          "tables",
          "output");

  private PlayLoader() {}

  /**
   * @param dirName play 目录名（必须与 manifest name 一致）
   * @param playYamlBytes play.yaml 原始字节（参与内容 SHA-256）
   * @param scopeExtractor SQL → 所需 scope 集合的提取函数。SaaS 传
   *     {@code PlaySqlScopeExtractor::extract}；bridge 传 {@code sql -> Set.of()}。
   */
  public static PlayDefinition load(
      String dirName,
      byte[] playYamlBytes,
      Function<String, Set<String>> scopeExtractor) {
    Map<String, Object> root = parseYaml(playYamlBytes);

    for (String k : root.keySet()) {
      if (!ROOT_KEYS.contains(k)) throw new PlayLoadException("未知字段 '" + k + "'");
    }

    // schema_version
    Object sv = require(root, "schema_version");
    if (!(sv instanceof Integer i) || i != 1) {
      throw new PlayLoadException("schema_version 必须为 1");
    }

    String name = reqString(root, "name", 40);
    if (!NAME_RE.matcher(name).matches()) {
      throw new PlayLoadException("name 不匹配 " + NAME_RE.pattern());
    }
    if (!name.equals(dirName)) {
      throw new PlayLoadException("name '" + name + "' 与目录名 '" + dirName + "' 不一致");
    }

    String title = reqString(root, "title", 40);
    String emoji = optString(root, "emoji", 8, "");
    String description = reqString(root, "description", 300);

    String scope = optString(root, "scope", 32, "read:drives");
    if (!"read:drives".equals(scope)) {
      throw new PlayLoadException("scope v1 仅允许 'read:drives'，got '" + scope + "'");
    }

    int defaultDays = 30;
    Object params = root.get("params");
    if (params != null) {
      Map<String, Object> p = asMap(params, "params");
      for (String k : p.keySet()) {
        if (!"default_days".equals(k)) {
          throw new PlayLoadException("params 未知字段 '" + k + "'");
        }
      }
      Object dd = p.get("default_days");
      if (dd != null) {
        if (!(dd instanceof Integer di) || di < 1 || di > 365) {
          throw new PlayLoadException("params.default_days 必须为 1..365 整数");
        }
        defaultDays = (Integer) dd;
      }
    }

    String sql = reqString(root, "sql", 8000);
    PlaySqlGuard.check(sql);

    // min_sample
    Map<String, Object> ms = asMap(require(root, "min_sample"), "min_sample");
    for (String k : ms.keySet()) {
      if (!"field".equals(k) && !"min".equals(k)) {
        throw new PlayLoadException("min_sample 未知字段 '" + k + "'");
      }
    }
    String msField = reqString(ms, "field", 120);
    Object msMin = require(ms, "min");
    if (!(msMin instanceof Integer mi) || mi < 1) {
      throw new PlayLoadException("min_sample.min 必须为 ≥1 整数");
    }

    // tables（先解析，lookup 校验要引用）
    Map<String, Map<String, Map<String, String>>> tables = parseTables(root.get("tables"));

    // compute 流水线
    List<ComputeStep> compute = parseCompute(require(root, "compute"), tables);

    // output
    List<OutputField> outputFields = parseOutput(require(root, "output"));

    String sha = contentSha256(playYamlBytes);

    // 通过注入的 scopeExtractor 派生所需 scope（在 PlaySqlGuard.check 通过之后）
    Set<String> requiredScopes = scopeExtractor.apply(sql);

    return new PlayDefinition(
        name,
        title,
        emoji,
        description,
        scope,
        defaultDays,
        sql,
        msField,
        (Integer) msMin,
        List.copyOf(compute),
        tables,
        List.copyOf(outputFields),
        sha,
        requiredScopes);
  }

  /**
   * 便捷重载：无 scope 提取（bridge 侧，{@code requiredScopes} 永远为空集）。
   */
  public static PlayDefinition load(String dirName, byte[] playYamlBytes) {
    return load(dirName, playYamlBytes, sql -> Set.of());
  }

  // ====== YAML ======

  private static Map<String, Object> parseYaml(byte[] bytes) {
    Object doc;
    try {
      Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
      doc = yaml.load(new String(bytes, StandardCharsets.UTF_8));
    } catch (RuntimeException e) {
      throw new PlayLoadException("YAML 解析失败: " + e.getMessage(), e);
    }
    if (!(doc instanceof Map)) {
      throw new PlayLoadException("play.yaml 根节点必须是 object");
    }
    Map<String, Object> out = new LinkedHashMap<>();
    for (Map.Entry<?, ?> e : ((Map<?, ?>) doc).entrySet()) {
      if (!(e.getKey() instanceof String k)) {
        throw new PlayLoadException("play.yaml key 必须为字符串");
      }
      out.put(k, e.getValue());
    }
    return out;
  }

  // ====== compute ======

  private static List<ComputeStep> parseCompute(
      Object computeObj, Map<String, Map<String, Map<String, String>>> tables) {
    if (!(computeObj instanceof List<?> list) || list.isEmpty() || list.size() > 64) {
      throw new PlayLoadException("compute 必须为 1..64 项数组");
    }
    List<ComputeStep> steps = new ArrayList<>();
    Set<String> seenVars = new HashSet<>();
    for (Object o : list) {
      Map<String, Object> step = asMap(o, "compute[]");
      String var = reqString(step, "var", 64);
      if (!VAR_RE.matcher(var).matches()) {
        throw new PlayLoadException("compute var '" + var + "' 不匹配 " + VAR_RE.pattern());
      }
      if (!seenVars.add(var)) {
        throw new PlayLoadException("compute var '" + var + "' 重名");
      }
      Set<String> kinds = new HashSet<>(step.keySet());
      kinds.remove("var");
      for (String k : kinds) {
        if (!Set.of("expr", "level", "template", "lookup").contains(k)) {
          throw new PlayLoadException("compute step 未知字段 '" + k + "'");
        }
      }
      if (kinds.size() != 1) {
        throw new PlayLoadException(
            "compute var '" + var + "' 必须恰含 expr/level/template/lookup 之一");
      }
      String kind = kinds.iterator().next();
      switch (kind) {
        case "expr" -> {
          String src = reqString(step, "expr", 500);
          steps.add(new ExprStep(var, PlayExpr.compile(src)));
        }
        case "level" -> steps.add(parseLevel(var, step.get("level")));
        case "template" -> {
          String tmpl = reqString(step, "template", 200);
          lintComputeTemplate(var, tmpl);
          steps.add(new TemplateStep(var, tmpl));
        }
        case "lookup" -> steps.add(parseLookup(var, step.get("lookup"), tables));
        default -> throw new PlayLoadException("unreachable");
      }
    }
    return steps;
  }

  private static LevelStep parseLevel(String var, Object levelObj) {
    Map<String, Object> level = asMap(levelObj, "level");
    for (String k : level.keySet()) {
      if (!"input".equals(k) && !"thresholds".equals(k)) {
        throw new PlayLoadException("level 未知字段 '" + k + "'");
      }
    }
    String input = reqString(level, "input", 64);
    Object thObj = require(level, "level.thresholds");
    if (!(thObj instanceof List<?> thList) || thList.isEmpty()) {
      throw new PlayLoadException("level.thresholds 必须为非空数组");
    }
    List<Threshold> thresholds = new ArrayList<>();
    for (int i = 0; i < thList.size(); i++) {
      Map<String, Object> t = asMap(thList.get(i), "thresholds[" + i + "]");
      for (String k : t.keySet()) {
        if (!"lt".equals(k) && !"label".equals(k)) {
          throw new PlayLoadException("threshold 未知字段 '" + k + "'");
        }
      }
      String label = reqString(t, "label", 120);
      Object lt = t.get("lt");
      boolean last = i == thList.size() - 1;
      if (last) {
        if (lt != null) {
          throw new PlayLoadException("level('" + var + "') 末项必须省略 lt 作兜底");
        }
        thresholds.add(new Threshold(null, label));
      } else {
        if (!(lt instanceof Number n)) {
          throw new PlayLoadException(
              "level('" + var + "') 非末项必须有数字 lt（否则后续项不可达）");
        }
        thresholds.add(new Threshold(n.doubleValue(), label));
      }
    }
    return new LevelStep(var, input, List.copyOf(thresholds));
  }

  private static LookupStep parseLookup(
      String var, Object lookupObj, Map<String, Map<String, Map<String, String>>> tables) {
    Map<String, Object> lookup = asMap(lookupObj, "lookup");
    for (String k : lookup.keySet()) {
      if (!Set.of("key", "table", "default").contains(k)) {
        throw new PlayLoadException("lookup 未知字段 '" + k + "'");
      }
    }
    String key = reqString(lookup, "key", 64);
    String table = reqString(lookup, "table", 64);
    if (!tables.containsKey(table)) {
      throw new PlayLoadException("lookup('" + var + "') 引用不存在的 table '" + table + "'");
    }
    Object def = lookup.get("default");
    if (def == null) {
      throw new PlayLoadException("lookup('" + var + "') 必须有 default 兜底 object");
    }
    Map<String, Object> defMap = asMap(def, "lookup.default");
    Map<String, Object> immutableDef = new LinkedHashMap<>(defMap);
    return new LookupStep(var, key, table, java.util.Collections.unmodifiableMap(immutableDef));
  }

  // ====== tables / output ======

  private static Map<String, Map<String, Map<String, String>>> parseTables(Object tablesObj) {
    Map<String, Map<String, Map<String, String>>> out = new LinkedHashMap<>();
    if (tablesObj == null) return out;
    Map<String, Object> tables = asMap(tablesObj, "tables");
    for (Map.Entry<String, Object> te : tables.entrySet()) {
      Map<String, Object> keys = asMap(te.getValue(), "tables." + te.getKey());
      Map<String, Map<String, String>> keyMap = new LinkedHashMap<>();
      for (Map.Entry<String, Object> ke : keys.entrySet()) {
        Map<String, Object> fields =
            asMap(ke.getValue(), "tables." + te.getKey() + "." + ke.getKey());
        Map<String, String> fieldMap = new LinkedHashMap<>();
        for (Map.Entry<String, Object> fe : fields.entrySet()) {
          if (!(fe.getValue() instanceof String s) || s.length() > 120) {
            throw new PlayLoadException(
                "tables."
                    + te.getKey()
                    + "."
                    + ke.getKey()
                    + "."
                    + fe.getKey()
                    + " 必须为 ≤120 字符串");
          }
          fieldMap.put(fe.getKey(), s);
        }
        keyMap.put(ke.getKey(), java.util.Collections.unmodifiableMap(fieldMap));
      }
      out.put(te.getKey(), java.util.Collections.unmodifiableMap(keyMap));
    }
    return java.util.Collections.unmodifiableMap(out);
  }

  private static List<OutputField> parseOutput(Object outputObj) {
    Map<String, Object> output = asMap(outputObj, "output");
    for (String k : output.keySet()) {
      if (!"fields".equals(k)) throw new PlayLoadException("output 未知字段 '" + k + "'");
    }
    Object fObj = require(output, "output.fields");
    if (!(fObj instanceof List<?> fList) || fList.isEmpty()) {
      throw new PlayLoadException("output.fields 必须为非空数组");
    }
    List<OutputField> fields = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    for (Object o : fList) {
      Map<String, Object> f = asMap(o, "output.fields[]");
      for (String k : f.keySet()) {
        if (!Set.of("name", "from", "type").contains(k)) {
          throw new PlayLoadException("output field 未知字段 '" + k + "'");
        }
      }
      String name = reqString(f, "name", 64);
      String from = reqString(f, "from", 64);
      String type = reqString(f, "type", 16);
      if (!Set.of("number", "string", "object").contains(type)) {
        throw new PlayLoadException(
            "output field '" + name + "' type 必须为 number/string/object");
      }
      if (!seen.add(name)) {
        throw new PlayLoadException("output field '" + name + "' 重名");
      }
      fields.add(new OutputField(name, from, type));
    }
    return fields;
  }

  // ====== 模板 lint ======

  private static void lintComputeTemplate(String var, String template) {
    Matcher m = COMPUTE_PLACEHOLDER_RE.matcher(template);
    while (m.find()) {
      String ident = m.group(1);
      if (CARD_ONLY_BUILTINS.contains(ident)) {
        throw new PlayLoadException(
            "compute template('"
                + var
                + "') 引用 '${"
                + ident
                + "}' —— 该内置变量仅 SaaS 私有层 card 路径可用（compute 流水线 ctx 无此变量）");
      }
    }
  }

  // ====== helpers ======

  private static String contentSha256(byte[] yamlBytes) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      md.update(yamlBytes);
      StringBuilder sb = new StringBuilder();
      for (byte b : md.digest()) sb.append(String.format("%02x", b));
      return sb.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }

  private static Object require(Map<String, Object> map, String key) {
    String shortKey = key.contains(".") ? key.substring(key.lastIndexOf('.') + 1) : key;
    Object v = map.get(shortKey);
    if (v == null) throw new PlayLoadException("缺必填字段 '" + key + "'");
    return v;
  }

  private static String reqString(Map<String, Object> map, String key, int maxLen) {
    Object v = require(map, key);
    if (!(v instanceof String s) || s.isBlank()) {
      throw new PlayLoadException("字段 '" + key + "' 必须为非空字符串");
    }
    if (s.length() > maxLen) {
      throw new PlayLoadException("字段 '" + key + "' 超长（>" + maxLen + "）");
    }
    return s;
  }

  private static String optString(Map<String, Object> map, String key, int maxLen, String dflt) {
    Object v = map.get(key);
    if (v == null) return dflt;
    if (!(v instanceof String s)) {
      throw new PlayLoadException("字段 '" + key + "' 必须为字符串");
    }
    if (s.length() > maxLen) {
      throw new PlayLoadException("字段 '" + key + "' 超长（>" + maxLen + "）");
    }
    return s;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> asMap(Object o, String what) {
    if (!(o instanceof Map)) {
      throw new PlayLoadException("'" + what + "' 必须为 object");
    }
    for (Object k : ((Map<?, ?>) o).keySet()) {
      if (!(k instanceof String)) {
        throw new PlayLoadException("'" + what + "' 的 key 必须为字符串");
      }
    }
    return (Map<String, Object>) o;
  }
}
