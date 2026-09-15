package com.qskj.get_geo_pg.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

@Configuration
public class GraphsDataSourceConfig {

    // 1. 主数据源 (mdb1)：供 MyBatis-Plus、FungisUserMapper 等主业务表使用
    @Value("${spring.datasource.url}")
    private String primaryUrl;

    @Value("${spring.datasource.username}")
    private String primaryUsername;

    @Value("${spring.datasource.password}")
    private String primaryPassword;

    @Value("${spring.datasource.driver-class-name}")
    private String primaryDriverClassName;

    @Primary
    @Bean(name = "dataSource")
    public DataSource primaryDataSource() {
        return DataSourceBuilder.create()
                .url(primaryUrl)
                .username(primaryUsername)
                .password(primaryPassword)
                .driverClassName(primaryDriverClassName)
                .build();
    }

    @Primary
    @Bean(name = "jdbcTemplate")
    public JdbcTemplate primaryJdbcTemplate() {
        return new JdbcTemplate(primaryDataSource());
    }

    // 2. 额外数据源 (graphs)：专供 pgRouting 路径规划与路网分析使用
    @Value("${graphs.datasource.url}")
    private String url;

    @Value("${graphs.datasource.username}")
    private String username;

    @Value("${graphs.datasource.password}")
    private String password;

    @Value("${graphs.datasource.driver-class-name}")
    private String driverClassName;

    @Bean(name = "graphsDataSource")
    public DataSource graphsDataSource() {
        return DataSourceBuilder.create()
                .url(url)
                .username(username)
                .password(password)
                .driverClassName(driverClassName)
                .build();
    }

    @Bean(name = "graphsJdbcTemplate")
    public JdbcTemplate graphsJdbcTemplate() {
        return new JdbcTemplate(graphsDataSource());
    }
}
