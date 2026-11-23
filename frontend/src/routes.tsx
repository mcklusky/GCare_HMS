import type { RouteObject } from 'react-router'
import { Permission } from './api/types'
import { FacilitiesPage, FacilityDepartmentsPage } from './pages/FacilitiesPage'
import { NotFoundPage } from './pages/NotFoundPage'
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
      { path: '*', element: <NotFoundPage /> },
    ],
  },
]
