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
      @Value("${gcare.imaging.archive.organization-id:}") String organizationId,
      @Value("${gcare.imaging.archive.allow-any-organization:false}")
          boolean allowAnyOrganization) {
    if (props.baseUrl() == null) {
      return new DicomWebArchive.NotConfigured();
    }
    if (organizationId.isBlank()) {
      // Fail closed (docs/25): an archive not bound to an organization could be queried, and its
      // studies linked, by every organization of a shared HMS. Only automated tests opt out.
      if (!allowAnyOrganization) {
        throw new IllegalStateException(
            "gcare.imaging.archive.base-url is set but gcare.imaging.archive.organization-id is"
                + " not: bind the archive to the organization that owns it");
      }
      LoggerFactory.getLogger(RadiologyConfiguration.class)
          .warn("The imaging archive is not bound to an organization (allow-any-organization)");
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
