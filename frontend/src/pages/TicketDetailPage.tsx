import { useParams, useNavigate } from 'react-router-dom';
import { useTicket, useDeleteTicket } from '../hooks/useTickets';
import { usePipelineLatest, useStartPipeline } from '../hooks/usePipeline';
import { useSSE } from '../hooks/useSSE';
import StatusBadge from '../components/StatusBadge';
import PipelineTimeline from '../components/PipelineTimeline';

export default function TicketDetailPage() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const ticketId = Number(id);
  const isValidId = !isNaN(ticketId) && ticketId > 0;

  const { data: ticket, isLoading, error: ticketError } = useTicket(isValidId ? ticketId : -1);
  const { data: pipeline, error: pipelineError } = usePipelineLatest(isValidId ? ticketId : -1);
  const startPipeline = useStartPipeline();
  const deleteTicket = useDeleteTicket();

  const isRunning =
    pipeline?.status === 'RUNNING' ||
    pipeline?.status === 'PENDING' ||
    (pipeline?.steps?.some((s) => s.status === 'WAITING_WEBHOOK') ?? false);
  useSSE({ ticketId, enabled: isValidId && isRunning });

  if (!isValidId) {
    return (
      <div className="flex-1 flex items-center justify-center">
        <div className="text-center">
          <h2 className="text-lg font-bold">Invalid Ticket ID</h2>
          <p className="text-slate-500 mt-2 text-sm">"{id}" is not a valid ticket ID.</p>
          <button
            className="mt-4 px-5 py-2.5 bg-primary text-white text-sm font-bold rounded-lg hover:bg-primary/90 transition-all"
            onClick={() => navigate('/tickets')}
          >
            Back to Tickets
          </button>
        </div>
      </div>
    );
  }

  if (isLoading) {
    return (
      <div className="flex-1 flex items-center justify-center">
        <p className="text-slate-500">Loading...</p>
      </div>
    );
  }

  if (ticketError) {
    const isNotFound = 'status' in ticketError && (ticketError as any).status === 404;
    return (
      <div className="flex-1 flex items-center justify-center">
        <div className="text-center">
          {isNotFound ? (
            <>
              <span className="material-symbols-outlined text-5xl text-slate-300 mb-3">search_off</span>
              <h2 className="text-lg font-bold text-slate-700">티켓을 찾을 수 없습니다</h2>
              <p className="text-slate-400 mt-2 text-sm">요청하신 티켓(ID: {id})이 존재하지 않거나 삭제되었습니다.</p>
            </>
          ) : (
            <>
              <h2 className="text-lg font-bold">Failed to load ticket</h2>
              <p className="text-red-600 mt-2 text-sm">{ticketError.message}</p>
            </>
          )}
          <button
            className="mt-4 px-5 py-2.5 bg-primary text-white text-sm font-bold rounded-lg hover:bg-primary/90 transition-all"
            onClick={() => navigate('/tickets')}
          >
            Back to Tickets
          </button>
        </div>
      </div>
    );
  }

  if (!ticket) return <p className="p-8 text-slate-500">Ticket not found</p>;

  const handleStart = async () => {
    try {
      await startPipeline.mutateAsync(ticketId);
    } catch (e) {
      alert(e instanceof Error ? e.message : 'Failed to start pipeline');
    }
  };

  const handleDelete = async () => {
    if (!window.confirm(`"${ticket.name}" 티켓을 삭제하시겠습니까?`)) return;
    try {
      await deleteTicket.mutateAsync(ticketId);
      navigate('/tickets');
    } catch (e) {
      alert(e instanceof Error ? e.message : 'Failed to delete ticket');
    }
  };

  return (
    <div className="flex-1 overflow-y-auto p-8">
      <div className="max-w-5xl mx-auto">
        {/* Header */}
        <div className="flex flex-col md:flex-row md:items-center justify-between gap-4 mb-8">
          <div className="flex items-center gap-4">
            <div className="p-3 bg-white dark:bg-slate-800 rounded-xl shadow-sm border border-slate-200 dark:border-slate-700">
              <span className="material-symbols-outlined text-primary text-3xl">confirmation_number</span>
            </div>
            <div>
              <h2 className="text-2xl font-bold">{ticket.name}</h2>
              <p className="text-slate-500 text-sm">
                ID: #{ticket.id} · 생성일: {new Date(ticket.createdAt).toLocaleDateString()}
              </p>
            </div>
          </div>
          <div className="flex items-center gap-3">
            <button
              className="px-5 py-2.5 bg-primary text-white text-sm font-bold rounded-lg hover:bg-primary/90 transition-all flex items-center gap-2 disabled:opacity-50 disabled:cursor-not-allowed"
              onClick={handleStart}
              disabled={isRunning || startPipeline.isPending}
            >
              <span className="material-symbols-outlined text-sm">play_arrow</span>
              {startPipeline.isPending ? '시작 중...' : isRunning ? '실행 중...' : '파이프라인 시작'}
            </button>
            <button
              className="px-5 py-2.5 bg-red-50 text-red-600 border border-red-200 text-sm font-bold rounded-lg hover:bg-red-100 transition-all flex items-center gap-2"
              onClick={handleDelete}
            >
              <span className="material-symbols-outlined text-sm">delete</span>
              삭제
            </button>
          </div>
        </div>

        {/* Content Grid */}
        <div className="grid grid-cols-1 lg:grid-cols-3 gap-8">
          {/* Left Column: Info & Pipeline */}
          <div className="lg:col-span-1 space-y-6">
            {/* Ticket Info Card */}
            <div className="bg-white dark:bg-slate-900 rounded-xl border border-slate-200 dark:border-slate-800 shadow-sm overflow-hidden">
              <div className="p-5 border-b border-slate-100 dark:border-slate-800">
                <h3 className="font-bold">티켓 정보</h3>
              </div>
              <div className="p-5 space-y-4">
                <div>
                  <label className="text-[11px] font-bold text-slate-400 uppercase tracking-wider">상태</label>
                  <div className="mt-1">
                    <StatusBadge status={ticket.status} />
                  </div>
                </div>
                {ticket.description && (
                  <div>
                    <label className="text-[11px] font-bold text-slate-400 uppercase tracking-wider">설명</label>
                    <p className="mt-1 text-sm text-slate-600 dark:text-slate-300 leading-relaxed">
                      {ticket.description}
                    </p>
                  </div>
                )}
                {ticket.sources && ticket.sources.length > 0 && (
                  <div>
                    <label className="text-[11px] font-bold text-slate-400 uppercase tracking-wider">소스 목록</label>
                    <ul className="mt-2 space-y-1">
                      {ticket.sources.map((s, i) => (
                        <li key={i} className="flex items-center gap-2 text-xs text-slate-500">
                          <span className="material-symbols-outlined text-sm">
                            {s.sourceType === 'GIT' ? 'account_tree' : s.sourceType === 'NEXUS' ? 'package_2' : 'dock'}
                          </span>
                          <span className="font-medium">{s.sourceType}</span>
                          {s.repoUrl && ` — ${s.repoUrl} (${s.branch})`}
                          {s.artifactCoordinate && ` — ${s.artifactCoordinate}`}
                          {s.imageName && ` — ${s.imageName}`}
                        </li>
                      ))}
                    </ul>
                  </div>
                )}
              </div>
            </div>

            {/* Pipeline Card */}
            {pipelineError && (
              <div className="bg-white dark:bg-slate-900 rounded-xl border border-red-200 dark:border-red-800 shadow-sm p-5">
                <p className="text-red-600 text-sm">Failed to load pipeline: {pipelineError.message}</p>
              </div>
            )}

            {pipeline && (
              <div className="bg-white dark:bg-slate-900 rounded-xl border border-slate-200 dark:border-slate-800 shadow-sm overflow-hidden">
                <div className="p-5 border-b border-slate-100 dark:border-slate-800 flex items-center justify-between">
                  <h3 className="font-bold">파이프라인 진행 상태</h3>
                  <StatusBadge status={pipeline.status} />
                </div>
                <PipelineTimeline steps={pipeline.steps} />
                {pipeline.errorMessage && (
                  <div className="px-5 pb-5">
                    <p className="text-red-600 text-sm">Error: {pipeline.errorMessage}</p>
                  </div>
                )}
              </div>
            )}
          </div>

          {/* Right Column: Log area (placeholder) */}
          <div className="lg:col-span-2">
            {pipeline && pipeline.steps.some((s) => s.log) && (
              <div className="bg-slate-950 rounded-xl border border-slate-800 shadow-xl overflow-hidden flex flex-col min-h-[400px]">
                <div className="bg-slate-900 px-4 py-2 border-b border-slate-800 flex items-center gap-2">
                  <div className="flex gap-1.5">
                    <div className="w-2.5 h-2.5 rounded-full bg-red-500/30" />
                    <div className="w-2.5 h-2.5 rounded-full bg-yellow-500/30" />
                    <div className="w-2.5 h-2.5 rounded-full bg-green-500/30" />
                  </div>
                  <span className="text-[11px] font-mono text-slate-500 ml-2 italic">pipeline logs</span>
                </div>
                <div className="flex-1 p-6 font-mono text-[13px] leading-relaxed text-slate-300 overflow-y-auto space-y-4">
                  {pipeline.steps
                    .filter((s) => s.log)
                    .map((s) => (
                      <div key={s.id}>
                        <span className="text-primary font-bold">[{s.stepName}]</span>
                        <pre className="mt-1 whitespace-pre-wrap text-slate-400">{s.log}</pre>
                      </div>
                    ))}
                </div>
              </div>
            )}
          </div>
        </div>
      </div>
    </div>
  );
}
