package com.gcare.hms.radiology;

import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import tools.jackson.databind.json.JsonMapper;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ImagingArchiveProperties.class)
class RadiologyConfiguration {

  /** DICOMweb when a base URL is configured; otherwise an archive that reports "not configured". */
  @Bean
  ImagingArchive imagingArchive(
      ImagingArchiveProperties props,
      JsonMapper json,
      @Value("${gcare.imaging.archive.organization-id:}") String organizationId) {
    if (props.baseUrl() == null) {
      return new DicomWebArchive.NotConfigured();
    }
    if (organizationId.isBlank()) {
      // Single-organization development only: in a shared deployment every organization could
      // query this archive. Production binds it (docs/25, docs/27).
      LoggerFactory.getLogger(RadiologyConfiguration.class)
          .warn(
              "The imaging archive is not bound to an organization; set"
                  + " gcare.imaging.archive.organization-id before more than one organization uses"
                  + " this HMS");
    }
    return new DicomWebArchive(props, json);
  }

  /** The scheduled archive check, only when the archive is bound to an organization. */
  @Configuration(proxyBeanMethods = false)
  @EnableScheduling
  @ConditionalOnProperty(prefix = "gcare.imaging.archive", name = "organization-id")
  static class Sync {

    @Bean
    ImagingSyncJob imagingSyncJob(
        ImagingService service,
        @Value("${gcare.imaging.archive.organization-id}") java.util.UUID organizationId,
        @Value("${gcare.imaging.sync.lookback-days:2}") int lookbackDays) {
      return new ImagingSyncJob(service, organizationId, lookbackDays);
    }
  }
}
