# 05 — API Specification (Detailed Draft)

> **Status:** Detailed draft 1.0, restored verbatim from the drafts embedded in `GCare_HMS_PRD_v1_4.docx`.
> **Precedence:** [05-API.md](../05-API.md) and [REVIEW.md](../REVIEW.md) are the approved baseline. Where this detail conflicts with them, the baseline wins; record the conflict in REVIEW.md. See [../README.md](../README.md#detail-reconciliation-notes) for known reconciliation notes.

The API will be the contract between React and Spring Boot. Claude should implement against this contract rather than inventing endpoints independently in each feature.

> Source metadata: 05 — API Specification Version: 1.0 Status: Draft for technical review API style: REST / JSON Base path: /api/v1

## 1. Purpose

Define the API conventions and initial endpoint catalogue used by the React frontend and Java Spring Boot backend.

The API is the official communication contract. Frontend and backend developers must not create undocumented endpoints or bypass backend business rules.

## 2. General API conventions

- Base path: /api/v1
- Request and response format: JSON unless an endpoint explicitly supports file upload/download.
- Use HTTPS in production.
- Use UUIDs for internal resource identifiers.
- Use ISO 8601 timestamps.
- Use ISO date format YYYY-MM-DD for dates.
- Use explicit currency codes for financial amounts.
- Use pagination for list endpoints.
- Validate every request on the backend.
- Return consistent error structures.
- Protect all endpoints except explicitly public authentication or health endpoints.
- Apply tenant, facility, department and role checks on the backend.

## 3. Authentication endpoints

| Method | Endpoint | Purpose |
|---|---|---|
| POST | /auth/login | Authenticate a user |
| POST | /auth/logout | End the current session |
| POST | /auth/refresh | Refresh authentication where applicable |
| GET | /auth/me | Get current user and permitted scopes |
| POST | /auth/change-password | Change password |
| POST | /auth/forgot-password | Start approved password recovery |
| POST | /auth/reset-password | Complete approved password recovery |

The final session/token mechanism must be selected before implementation. Login responses must not expose password hashes, secrets or internal security configuration.

## 4. Organization and facility endpoints

| Method | Endpoint | Purpose |
|---|---|---|
| GET | /organizations | List organizations available to the caller |
| GET | /organizations/{id} | Organization details |
| POST | /organizations | Create organization, platform-authorized |
| GET | /facilities | List permitted facilities |
| POST | /facilities | Create facility |
| GET | /facilities/{id} | Facility details |
| PUT | /facilities/{id} | Update permitted facility settings |
| GET | /departments | List permitted departments |
| POST | /departments | Create department |
| GET | /service-points | List service points |
| POST | /service-points | Create service point |

All organization and facility operations require appropriate administrative permissions.

## 5. User and permission endpoints

| Method | Endpoint | Purpose |
|---|---|---|
| GET | /users | Search/list users within permitted scope |
| POST | /users | Create user |
| GET | /users/{id} | User details |
| PUT | /users/{id} | Update user |
| PATCH | /users/{id}/status | Activate/disable user |
| GET | /roles | List roles |
| POST | /roles | Create permitted role |
| PUT | /roles/{id} | Update role |
| GET | /permissions | List permission definitions |
| POST | /users/{id}/role-assignments | Assign role and scope |
| DELETE | /users/{id}/role-assignments/{assignmentId} | Revoke assignment |

Revoking an assignment must not erase its history.

## 6. Patient endpoints

| Method | Endpoint | Purpose |
|---|---|---|
| GET | /patients | Search patients |
| POST | /patients | Register patient |
| GET | /patients/{id} | Retrieve patient profile |
| PUT | /patients/{id} | Update permitted demographics |
| GET | /patients/{id}/identifiers | List permitted identifiers |
| POST | /patients/{id}/identifiers | Add identifier |
| GET | /patients/{id}/encounters | Patient encounter history |
| GET | /patients/{id}/documents | List authorized documents |
| POST | /patients/{id}/documents | Upload approved document |
| POST | /patients/duplicate-check | Check possible duplicate records |
| POST | /patients/merge-requests | Request patient merge |
| POST | /patients/merge-requests/{id}/approve | Approve merge |
| POST | /patients/merge-requests/{id}/reverse | Request/approve reversal under policy |

Patient search parameters

```text
GET /patients
```

Supported parameters may include:

```text
patientNumber
firstName
lastName
phone
dateOfBirth
identifier
facilityId
page
size
sort
```

Search must enforce the caller's access scope. Possible matches must be presented before creating a new record. The backend must not automatically merge records based on a similarity score.

Example registration request

```json
{
  "firstName": "Ama",
  "middleName": null,
  "lastName": "Mensah",
  "dateOfBirth": "1990-05-12",
  "birthDateEstimated": false,
  "phoneNumber": "0000000000",
  "preferredLanguage": "en",
  "primaryFacilityId": "00000000-0000-0000-0000-000000000001"
}
```

The example is synthetic. The backend must generate the patient number and validate the facility scope.

## 7. Encounter endpoints

| Method | Endpoint | Purpose |
|---|---|---|
| GET | /encounters | Search encounters |
| POST | /encounters | Open encounter |
| GET | /encounters/{id} | Encounter details |
| PUT | /encounters/{id} | Update permitted encounter fields |
| POST | /encounters/{id}/transfer | Transfer encounter |
| POST | /encounters/{id}/close | Close encounter |
| POST | /encounters/{id}/reopen | Reopen under permission |
| GET | /encounters/{id}/timeline | Encounter activity timeline |

Encounter status transitions must be validated by the backend.

## 8. Triage and observations endpoints

| Method | Endpoint | Purpose |
|---|---|---|
| POST | /encounters/{id}/triage | Record triage |
| GET | /encounters/{id}/triage | Retrieve triage history |
| POST | /encounters/{id}/observations | Add observation/vital |
| GET | /encounters/{id}/observations | List observations |
| PUT | /observations/{id} | Correct permitted observation under policy |

Observation corrections must preserve the original value and audit history.

## 9. Clinical record endpoints

| Method | Endpoint | Purpose |
|---|---|---|
| GET | /encounters/{id}/clinical-notes | List notes |
| POST | /encounters/{id}/clinical-notes | Create draft note |
| GET | /clinical-notes/{id} | Retrieve note |
| PUT | /clinical-notes/{id} | Update draft note |
| POST | /clinical-notes/{id}/sign | Sign note |
| POST | /clinical-notes/{id}/amendments | Create amendment |
| GET | /encounters/{id}/diagnoses | List diagnoses |
| POST | /encounters/{id}/diagnoses | Add diagnosis |

Only authorized users may sign. A signed note must not be edited as though it were still a draft.

## 10. Orders and referrals endpoints

| Method | Endpoint | Purpose |
|---|---|---|
| GET | /orders | Search orders |
| POST | /orders | Create order |
| GET | /orders/{id} | Order details |
| POST | /orders/{id}/accept | Accept order |
| POST | /orders/{id}/cancel | Cancel order under policy |
| POST | /orders/{id}/complete | Mark completed |
| GET | /referrals | Search referrals |
| POST | /referrals | Create referral |
| POST | /referrals/{id}/status | Update referral status |

Order types and permitted status transitions must be centrally defined and validated.

## 11. Laboratory endpoints

| Method | Endpoint | Purpose |
|---|---|---|
| GET | /laboratory/worklist | Laboratory queue |
| GET | /laboratory/orders/{id} | Laboratory order details |
| POST | /laboratory/orders/{id}/specimens | Record specimen |
| POST | /laboratory/specimens/{id}/collection | Record collection |
| POST | /laboratory/orders/{id}/results | Enter results |
| POST | /laboratory/results/{id}/validate | Validate result |
| POST | /laboratory/results/{id}/corrections | Correct result |
| GET | /laboratory/results/{id} | Retrieve result |

Result validation and correction must require appropriate permissions and generate audit events.

## 12. Radiology endpoints

| Method | Endpoint | Purpose |
|---|---|---|
| GET | /radiology/worklist | Imaging worklist |
| GET | /radiology/orders/{id} | Imaging order |
| POST | /radiology/orders/{id}/schedule | Schedule study |
| GET | /radiology/studies | Search studies |
| GET | /radiology/studies/{id} | Study metadata |
| POST | /radiology/studies/{id}/reconcile | Resolve study matching |
| POST | /radiology/studies/{id}/viewer-launch | Request authorized viewer launch |
| POST | /radiology/studies/{id}/reports | Create report draft |
| POST | /radiology/reports/{id}/sign | Finalize report |
| POST | /radiology/reports/{id}/amendments | Create report amendment |
| POST | /radiology/reports/{id}/critical-notifications | Record critical-result communication |

DICOM archive integration endpoints must be separately secured. Do not expose archive credentials or unrestricted archive access to the browser.

## 13. Billing endpoints

| Method | Endpoint | Purpose |
|---|---|---|
| GET | /billing/service-catalog | List billable services |
| POST | /billing/service-catalog | Add service |
| GET | /billing/tariffs | List tariff catalogs |
| POST | /billing/tariffs | Create tariff catalog |
| POST | /billing/invoices | Create invoice |
| GET | /billing/invoices/{id} | Invoice details |
| POST | /billing/invoices/{id}/items | Add invoice item |
| POST | /billing/invoices/{id}/issue | Issue invoice |
| POST | /billing/invoices/{id}/payments | Record payment |
| POST | /billing/payments/{id}/reversals | Request payment reversal |
| POST | /billing/invoices/{id}/adjustments | Request approved adjustment |
| GET | /billing/receipts/{id} | Retrieve receipt |
| GET | /billing/reconciliation | Cashier reconciliation data |

Financial endpoints must enforce authorization, transaction integrity and duplicate-submission protection. A refund or reversal must not simply delete the original payment.

## 14. Pharmacy and inventory endpoints

| Method | Endpoint | Purpose |
|---|---|---|
| GET | /pharmacy/medications | Medication catalog |
| POST | /pharmacy/prescriptions | Create prescription |
| GET | /pharmacy/prescriptions/{id} | Prescription details |
| POST | /pharmacy/prescriptions/{id}/dispense | Dispense medication |
| GET | /inventory/items | Inventory item search |
| POST | /inventory/items | Create inventory item |
| GET | /inventory/stores | List stores |
| POST | /inventory/transactions | Record stock movement |
| GET | /inventory/transactions | View stock movement history |
| POST | /inventory/stocktakes | Create stocktake |
| POST | /inventory/stocktakes/{id}/complete | Complete stocktake |

These modules may be implemented after the MVP according to the release plan.

## 15. Configuration and module endpoints

| Method | Endpoint | Purpose |
|---|---|---|
| GET | /configuration | Read permitted effective configuration |
| GET | /configuration/versions | List configuration versions |
| POST | /configuration/changes | Propose configuration change |
| POST | /configuration/changes/{id}/approve | Approve change |
| POST | /configuration/changes/{id}/publish | Publish approved change |
| POST | /configuration/versions/{id}/rollback | Roll back under policy |
| GET | /modules | List module catalog |
| GET | /organizations/{id}/modules | List enabled modules |
| PUT | /organizations/{id}/modules | Update module entitlement, platform-authorized |
| GET | /branding | Read branding configuration |
| PUT | /branding | Update permitted branding |

A configuration change must preserve history and identify the person who approved and published it.

## 16. Reports and audit endpoints

| Method | Endpoint | Purpose |
|---|---|---|
| GET | /reports | List available reports |
| POST | /reports/{reportCode}/run | Run authorized report |
| GET | /reports/jobs/{id} | Check report job |
| GET | /audit/events | Search permitted audit events |
| GET | /audit/patients/{patientId} | Patient access history |
| GET | /audit/exports | Export history |

Report exports must enforce field-level and record-level access controls and be audited.

## 17. Pagination and filtering

List endpoints should use consistent pagination.

Example:

```text
GET /api/v1/patients?page=0&size=20&sort=lastName,asc
```

Example response:

```json
{
  "content": [],
  "page": 0,
  "size": 20,
  "totalElements": 0,
  "totalPages": 0
}
```

Do not return unbounded patient, encounter, order or audit lists.

## 18. Standard error response

Use a consistent error structure.

```json
{
  "timestamp": "2026-11-23T10:00:00Z",
  "status": 400,
  "code": "VALIDATION_ERROR",
  "message": "The request contains invalid information.",
  "fieldErrors": [
    {
      "field": "dateOfBirth",
      "message": "The date is invalid."
    }
  ],
  "traceId": "example-trace-id"
}
```

Do not expose stack traces, database errors, internal secrets or sensitive patient data in error responses.

## 19. HTTP status conventions

| Status | Meaning |
|---|---|
| 200 | Successful request |
| 201 | Resource created |
| 202 | Request accepted for background processing |
| 204 | Successful request with no response body |
| 400 | Invalid request |
| 401 | Authentication required |
| 403 | Authenticated but not permitted |
| 404 | Resource not found or not visible to caller |
| 409 | Conflict, duplicate or invalid state transition |
| 422 | Valid format but business-rule failure |
| 429 | Rate limit exceeded |
| 500 | Unexpected server error |
| 503 | Service temporarily unavailable |

## 20. API security requirements

- Enforce authorization on every protected endpoint.
- Validate tenant and facility scope.
- Prevent insecure direct object references.
- Apply request-size limits.
- Validate file uploads.
- Apply rate limits to sensitive endpoints.
- Protect authentication and password recovery endpoints.
- Use idempotency keys for sensitive operations where appropriate.
- Record required audit events.
- Do not trust organization or facility IDs supplied by the frontend without authorization checks.
- Never expose passwords, tokens, secrets or unrestricted DICOM archive access.

## 21. API documentation and testing

Use OpenAPI to document:

- Endpoint paths and methods.
- Authentication requirements.
- Request schemas.
- Response schemas.
- Validation rules.
- Error responses.
- Permission requirements.
- Pagination and filtering.
- Status transitions.

Backend tests must cover success, validation failure, unauthorized access, cross-facility access, cross-tenant access, duplicate submissions and invalid workflow transitions.

## 22. Definition of API readiness

The API specification is ready for implementation when:

- MVP endpoints are approved.
- DTO schemas are documented.
- Authentication approach is selected.
- Permission requirements are mapped.
- Error and pagination formats are agreed.
- Clinical and financial state transitions are documented.
- OpenAPI documentation can be generated and reviewed.
- Frontend and backend teams agree to use the same contract.

End of Document 05
