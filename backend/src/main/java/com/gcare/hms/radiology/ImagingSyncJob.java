package com.gcare.hms.radiology;

import com.gcare.hms.shared.tenancy.OrganizationContext;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Scheduled archive check (task 6.3; docs/25). Active only when the archive is bound to an
 * organization ({@code gcare.imaging.archive.organization-id}); the interval and look-back are
 * deployment settings ({@code gcare.imaging.sync.interval}, {@code .lookback-days}).
 */
class ImagingSyncJob {

  private static final Logger LOG = LoggerFactory.getLogger(ImagingSyncJob.class);

  private final ImagingService service;
  private final UUID organizationId;
  private final int lookbackDays;

  ImagingSyncJob(ImagingService service, UUID organizationId, int lookbackDays) {
    this.service = service;
    this.organizationId = organizationId;
    this.lookbackDays = lookbackDays;
  }

  @Scheduled(
      fixedDelayString = "${gcare.imaging.sync.interval:PT5M}",
      initialDelayString = "${gcare.imaging.sync.initial-delay:PT1M}")
  void run() {
    try {
      var r =
          OrganizationContext.callAs(
              organizationId, () -> service.sweep(organizationId, lookbackDays));
      LOG.info(
          "Imaging archive check: {} studies seen, {} new, {} linked, {} waiting for review",
          r.studiesSeen(),
          r.newStudies(),
          r.linked(),
          r.unmatched());
    } catch (ImagingArchive.UnavailableException e) {
      LOG.warn("Imaging archive check failed: {}", e.getMessage());
    }
  }
}
