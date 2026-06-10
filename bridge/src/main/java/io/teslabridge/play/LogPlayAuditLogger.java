package io.teslabridge.play;

import io.teslamate.play.PlayAuditLogger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Bridge 实现：{@code log.info} 轻量审计日志。
 *
 * <p>单机部署无数据库审计表：每次 API 调用打 INFO 日志，供 docker logs / ELK 收集。
 *
 * <p>SaaS 侧实现（{@code SaasPlayAuditLogger}）调用 {@code AuditService.log} 写
 * {@code audit_logs} 表，用户登录 dashboard 可查。两者共享接口
 * {@link PlayAuditLogger}（来自 play-engine-core）。
 */
@Component
public class LogPlayAuditLogger implements PlayAuditLogger {

    private static final Logger log = LoggerFactory.getLogger(LogPlayAuditLogger.class);

    /**
     * {@inheritDoc}
     *
     * <p>不抛异常（接口约定），实现里 log.info 不会抛。
     */
    @Override
    public void log(String path) {
        log.info("play-api call: {}", path);
    }
}
