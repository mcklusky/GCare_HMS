# 12 — Clinical Safety and Downtime Plan

## Governance
Name a clinical safety owner and module-specific clinical reviewers. Maintain a hazard log with hazard, clinical context, severity, likelihood, mitigation, residual risk, owner, evidence and review date. Review hazards at design changes, UAT, incidents and releases. This document supports governance and is not a clinical safety certification.

## Minimum hazards to assess
Wrong-patient registration/order/result; duplicate or silently merged identity; lost or delayed critical result; unsigned report appearing final; unauthorized signing; incorrect tariff/eligibility disrupting care; duplicate payment; downtime record lost or duplicated; stale/inaccurate data; unavailable system or archive; unauthorized emergency access; alert fatigue.

## Safe workflow controls
Confirm identity at patient-specific actions; expose clear status and timestamps; preserve signed versions and amendments; show result ownership and acknowledgement; require reasons and approval for break-glass and financial correction; prevent uncertain DICOM matching from finalizing; distinguish draft from final; provide escalation path for critical results.

## Downtime procedure
Facility approves trigger, decision authority, paper forms, unique temporary identifiers, minimum documentation, custody/storage, privacy protections, communication tree, clinical prioritization, and conditions for restoration. Care continues under facility policy; the software must not be a prerequisite for emergency treatment.

## Recovery and reconciliation
Record outage start/end, affected locations, temporary identifiers, orders/results, payments and responsible staff. On recovery, enter/reconcile using duplicate checks and source references; second-person review high-risk items. Resolve conflicts without overwriting original paper/electronic history. Confirm critical results and outstanding tasks. Document unresolved items and closeout approval.

## Drills and incident learning
Run scheduled downtime and restore drills before pilot and after material changes. Capture duration, missed steps, discrepancies, corrective actions and owners. Report incidents through approved facility process; preserve relevant audit and operational evidence.
