package com.gcare.hms.organization;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Row-level security limits every query to the current organization (ADR-010). */
interface FacilityRepository extends JpaRepository<Facility, UUID> {}
