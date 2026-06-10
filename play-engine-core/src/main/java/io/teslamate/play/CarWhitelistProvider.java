/* (C) TeslaMate play-engine-core */
package io.teslamate.play;

/**
 * 查询指定 carId 是否在当前请求上下文的 car 白名单里（租户隔离核心校验）。
 *
 * <p><b>SaaS 实现</b>（{@code com.teslaproxy.gateway.play.saas.SaasCarWhitelistProvider}）：
 * 读 {@code TenantInterceptor} 在 request attribute 上写入的 carIdWhitelist，实现租户过滤。
 *
 * <p><b>Bridge 实现</b>（{@code io.teslabridge.play.EnvCarWhitelistProvider}）：
 * 读 env {@code CAR_IDS}，单机部署无多租户。
 */
public interface CarWhitelistProvider {

    /**
     * 返回 {@code true} 表示 carId 不在当前请求的白名单里，调用方应返回 404 NOT_FOUND
     * （与跨租户同语义，不泄露资源存在性）。
     *
     * @param carId TeslaMate cars.id
     * @return true = carId 越界 / 无权限（调用方返 404）；false = 正常放行
     */
    boolean outOfWhitelist(long carId);
}
