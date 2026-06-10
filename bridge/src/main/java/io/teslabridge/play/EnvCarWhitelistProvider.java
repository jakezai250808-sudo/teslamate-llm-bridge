package io.teslabridge.play;

import io.teslamate.play.CarWhitelistProvider;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Bridge 实现：从环境变量 {@code CAR_IDS}（逗号分隔）读取 car ID 白名单。
 *
 * <p>单机部署无多租户概念：{@code CAR_IDS} 未设置时放行所有 carId（单租户模式）；
 * 设置后仅允许列出的 carId，其余返回 {@code true}（outOfWhitelist = 拒绝）。
 *
 * <p>SaaS 侧实现（{@code SaasCarWhitelistProvider}）读 TenantInterceptor request attribute，
 * 两者共享同一接口 {@link CarWhitelistProvider}（io.teslamate.play 包，来自 play-engine-core）。
 */
@Component
public class EnvCarWhitelistProvider implements CarWhitelistProvider {

    private static final Logger log = LoggerFactory.getLogger(EnvCarWhitelistProvider.class);

    /** null = 放行全部（CAR_IDS 未设置）；non-null = 仅放行集合内的 carId。 */
    private final Set<Long> allowedCarIds;

    public EnvCarWhitelistProvider(@Value("${CAR_IDS:}") String carIds) {
        if (carIds == null || carIds.isBlank()) {
            this.allowedCarIds = null;
            log.info("EnvCarWhitelistProvider: CAR_IDS not set — all car IDs allowed (single-tenant mode)");
        } else {
            try {
                this.allowedCarIds =
                        Arrays.stream(carIds.split(","))
                                .map(String::trim)
                                .filter(s -> !s.isEmpty())
                                .map(Long::parseLong)
                                .collect(Collectors.toSet());
                log.info("EnvCarWhitelistProvider: restricting play engine to car IDs: {}", allowedCarIds);
            } catch (NumberFormatException e) {
                throw new IllegalStateException(
                        "CAR_IDS '" + carIds + "' contains non-numeric token — fix CAR_IDS.", e);
            }
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>返回 {@code true} 表示 carId 不在白名单（调用方返 404）；{@code false} 放行。
     */
    @Override
    public boolean outOfWhitelist(long carId) {
        if (allowedCarIds == null) return false;
        return !allowedCarIds.contains(carId);
    }
}
