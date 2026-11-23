# GCare HMS

Configurable hospital management system for multi-facility organizations. Pilot scope: patient registration, outpatient workflow, billing, and one validated radiology pathway.

**Stack (approved):** React + TypeScript · Java + Spring Boot modular monolith · PostgreSQL (Flyway) · external DICOM archive/PACS and diagnostic viewer.

## Status
Phase 1 foundation in progress: backend and frontend skeletons, local PostgreSQL, CI. There are no clinical features yet, and every API request is denied until authentication lands (task 1.3). See [docs/README.md](docs/README.md) for the document set and [docs/08-FIRST-SPRINT.md](docs/08-FIRST-SPRINT.md) for sprint progress.

## Repository layout
| Path | Contents |
|---|---|
| `docs/` | Requirements, architecture, data model, API, plan, test, safety and operations documents |
| `docs/detail/` | Detailed drafts (tables, endpoints, task acceptance criteria) |
| `docs/project-record/` | Site-specific approvals and evidence (templates until filled) |
| `GCare_HMS_PRD_v1_4.docx` | Superseded source PRD, kept for history |
| `backend/` | Spring Boot 4.2 / Java 21 modular monolith (Maven wrapper) |
| `frontend/` | React 19 + TypeScript + Vite |
| `docker-compose.yml` | Local PostgreSQL 18 |
| `.github/` | CI workflow and Dependabot |

## Local setup
See [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md). In short: `docker compose up -d postgres`, then `cd backend && ./mvnw spring-boot:run`, then `cd frontend && npm ci && npm run dev`.

## Data policy
Synthetic data only in development, demo and test environments. No real patient data, credentials or production extracts in this repository.
