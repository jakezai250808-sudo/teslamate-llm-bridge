package io.teslabridge.config;

import io.teslamate.play.PlayCardRenderer;
import io.teslamate.play.PlayEngine;
import io.teslamate.play.PlayRegistry;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Bridge wiring configuration for play-engine-core beans.
 *
 * <p>play-engine-core classes are NOT registered via ComponentScan — the core is designed to be
 * injection-framework-agnostic ({@code PlayEngine} has no {@code @Component}; {@code PlayRegistry}
 * and {@code PlayCardRenderer} have it but rely on Spring-managed construction). Bridge controls
 * all wiring explicitly here, which avoids Spring Boot 3 auto-wiring limitations with
 * {@code @Value}-only constructors scanned from outside the boot package.
 *
 * <p>DataSource qualifier names ({@code teslamateJdbc} / {@code teslamateTx}) are kept for
 * symmetry with the SaaS multi-datasource setup; in bridge there is only one DataSource.
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
     * PlayEngine: no @Component in core (injection-framework-agnostic). Bridge provides the
     * single DataSource via qualified beans matching the SaaS convention.
     */
    @Bean
    public PlayEngine playEngine(
            @Qualifier("teslamateJdbc") JdbcTemplate jdbcTemplate,
            @Qualifier("teslamateTx") PlatformTransactionManager txManager) {
        return new PlayEngine(jdbcTemplate, txManager);
    }

    /**
     * PlayRegistry: has @Component in core but depends on @Value("${PLAYS_DIR:}") constructor
     * param — Spring Boot 3 requires explicit @Autowired or explicit @Bean for cross-package
     * @Value injection. Registered here explicitly, using the same default as the @Component
     * constructor (empty string = classpath-only mode).
     */
    @Bean
    public PlayRegistry playRegistry(@Value("${PLAYS_DIR:}") String playsDir) {
        return new PlayRegistry(playsDir);
    }

    /**
     * PlayCardRenderer: has @Component in core but similarly requires cross-package @Value
     * injection of play.card-watermark. Registered explicitly.
     */
    @Bean
    public PlayCardRenderer playCardRenderer(
            @Value("${play.card-watermark:}") String watermark) {
        return new PlayCardRenderer(watermark);
    }
}
