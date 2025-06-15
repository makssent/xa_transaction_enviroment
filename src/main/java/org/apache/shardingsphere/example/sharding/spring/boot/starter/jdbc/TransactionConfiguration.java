package org.apache.shardingsphere.example.sharding.spring.boot.starter.jdbc;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import javax.sql.DataSource;

/**
 * Spring boot tx configuration.
 */
@Configuration
@EnableTransactionManagement
public class TransactionConfiguration {
    
    /**
     * Create platform transaction manager bean.
     *
     * @param dataSource data source
     * @return platform transaction manager
     */
    @Bean
    public PlatformTransactionManager txManager(final DataSource dataSource) {
        return new DataSourceTransactionManager(dataSource);
    }
}
