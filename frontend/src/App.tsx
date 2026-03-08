import { Routes, Route, useLocation, Navigate } from 'react-router-dom';
import ErrorBoundary from './components/ErrorBoundary';
import Sidebar from './components/Sidebar';
import TicketListPage from './pages/TicketListPage';
import TicketCreatePage from './pages/TicketCreatePage';
import TicketDetailPage from './pages/TicketDetailPage';
import ToolListPage from './pages/ToolListPage';

export default function App() {
  const { pathname } = useLocation();

  return (
    <div className="flex h-screen overflow-hidden bg-background-light dark:bg-background-dark text-slate-900 dark:text-slate-100 font-display">
      <Sidebar currentPath={pathname} />
      <main className="flex-1 flex flex-col min-w-0 overflow-hidden">
        <ErrorBoundary>
          <Routes>
            <Route path="/" element={<Navigate to="/tickets" replace />} />
            <Route path="/tickets" element={<TicketListPage />} />
            <Route path="/tickets/new" element={<TicketCreatePage />} />
            <Route path="/tickets/:id" element={<TicketDetailPage />} />
            <Route path="/tools" element={<ToolListPage />} />
          </Routes>
        </ErrorBoundary>
      </main>
    </div>
  );
}
