package com.gcare.hms.shared.tenancy;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * The organization the current thread is acting for (ADR-010).
 *
 * <p>{@link RowLevelSecurityDataSource} copies this into PostgreSQL on every connection checkout,
 * where row-level security policies use it. Set it <em>before</em> starting a transaction: a
 * transaction keeps the connection (and therefore the organization) it started with.
 *
 * <p>For web requests {@code CurrentUserFilter} (identity module) sets it from the signed-in user.
 * Platform code and tests set it explicitly.
 */
public final class OrganizationContext {

  private static final ThreadLocal<Mode> MODE = new ThreadLocal<>();

  private OrganizationContext() {}

  /** Runs {@code action} with queries restricted to {@code organizationId}. */
  public static <T> T callAs(UUID organizationId, Supplier<T> action) {
    Objects.requireNonNull(organizationId, "organizationId");
    return callWith(new Mode.Organization(organizationId), action::get);
  }

  public static void runAs(UUID organizationId, Runnable action) {
    callAs(
        organizationId,
        () -> {
          action.run();
          return null;
        });
  }

  /** Like {@link #callAs} for work that throws checked exceptions (for example servlet filters). */
  public static <T, E extends Exception> T callAsChecked(UUID organizationId, Work<T, E> action)
      throws E {
    Objects.requireNonNull(organizationId, "organizationId");
    return callWith(new Mode.Organization(organizationId), action);
  }

  /**
   * Runs {@code action} as the schema owner, bypassing the application role and row-level security.
   * Only for schema migrations and audited platform operations; never for request handling.
   */
  public static <T> T callAsSchemaOwner(Supplier<T> action) {
    return callWith(Mode.SchemaOwner.INSTANCE, action::get);
  }

  /** The organization in effect, if any. */
  public static Optional<UUID> currentOrganizationId() {
    return MODE.get() instanceof Mode.Organization org ? Optional.of(org.id()) : Optional.empty();
  }

  /** True inside {@link #callAsSchemaOwner}; platform operations use it as a guard. */
  public static boolean isSchemaOwner() {
    return MODE.get() == Mode.SchemaOwner.INSTANCE;
  }

  private static <T, E extends Exception> T callWith(Mode mode, Work<T, E> action) throws E {
    Mode previous = MODE.get();
    if (previous != null && !previous.equals(mode)) {
      throw new IllegalStateException(
          "Cannot switch organization context inside another context; "
              + "finish the current unit of work first");
    }
    MODE.set(mode);
    try {
      return action.get();
    } finally {
      if (previous == null) {
        MODE.remove();
      }
    }
  }

  /** A unit of work that may throw a checked exception. */
  @FunctionalInterface
  public interface Work<T, E extends Exception> {
    T get() throws E;
  }

  private sealed interface Mode {
    record Organization(UUID id) implements Mode {}

    enum SchemaOwner implements Mode {
      INSTANCE
    }
  }
}
