import { Routes, Route, useLocation, Navigate } from 'react-router-dom';
import { Toaster } from 'react-hot-toast';
import ErrorBoundary from './components/ErrorBoundary';
import Sidebar from './components/Sidebar';
import TicketListPage from './pages/TicketListPage';
import TicketCreatePage from './pages/TicketCreatePage';
import TicketDetailPage from './pages/TicketDetailPage';
import ToolListPage from './pages/ToolListPage';
import PresetListPage from './pages/PresetListPage';
import JobListPage from './pages/JobListPage';
import JobDetailPage from './pages/JobDetailPage';
import JobCreatePage from './pages/JobCreatePage';
import JobEditPage from './pages/JobEditPage';
import PipelineListPage from './pages/PipelineListPage';
import PipelineDetailPage from './pages/PipelineDetailPage';
import PipelineCreatePage from './pages/PipelineCreatePage';

export default function App() {
  const { pathname } = useLocation();

  return (
    <div className="flex h-screen overflow-hidden bg-background-light dark:bg-background-dark text-slate-900 dark:text-slate-100 font-display">
      <Sidebar currentPath={pathname} />
      <Toaster position="top-right" toastOptions={{
        duration: 3000,
        style: { background: '#1e293b', color: '#f1f5f9', borderRadius: '0.75rem', fontSize: '0.875rem' }
      }} />
      <main className="flex-1 flex flex-col min-w-0 overflow-hidden">
        <ErrorBoundary>
          <Routes>
            <Route path="/" element={<Navigate to="/tickets" replace />} />
            <Route path="/tickets" element={<TicketListPage />} />
            <Route path="/tickets/new" element={<TicketCreatePage />} />
            <Route path="/tickets/:id" element={<TicketDetailPage />} />
            <Route path="/presets" element={<PresetListPage />} />
            <Route path="/tools" element={<ToolListPage />} />
            <Route path="/jobs" element={<JobListPage />} />
            <Route path="/jobs/new" element={<JobCreatePage />} />
            <Route path="/jobs/:id/edit" element={<JobEditPage />} />
            <Route path="/jobs/:id" element={<JobDetailPage />} />
            <Route path="/pipelines" element={<PipelineListPage />} />
            <Route path="/pipelines/new" element={<PipelineCreatePage />} />
            <Route path="/pipelines/:id" element={<PipelineDetailPage />} />
          </Routes>
        </ErrorBoundary>
      </main>
    </div>
  );
}
