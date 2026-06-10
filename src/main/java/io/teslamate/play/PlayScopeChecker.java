/* (C) TeslaMate play-engine-core */
package io.teslamate.play;

/**
 * 检查当前请求的 OAuth token 是否具备运行指定 play 所需的全部 scope。
 *
 * <p><b>SaaS 实现</b>（{@code com.teslaproxy.gateway.play.saas.SaasPlayScopeChecker}）：
 * 读 {@code TenantInterceptor.ATTR_OAUTH_SCOPES} request attribute，与
 * {@code play.requiredScopes()} 对比，实现 OAuth Bearer 路径的精细 scope 鉴权；
 * {@code mtp_} / session 路径该 attribute 为 null，直接放行（这些路径凭据代表全权）。
 *
 * <p><b>Bridge 实现</b>（{@code io.teslabridge.play.NoopPlayScopeChecker}）：
 * 永远返回 {@code false}（无 OAuth 概念，bridge 单机无多租户鉴权）。
 */
public interface PlayScopeChecker {

    /**
     * 返回 {@code true} 表示当前 token scope 不足，调用方应返回 403 FORBIDDEN。
     *
     * @param play 即将执行的玩法定义
     * @return true = scope 不足（调用方返 403）；false = scope 充分或无需校验（放行）
     */
    boolean insufficientScope(PlayDefinition play);
}
