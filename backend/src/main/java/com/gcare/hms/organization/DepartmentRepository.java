package com.gcare.hms.organization;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Row-level security limits every query to the current organization (ADR-010). */
interface DepartmentRepository extends JpaRepository<Department, UUID> {

  List<Department> findByFacilityIdOrderByCode(UUID facilityId);
}
