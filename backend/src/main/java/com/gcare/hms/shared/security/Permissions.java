package com.gcare.hms.shared.security;

import java.util.Set;

/**
 * Permission codes checked in code. The same codes are in the {@code permissions} table (migration
 * V2); a test keeps both in step. Roles are sets of these codes, assigned per organization or per
 * facility.
 */
public final class Permissions {

  public static final String FACILITY_READ = "FACILITY_READ";
  public static final String FACILITY_MANAGE = "FACILITY_MANAGE";
  public static final String USER_READ = "USER_READ";
  public static final String USER_MANAGE = "USER_MANAGE";
  public static final String AUDIT_READ = "AUDIT_READ";
  public static final String PATIENT_READ = "PATIENT_READ";
  public static final String PATIENT_REGISTER = "PATIENT_REGISTER";

  public static final Set<String> ALL =
      Set.of(
          FACILITY_READ,
          FACILITY_MANAGE,
          USER_READ,
          USER_MANAGE,
          AUDIT_READ,
          PATIENT_READ,
          PATIENT_REGISTER);

  private Permissions() {}
}
