package io.teslabridge.play;

/**
 * compute 流水线运行时错误（未知标识符 / 类型不符 / lookup 无 default 兜底）。
 *
 * <p>设计定稿 §1.1：该请求 500 {@code PLAY_COMPUTE_ERROR} + ERROR log（由
 * {@link PlayController} 转换），不影响其它 play / 其它请求。
 */
public class PlayComputeException extends RuntimeException {
    public PlayComputeException(String message) {
        super(message);
    }
}
