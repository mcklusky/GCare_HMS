# GCare HMS frontend

React 19 + TypeScript + Vite. Setup, commands and policies: [../docs/DEVELOPMENT.md](../docs/DEVELOPMENT.md).

```sh
npm ci
npm run dev        # http://localhost:5173, proxies /api, /actuator, /oauth2, /login/oauth2, /v3/api-docs to the backend
npm run typecheck && npm run lint && npm test && npm run build
```

Stack: Mantine 9, React Router 8, TanStack Query 5 (ADR-013). Structure and patterns: [DEVELOPMENT.md](../docs/DEVELOPMENT.md#architecture-map).

Do not store clinical content or tokens in browser storage ([../docs/07-CLAUDE.md](../docs/07-CLAUDE.md)).
