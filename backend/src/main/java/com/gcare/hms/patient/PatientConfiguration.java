package com.gcare.hms.patient;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MatchingProperties.class)
class PatientConfiguration {

  @Bean
  DuplicateScorer duplicateScorer(MatchingProperties matching) {
    return new DuplicateScorer(matching);
  }
}
