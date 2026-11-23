# Deployment Record

> **Status: NOT PROVIDED.** Template only. Fill from approved, signed project artifacts. Do not enter assumed or example values. Real patient data never belongs here.

Required by ADR-007 and the "Before implementation" items in [08-FIRST-SPRINT.md](../08-FIRST-SPRINT.md).

| Item | Value | Source / approver | Date |
|---|---|---|---|
| Pilot facility and departments |  |  |  |
| Hosting model and location (on-premises / hosted) |  |  |  |
| Data residency constraint |  |  |  |
| Deployment topology (servers, reverse proxy, DB, archive) |  |  |  |
| Identity provider |  |  |  |
| Tenant isolation model |  |  |  |
| Facility timezone |  |  |  |
| Currencies in use |  |  |  |
| RPO (recovery point objective) |  |  |  |
| RTO (recovery time objective) |  |  |  |
| Backup method, frequency, off-site copy |  |  |  |
| Network diagram location |  |  |  |
| Power / UPS / generator arrangement |  |  |  |
| Monitoring and alert routing |  |  |  |
| Support owner |  |  |  |

## Environments

| Environment | Purpose | Data allowed | Location | Owner |
|---|---|---|---|---|
| Local/dev | Development | Synthetic only |  |  |
| Demo | Demonstrations | Synthetic only |  |  |
| Staging/UAT | Acceptance testing | Synthetic or approved de-identified |  |  |
| Production | Pilot | Real data only after launch authorization |  |  |
