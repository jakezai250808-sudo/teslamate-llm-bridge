/* (C) TeslaMate play-engine-core */
package io.teslamate.play;

/**
 * play.yaml 加载 / 校验失败（schema 不合规、SQL 门禁不过、模板 lint 不过、表达式编译错）。
 *
 * <p>设计定稿 §2.1：任何一步校验失败 → {@code log.warn} 并继续 —— <b>坏 play 不挡启动</b>。
 */
public class PlayLoadException extends RuntimeException {
  public PlayLoadException(String message) {
    super(message);
  }

  public PlayLoadException(String message, Throwable cause) {
    super(message, cause);
  }
}
