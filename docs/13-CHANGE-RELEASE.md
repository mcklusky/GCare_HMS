# 13 — Change Control and Release Management

## Change classes
Product requirement/clinical workflow; API/contract; database migration; facility configuration/tariff; security/access; infrastructure/dependency; production release. Classify impact and identify clinical, finance, privacy, security, operations and customer reviewers as applicable.

## Change record
Every material change includes rationale, requirement/decision reference, affected users/data, risk and rollback assessment, compatibility/migration plan, test evidence, documentation updates, approvers, target environment and release notes. Clinical workflow, tariff, permission and retention changes require designated owners' approval.

## Software release
Use reviewed pull requests, protected branches, CI gates, tagged immutable artifacts and environment promotion. Apply backward-compatible migrations where possible; rehearse risky changes and retain rollback/restore plan. Production deployment is authorized by release owner and customer change window. Verify health, alerts, key workflows and backup after deployment; define rollback decision authority.

## Configuration release
Version and stage configuration changes; separate author and approver for tariffs/permissions where policy requires. Preserve effective dates and historical snapshots. Test in staging with synthetic scenarios; publish with audit trail and rollback to prior approved version.

## Emergency changes and incidents
Emergency change needs documented reason, authorized approver, scoped access, minimum implementation, post-change review and evidence. Incident response follows the operations runbook and applicable customer notification rules. Record lessons and corrective actions.
