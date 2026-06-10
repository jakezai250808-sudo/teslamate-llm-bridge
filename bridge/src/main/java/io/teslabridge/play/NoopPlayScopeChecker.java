package io.teslabridge.play;

import io.teslamate.play.PlayDefinition;
import io.teslamate.play.PlayScopeChecker;
import org.springframework.stereotype.Component;

/**
 * Bridge 实现：永远返回 {@code false}（放行），无 OAuth scope 鉴权概念。
 *
 * <p>bridge 是单机单租户部署，鉴权由 {@link io.teslabridge.auth.StaticTokenFilter}
 * 在 HTTP 层做 Bearer API_TOKEN 校验，不需要 play 级别的 OAuth scope 校验。
 *
 * <p>SaaS 侧实现（{@code SaasPlayScopeChecker}）读 {@code TenantInterceptor.ATTR_OAUTH_SCOPES}
 * request attribute，与 {@code play.requiredScopes()} 对比，实现精细 scope 鉴权。
 * 两者共享接口 {@link PlayScopeChecker}（来自 play-engine-core）。
 */
@Component
public class NoopPlayScopeChecker implements PlayScopeChecker {

    /**
     * {@inheritDoc}
     *
     * <p>Bridge 始终返回 {@code false}：scope 充分（即无需校验，放行所有 play）。
     */
    @Override
    public boolean insufficientScope(PlayDefinition play) {
        return false;
    }
}
