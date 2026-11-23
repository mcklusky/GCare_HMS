# GCare HMS

Configurable hospital management system for multi-facility organizations. Pilot scope: patient registration, outpatient workflow, billing, and one validated radiology pathway.

**Stack (approved):** React + TypeScript · Java + Spring Boot modular monolith · PostgreSQL (Flyway) · external DICOM archive/PACS and diagnostic viewer.

## Status
Documentation and planning baseline only; application code has not been started. See [docs/README.md](docs/README.md) for the full document set and [docs/08-FIRST-SPRINT.md](docs/08-FIRST-SPRINT.md) for the first sprint.

## Repository layout
| Path | Contents |
|---|---|
| `docs/` | Requirements, architecture, data model, API, plan, test, safety and operations documents |
| `docs/detail/` | Detailed drafts (tables, endpoints, task acceptance criteria) |
| `docs/project-record/` | Site-specific approvals and evidence (templates until filled) |
| `GCare_HMS_PRD_v1_4.docx` | Superseded source PRD, kept for history |
| `backend/`, `frontend/` | Created in Phase 1 (not yet present) |

## Local setup
Not yet available. Phase 1 will add documented commands for building and running the backend and frontend from a fresh checkout. Copy `.env.example` to `.env` for local values; never commit `.env`.

## Data policy
Synthetic data only in development, demo and test environments. No real patient data, credentials or production extracts in this repository.
