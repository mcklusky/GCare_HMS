/**
 * Cross-cutting infrastructure: security configuration, error handling, correlation IDs. No
 * business rules.
 *
 * <p>Module boundary: other modules call this module through its application services or events,
 * never through its repositories (docs/03-ARCHITECTURE.md).
 */
package com.gcare.hms.shared;
