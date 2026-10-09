package com.zachery.cms.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.*;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.zachery.cms.modules.identity.persistence.IdentityAuditFillHandler;
import com.zaxxer.hikari.*;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import javax.sql.DataSource;

@Configuration(proxyBeanMethods = false)
@EnableTransactionManagement(proxyTargetClass = true)
@MapperScan({"com.zachery.cms.modules.identity.persistence.mapper", "com.zachery.cms.modules.content.persistence.mapper"})
public class PersistenceConfiguration {
    @Bean(destroyMethod = "close") public HikariDataSource dataSource(Environment env) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(env.getRequiredProperty("cms.db.url"));
        config.setUsername(env.getRequiredProperty("cms.db.username"));
        config.setPassword(env.getProperty("cms.db.password", ""));
        config.setDriverClassName(env.getRequiredProperty("cms.db.driver"));
        config.setMaximumPoolSize(10);
        config.setConnectionTimeout(10000);
        return new HikariDataSource(config);
    }
    @Bean public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());
        PaginationInnerInterceptor pagination = new PaginationInnerInterceptor(DbType.MYSQL);
        pagination.setMaxLimit(100L);
        interceptor.addInnerInterceptor(pagination);
        return interceptor;
    }
    @Bean public SqlSessionFactory sqlSessionFactory(DataSource source, IdentityAuditFillHandler audit, ObjectProvider<Interceptor> plugins) throws Exception {
        MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(source);
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        factory.setConfiguration(configuration);
        GlobalConfig global = new GlobalConfig();
        GlobalConfig.DbConfig database = new GlobalConfig.DbConfig();
        database.setLogicDeleteValue("1"); database.setLogicNotDeleteValue("0");
        global.setDbConfig(database); global.setMetaObjectHandler(audit);
        factory.setGlobalConfig(global);
        factory.setMapperLocations(new PathMatchingResourcePatternResolver().getResources("classpath*:mapper/**/*.xml"));
        factory.setPlugins(plugins.orderedStream().toArray(Interceptor[]::new));
        return factory.getObject();
    }
    @Bean public DataSourceTransactionManager transactionManager(DataSource source) { return new DataSourceTransactionManager(source); }
    @Bean public JdbcTemplate jdbcTemplate(DataSource source) { return new JdbcTemplate(source); }
}
