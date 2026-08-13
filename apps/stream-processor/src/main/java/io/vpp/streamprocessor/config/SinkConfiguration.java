package io.vpp.streamprocessor.config;

import javax.sql.DataSource;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@Configuration
public class SinkConfiguration {
    @Bean
    DataSource clickHouseDataSource(StreamProperties properties) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("com.clickhouse.jdbc.ClickHouseDriver");
        dataSource.setUrl(properties.clickhouse().url());
        dataSource.setUsername(properties.clickhouse().username());
        dataSource.setPassword(properties.clickhouse().password());
        return dataSource;
    }
}
