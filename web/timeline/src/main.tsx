import { mountApp } from '@tenshu/app-kit/root'
import './index.css'
import './i18n'
import App from './App.tsx'
import { ErrorBoundary } from './components/ErrorBoundary'

// StrictMode → the root ErrorBoundary → a QueryClient that does not retry (the bridge fails
// transiently — no session yet, apex not ready — and a retry only delays the inline error the views
// already render) → the app.
mountApp(App, { boundary: ErrorBoundary })
