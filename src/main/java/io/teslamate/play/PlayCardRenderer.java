/* (C) TeslaMate play-engine-core */
package io.teslamate.play;

import io.teslamate.play.PlayDefinition.OutputField;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.batik.bridge.ExternalResourceSecurity;
import org.apache.batik.bridge.NoLoadExternalResourceSecurity;
import org.apache.batik.bridge.NoLoadScriptSecurity;
import org.apache.batik.bridge.ScriptSecurity;
import org.apache.batik.bridge.UserAgent;
import org.apache.batik.transcoder.SVGAbstractTranscoder;
import org.apache.batik.transcoder.TranscoderException;
import org.apache.batik.transcoder.TranscoderInput;
import org.apache.batik.transcoder.TranscoderOutput;
import org.apache.batik.transcoder.image.PNGTranscoder;
import org.apache.batik.util.ParsedURL;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * card.svg.tmpl → 变量替换 → Batik PNG 转码（设计定稿 §2.3）。
 *
 * <p>安全设计：
 *
 * <ul>
 *   <li>变量替换 {@code ${ident}}（ident 允许点路径如 {@code persona.name}），值一律 XML
 *       转义（&amp; &lt; &gt; " '）防 SVG 注入
 *   <li>{@code output.type=number} 的字段先验证为数字再注入（坐标 / 宽度属性场景）
 *   <li>模板加载期已 lint（{@link PlayLoader}：拒 script / foreignObject / 外链 / DTD）；
 *       本类对替换后的最终 SVG 再做一次 DOCTYPE/ENTITY 字面拒绝（defense-in-depth）
 *   <li>字体走 prod base image 已有 Noto Sans CJK（fonts-noto-cjk，Java AWT headless 已配）
 * </ul>
 *
 * <p>数据不足时不报错：渲染引擎内置的灰色「数据不足」兜底卡（HTTP 200），LLM 平台流程不中断。
 *
 * <p><b>watermark 配置</b>：{@code play.card-watermark}（默认空串）。SaaS 侧在
 * {@code application.yml} 配置为 {@code tesla.shenqinqin.com}；bridge 侧可配为
 * bridge 域名或留空。兜底卡模板（{@link #renderUnavailable}/{@link #renderInsufficient}）
 * 直接嵌入 {@code ${watermark}} 占位符，与 play 自带 card.svg.tmpl 格式一致。
 */
@Component
public class PlayCardRenderer {

  /** 输出尺寸：1080×1080（小红书 1:1 正方形配图，与 score-card 一致）。 */
  static final float CANVAS = 1080f;

  private static final Pattern PLACEHOLDER_RE =
      Pattern.compile("\\$\\{([a-z_][a-z0-9_]*(?:\\.[a-z_][a-z0-9_]*)*)}");

  /** watermark 文字（注入到兜底卡 + 供 play 模板使用）。默认空串 = 无水印。 */
  private final String watermark;

  public PlayCardRenderer(@Value("${play.card-watermark:}") String watermark) {
    this.watermark = watermark == null ? "" : watermark;
  }

  /** 渲染失败（Batik 转码错 / 变量解析错）。controller 层转 500 problem+json。 */
  public static class PlayRenderException extends RuntimeException {
    public PlayRenderException(String message, Throwable cause) {
      super(message, cause);
    }

    public PlayRenderException(String message) {
      super(message);
    }
  }

  /**
   * 正常渲染：模板占位符替换 → PNG。
   *
   * @param vars compute ctx + 内置变量（car_name / car_model / window_label / generated_at）
   */
  public byte[] render(PlayDefinition play, Map<String, Object> vars) {
    if (!play.hasCard()) {
      throw new PlayRenderException("play '" + play.name() + "' 无 card 模板");
    }
    // 注入 watermark 内置变量（供 play 模板使用；putIfAbsent 不覆盖 compute 中同名变量）
    vars.putIfAbsent("watermark", watermark);
    String svg = substitute(play, play.cardTemplate(), vars);
    return transcode(svg);
  }

  /**
   * 数据查询失败兜底卡（内置模板）：SQL 层 DataAccessException（超时 / 列错 / 连接断）
   * 时 card.png 路径不裸 500 —— LLM 平台聊天流里一张「暂时无法生成」灰卡比破图标体面。
   * controller 层配 no-store 返回，绝不缓存错误卡。
   */
  public byte[] renderUnavailable(PlayDefinition play) {
    String svg =
        """
        <svg xmlns="http://www.w3.org/2000/svg" width="1080" height="1080" viewBox="0 0 1080 1080">
          <rect width="1080" height="1080" fill="#1E293B"/>
          <text x="540" y="120" text-anchor="middle" fill="#F1F5F9" font-size="56" font-weight="bold" font-family="Noto Sans CJK SC,sans-serif">%s</text>
          <text x="540" y="520" text-anchor="middle" fill="#94A3B8" font-size="64" font-weight="bold" font-family="Noto Sans CJK SC,sans-serif">卡片暂时无法生成</text>
          <text x="540" y="600" text-anchor="middle" fill="#64748B" font-size="30" font-family="Noto Sans CJK SC,sans-serif">数据查询暂时失败，请稍后再试一次。</text>
          <text x="540" y="1020" text-anchor="middle" fill="#64748B" font-size="24" font-family="Noto Sans CJK SC,sans-serif">%s</text>
        </svg>
        """
            .formatted(xmlEscape(play.title()), xmlEscape(watermark));
    return transcode(svg);
  }

  /** 数据不足兜底卡（内置模板，不依赖 play 自带模板的健壮性）。 */
  public byte[] renderInsufficient(PlayDefinition play, int sample, int minSample) {
    String svg =
        """
        <svg xmlns="http://www.w3.org/2000/svg" width="1080" height="1080" viewBox="0 0 1080 1080">
          <rect width="1080" height="1080" fill="#1E293B"/>
          <text x="540" y="120" text-anchor="middle" fill="#F1F5F9" font-size="56" font-weight="bold" font-family="Noto Sans CJK SC,sans-serif">%s</text>
          <text x="540" y="520" text-anchor="middle" fill="#94A3B8" font-size="64" font-weight="bold" font-family="Noto Sans CJK SC,sans-serif">数据不足</text>
          <text x="540" y="600" text-anchor="middle" fill="#64748B" font-size="30" font-family="Noto Sans CJK SC,sans-serif">当前窗口仅 %d 个采样点（需 ≥ %d），请扩大窗口或等待新行程。</text>
          <text x="540" y="1020" text-anchor="middle" fill="#64748B" font-size="24" font-family="Noto Sans CJK SC,sans-serif">%s</text>
        </svg>
        """
            .formatted(xmlEscape(play.title()), sample, minSample, xmlEscape(watermark));
    return transcode(svg);
  }

  // ====== 变量替换 ======

  private static String substitute(PlayDefinition play, String template, Map<String, Object> vars) {
    Matcher m = PLACEHOLDER_RE.matcher(template);
    StringBuilder sb = new StringBuilder(template.length() + 256);
    while (m.find()) {
      String path = m.group(1);
      Object value = resolvePath(path, vars);
      assertNumberTypeIfDeclared(play, path, value);
      m.appendReplacement(sb, Matcher.quoteReplacement(xmlEscape(format(value))));
    }
    m.appendTail(sb);
    return sb.toString();
  }

  private static Object resolvePath(String path, Map<String, Object> vars) {
    String[] segs = path.split("\\.");
    Object cur = vars;
    for (String seg : segs) {
      if (!(cur instanceof Map<?, ?> map) || !map.containsKey(seg)) {
        throw new PlayRenderException("模板变量 '${" + path + "}' 运行时解析失败");
      }
      cur = map.get(seg);
    }
    return cur;
  }

  private static void assertNumberTypeIfDeclared(PlayDefinition play, String path, Object value) {
    if (path.contains(".")) return;
    for (OutputField f : play.outputFields()) {
      if (f.name().equals(path) && "number".equals(f.type()) && !(value instanceof Number)) {
        throw new PlayRenderException("output 字段 '" + path + "' 声明 number 但运行时值非数字");
      }
    }
  }

  private static String format(Object v) {
    return PlayComputeEngine.formatValue(v);
  }

  static String xmlEscape(String s) {
    if (s == null) return "";
    StringBuilder sb = new StringBuilder(s.length() + 16);
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '&' -> sb.append("&amp;");
        case '<' -> sb.append("&lt;");
        case '>' -> sb.append("&gt;");
        case '"' -> sb.append("&quot;");
        case '\'' -> sb.append("&apos;");
        default -> sb.append(c);
      }
    }
    return sb.toString();
  }

  // ====== Batik 转码 ======

  private static byte[] transcode(String svg) {
    String lower = svg.toLowerCase(Locale.ROOT);
    if (lower.contains("<!doctype") || lower.contains("<!entity")) {
      throw new PlayRenderException("最终 SVG 含 DTD 字面，拒绝转码");
    }
    try {
      PNGTranscoder t = hardenedTranscoder();
      t.addTranscodingHint(SVGAbstractTranscoder.KEY_WIDTH, CANVAS);
      t.addTranscodingHint(SVGAbstractTranscoder.KEY_HEIGHT, CANVAS);
      TranscoderInput in =
          new TranscoderInput(
              new ByteArrayInputStream(svg.getBytes(StandardCharsets.UTF_8)));
      ByteArrayOutputStream baos = new ByteArrayOutputStream(160_000);
      t.transcode(in, new TranscoderOutput(baos));
      return baos.toByteArray();
    } catch (TranscoderException e) {
      throw new PlayRenderException("SVG → PNG 转码失败: " + e.getMessage(), e);
    }
  }

  /**
   * 安全加固的 PNGTranscoder：Batik 默认 UserAgent 会真去加载 SVG 引用的外部资源
   * （{@code <image href>} / CSS {@code url()} → SSRF / 本地文件读取面）。这里把
   * 外部资源与脚本安全策略都换成 no-load（双保险的第二道）。
   */
  private static PNGTranscoder hardenedTranscoder() {
    return new PNGTranscoder() {
      @Override
      protected UserAgent createUserAgent() {
        return new SVGAbstractTranscoderUserAgent() {
          @Override
          public ExternalResourceSecurity getExternalResourceSecurity(
              ParsedURL resourceURL, ParsedURL docURL) {
            return new NoLoadExternalResourceSecurity();
          }

          @Override
          public ScriptSecurity getScriptSecurity(
              String scriptType, ParsedURL scriptURL, ParsedURL docURL) {
            return new NoLoadScriptSecurity(scriptType);
          }
        };
      }
    };
  }
}
