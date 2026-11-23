# GCare HMS — Claude Code instructions

Hospital management system. **Patient safety, data integrity and security take priority over speed.** This is not a CRUD app.

@docs/07-CLAUDE.md

## Where things are
- Requirements and plan: [docs/README.md](docs/README.md) (index), [docs/01-PRD.md](docs/01-PRD.md) (FR-001–FR-046), [docs/06-PLAN.md](docs/06-PLAN.md), [docs/16-TRACEABILITY.md](docs/16-TRACEABILITY.md).
- Column/endpoint/task detail: [docs/detail/](docs/detail/). Core docs win on conflict; known conflicts are listed in [docs/README.md](docs/README.md#detail-reconciliation-notes).
- Decisions: [docs/REVIEW.md](docs/REVIEW.md) (ADR register) and [docs/project-record/open-decisions.md](docs/project-record/open-decisions.md).
- `GCare_HMS_PRD_v1_4.docx` is superseded (it says Django). Do not build from it.

## Current state (2026-11-23)
- Documentation only; no application code yet. Next work is Phase 1 / [docs/08-FIRST-SPRINT.md](docs/08-FIRST-SPRINT.md).
- Blocking before the first migration and auth work: OD-01 (tenant vs organization naming), OD-02 (tenant isolation), OD-03 (identity provider), OD-04 (pinned versions). If still open, stop and ask; do not pick silently.
- Project-record files are empty templates. Never fill them with invented values.

## Working rules for this repo
- Reference FR IDs and plan task IDs in commits/PRs; update the Tests/Evidence columns in `docs/16-TRACEABILITY.md` only with tests that actually ran.
- Synthetic data only. Never commit `.env`, credentials, real patient data or production extracts.
