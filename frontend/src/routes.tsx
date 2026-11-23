import type { RouteObject } from 'react-router'
import { Permission } from './api/types'
import { EncounterPage } from './pages/encounters/EncounterPage'
import { QueuePage } from './pages/encounters/QueuePage'
import { FacilitiesPage, FacilityDepartmentsPage } from './pages/FacilitiesPage'
import { NotFoundPage } from './pages/NotFoundPage'
import { OrderCatalogPage } from './pages/orders/OrderCatalogPage'
import { WorklistPage } from './pages/orders/WorklistPage'
import { DuplicateReviewPage } from './pages/patients/DuplicateReviewPage'
import { PatientPage } from './pages/patients/PatientPage'
import { PatientSearchPage } from './pages/patients/PatientSearchPage'
import { RegisterPatientPage } from './pages/patients/RegisterPatientPage'
import { HomeRoute, RequirePermission, RouteError, SessionGate } from './shell/SessionGate'

export const routes: RouteObject[] = [
  {
    path: '/',
    element: <SessionGate />,
    errorElement: <RouteError />,
    children: [
      { index: true, element: <HomeRoute /> },
      {
        path: 'facilities',
        element: (
          <RequirePermission permission={Permission.FACILITY_READ}>
            <FacilitiesPage />
          </RequirePermission>
        ),
      },
      {
        path: 'facilities/:facilityId',
        element: (
          <RequirePermission permission={Permission.FACILITY_READ}>
            <FacilityDepartmentsPage />
          </RequirePermission>
        ),
      },
      {
        path: 'patients',
        element: (
          <RequirePermission permission={Permission.PATIENT_READ}>
            <PatientSearchPage />
          </RequirePermission>
        ),
      },
      {
        path: 'patients/new',
        element: (
          <RequirePermission permission={Permission.PATIENT_REGISTER}>
            <RegisterPatientPage />
          </RequirePermission>
        ),
      },
      {
        path: 'patients/duplicates',
        element: (
          <RequirePermission permission={Permission.PATIENT_DUPLICATE_REVIEW}>
            <DuplicateReviewPage />
          </RequirePermission>
        ),
      },
      {
        path: 'patients/:patientId',
        element: (
          <RequirePermission permission={Permission.PATIENT_READ}>
            <PatientPage />
          </RequirePermission>
        ),
      },
      {
        path: 'queue',
        element: (
          <RequirePermission permission={Permission.ENCOUNTER_READ}>
            <QueuePage />
          </RequirePermission>
        ),
      },
      {
        path: 'encounters/:encounterId',
        element: (
          <RequirePermission permission={Permission.ENCOUNTER_READ}>
            <EncounterPage />
          </RequirePermission>
        ),
      },
      {
        path: 'orders',
        element: (
          <RequirePermission permission={Permission.ORDER_PROCESS}>
            <WorklistPage />
          </RequirePermission>
        ),
      },
      {
        path: 'admin/order-catalog',
        element: (
          <RequirePermission permission={Permission.ORDER_CATALOG_MANAGE}>
            <OrderCatalogPage />
          </RequirePermission>
        ),
      },
      { path: '*', element: <NotFoundPage /> },
    ],
  },
]
