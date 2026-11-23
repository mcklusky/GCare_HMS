# Local Keycloak realm (development only)

`gcare-realm.local.json` is imported by `docker compose up keycloak` (see the repository `docker-compose.yml`).
It exists so developers can sign in locally. **It is not a production configuration.**

- Realm `gcare`, issuer `http://localhost:8180/realms/gcare`.
- Redirect URIs: `http://localhost:5173/...` (through the Vite proxy, normal use), `:8080` and `:8082` (backend directly).
- Client `gcare-hms-backend`: confidential, authorization code flow with PKCE (S256), no implicit or password grants.
  The client secret `local-dev-only-not-a-secret` is a throwaway local value.
- Synthetic users, all with password `Local-Demo-Only-1!` (local only):

| Keycloak user | Keycloak ID (`sub`) | In GCare (local seed) | Expected result |
|---|---|---|---|
| `demo.admin` | `…1001` | DEMO-ORG, ORGANIZATION_ADMIN (organization-wide) | Signs in; can list facilities |
| `demo.auditor` | `…1002` | DEMO-ORG, AUDITOR | Signs in; facility list is 403 |
| `demo.unassigned` | `…1003` | DEMO-ORG, no roles | Signs in; sees only `/me` with no permissions |
| `demo.clerk` | `…1005` | DEMO-ORG, REGISTRATION_CLERK at DEMO-GH | Signs in; can search, register and correct patients |
| `demo.supervisor` | `…1006` | DEMO-ORG, REGISTRATION_SUPERVISOR at DEMO-GH | Clerk duties plus the duplicate review queue and link requests |
| `demo.records` | `…1007` | DEMO-ORG, HEALTH_RECORDS_MANAGER at DEMO-GH | Approves, rejects and reverses patient links (second person) |
| `demo.nurse` | `…1008` | DEMO-ORG, TRIAGE_NURSE at DEMO-GH | Sees queues, records triage observations |
| `demo.clinician` | `…1009` | DEMO-ORG, CLINICIAN at DEMO-GH | Calls in, transfers, closes and reopens visits; records observations |
| `demo.lab` | `…1010` | DEMO-ORG, SERVICE_DEPARTMENT_STAFF at DEMO-GH | Works the order worklist (start, complete, cancel) |
| `demo.finance` | `…1011` | DEMO-ORG, FINANCE_OFFICER at DEMO-GH | Billable services; proposes prices and tax rates |
| `demo.financemgr` | `…1012` | DEMO-ORG, FINANCE_MANAGER at DEMO-GH | Approves or rejects prices and tax rates (second person); voids issued invoices; signs off cashier shifts |
| `demo.cashier` | `…1013` | DEMO-ORG, CASHIER at DEMO-GH | Starts, edits and issues visit invoices; takes payments in a shift |
| `demo.unlinked` | `…1004` | not linked | Sign-in refused (`login_error=account_not_enabled`), audited |

Values such as brute-force limits, password policy and session lifetimes are **local defaults only**.
Production realm settings (MFA requirement, password policy, lockout, session lengths) must be approved
by the security owner and recorded in `docs/project-record/deployment-record.md` before pilot.

Never add real staff accounts or real names to this file.
