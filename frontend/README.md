# GCare HMS frontend

React 19 + TypeScript + Vite. Setup, commands and policies: [../docs/DEVELOPMENT.md](../docs/DEVELOPMENT.md).

```sh
npm ci
npm run dev        # http://localhost:5173, proxies /api and /actuator to the backend
npm run typecheck && npm run lint && npm test && npm run build
```

Do not store clinical content or tokens in browser storage ([../docs/07-CLAUDE.md](../docs/07-CLAUDE.md)).
