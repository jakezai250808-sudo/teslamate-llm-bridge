package io.teslabridge.config;

import io.teslamate.play.PlayEngine;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Exposes the TeslaMate DataSource under the qualifier names expected by the play engine:
 * {@code teslamateJdbc} and {@code teslamateTx}.
 *
 * <p>Bridge uses the single auto-configured DataSource (pointing at TeslaMate PG).
 * The qualifier names match those used by {@code PlayEngine} so engine classes need zero changes.
 *
 * <p>Also registers {@link PlayEngine} as a Spring bean: {@code PlayEngine} in play-engine-core
 * is not annotated with {@code @Component} (to stay injection-framework-agnostic in core);
 * bridge wires it here via an explicit {@code @Bean} method, injecting the qualified beans.
 */
@Configuration
public class TeslamateDataSource {

    @Bean
    @Qualifier("teslamateJdbc")
    public JdbcTemplate teslamateJdbc(DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    @Bean
    @Qualifier("teslamateTx")
    public PlatformTransactionManager teslamateTx(DataSource dataSource) {
        return new DataSourceTransactionManager(dataSource);
    }

    /**
     * PlayEngine bean for bridge: single datasource, so inject the same teslamateJdbc /
     * teslamateTx beans (bridge has only one DataSource, both qualifiers resolve to the same
     * underlying source).
     */
    @Bean
    public PlayEngine playEngine(
            @Qualifier("teslamateJdbc") JdbcTemplate jdbcTemplate,
            @Qualifier("teslamateTx") PlatformTransactionManager txManager) {
        return new PlayEngine(jdbcTemplate, txManager);
    }
}
