import type { RouteObject } from 'react-router'
import { Permission } from './api/types'
import { FacilitiesPage, FacilityDepartmentsPage } from './pages/FacilitiesPage'
import { NotFoundPage } from './pages/NotFoundPage'
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
      { path: '*', element: <NotFoundPage /> },
    ],
  },
]
