package com.gcare.hms.organization;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.repository.Repository;

/** Read access only: organizations are created by platform operations, not the app role. */
interface OrganizationRepository extends Repository<Organization, UUID> {

  Optional<Organization> findById(UUID id);

  List<Organization> findAll();
}
