package com.gcare.hms.radiology;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ImagingArchiveProperties.class)
class RadiologyConfiguration {

  /** DICOMweb when a base URL is configured; otherwise an archive that reports "not configured". */
  @Bean
  ImagingArchive imagingArchive(ImagingArchiveProperties props, JsonMapper json) {
    return props.baseUrl() == null
        ? new DicomWebArchive.NotConfigured()
        : new DicomWebArchive(props, json);
  }
}
