package io.teslabridge.config;

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
}
