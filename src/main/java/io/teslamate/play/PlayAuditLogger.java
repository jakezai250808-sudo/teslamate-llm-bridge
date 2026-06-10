/* (C) TeslaMate play-engine-core */
package io.teslamate.play;

/**
 * 玩法 API 调用审计日志接口。
 *
 * <p><b>SaaS 实现</b>（{@code com.teslaproxy.gateway.play.saas.SaasPlayAuditLogger}）：
 * 调用 {@code AuditService.log}，写 {@code audit_logs} 表，用户登录 dashboard 可查。
 *
 * <p><b>Bridge 实现</b>（{@code io.teslabridge.play.LogPlayAuditLogger}）：
 * {@code log.info(path)}，单机无数据库审计表。
 */
public interface PlayAuditLogger {

    /**
     * 记录一次 API 调用。调用方在请求入口调用，不影响主流程（实现不抛异常）。
     *
     * @param path 请求 path（不含 query string），审计可读形式，例如
     *     {@code /api/v1/cars/2/play/driving-personality}
     */
    void log(String path);
}
