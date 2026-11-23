package com.gcare.hms.shared.tenancy;

import javax.sql.DataSource;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires the row-level security model (ADR-010) into the application's DataSource and Flyway. */
@Configuration(proxyBeanMethods = false)
public class RowLevelSecurityConfiguration {

  /** Wraps the primary DataSource so every connection runs as the application role. */
  @Bean
  static BeanPostProcessor rowLevelSecurityDataSourcePostProcessor() {
    return new BeanPostProcessor() {
      @Override
      public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (bean instanceof DataSource dataSource
            && "dataSource".equals(beanName)
            && !(bean instanceof RowLevelSecurityDataSource)) {
          return new RowLevelSecurityDataSource(dataSource);
        }
        return bean;
      }
    };
  }

  /** Migrations create the application role, so they must run as the schema owner. */
  @Bean
  FlywayMigrationStrategy schemaOwnerMigrationStrategy() {
    return flyway -> OrganizationContext.callAsSchemaOwner(flyway::migrate);
  }
}
