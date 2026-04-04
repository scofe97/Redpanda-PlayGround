import { useParams, useNavigate, Link } from 'react-router-dom';
import toast from 'react-hot-toast';
import { useJob, useDeleteJob, useExecuteJob, useJobExecutions } from '../hooks/useJobs';

const JENKINS_STATUS_BADGE: Record<string, { icon: string; label: string; className: string }> = {
  PENDING:  { icon: 'hourglass_empty', label: '대기',     className: 'text-amber-600 bg-amber-50 dark:bg-amber-900/20' },
  ACTIVE:   { icon: 'check_circle',   label: '활성',     className: 'text-emerald-600 bg-emerald-50 dark:bg-emerald-900/20' },
  FAILED:   { icon: 'error',          label: '실패',     className: 'text-red-600 bg-red-50 dark:bg-red-900/20' },
  DELETING: { icon: 'delete_sweep',   label: '삭제 중',  className: 'text-slate-500 bg-slate-50 dark:bg-slate-800' },
};

export default function JobDetailPage() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const jobId = Number(id);
  const isValidId = !isNaN(jobId) && jobId > 0;

  const { data: job, isLoading, error } = useJob(isValidId ? jobId : -1);
  const { data: executions } = useJobExecutions(isValidId ? jobId : -1);
  const deleteJob = useDeleteJob();
  const executeJob = useExecuteJob();

  if (!isValidId) {
    return (
      <div className="flex-1 flex items-center justify-center">
        <div className="text-center">
          <h2 className="text-lg font-bold">Invalid Job ID</h2>
          <p className="text-slate-500 mt-2 text-sm">"{id}" is not a valid job ID.</p>
          <button
            className="mt-4 px-5 py-2.5 bg-primary text-white text-sm font-bold rounded-lg hover:bg-primary/90 transition-all"
            onClick={() => navigate('/jobs')}
          >
            Back to Jobs
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

  if (error) {
    const isNotFound = 'status' in error && (error as any).status === 404;
    return (
      <div className="flex-1 flex items-center justify-center">
        <div className="text-center">
          {isNotFound ? (
            <>
              <span className="material-symbols-outlined text-5xl text-slate-300 mb-3">search_off</span>
              <h2 className="text-lg font-bold text-slate-700">Job을 찾을 수 없습니다</h2>
              <p className="text-slate-400 mt-2 text-sm">요청하신 Job(ID: {id})이 존재하지 않거나 삭제되었습니다.</p>
            </>
          ) : (
            <>
              <h2 className="text-lg font-bold">Failed to load job</h2>
              <p className="text-red-600 mt-2 text-sm">{error.message}</p>
            </>
          )}
          <button
            className="mt-4 px-5 py-2.5 bg-primary text-white text-sm font-bold rounded-lg hover:bg-primary/90 transition-all"
            onClick={() => navigate('/jobs')}
          >
            Back to Jobs
          </button>
        </div>
      </div>
    );
  }

  if (!job) return null;

  const badge = job.jenkinsStatus ? JENKINS_STATUS_BADGE[job.jenkinsStatus] : null;
  const isActive = !job.jenkinsScript || job.jenkinsStatus === 'ACTIVE';

  const handleDelete = async () => {
    if (!window.confirm(`"${job.jobName}" Job을 삭제하시겠습니까?`)) return;
    try {
      await deleteJob.mutateAsync(jobId);
      navigate('/jobs');
    } catch (err) {
      toast.error(err instanceof Error ? err.message : 'Failed to delete job');
    }
  };

  const handleExecute = async () => {
    try {
      await executeJob.mutateAsync({ id: jobId });
      toast.success('실행이 시작되었습니다');
    } catch (err) {
      toast.error(err instanceof Error ? err.message : 'Failed to execute job');
    }
  };

  return (
    <div className="flex-1 overflow-y-auto p-8">
      <div className="max-w-[1200px] mx-auto">
        {/* Header */}
        <div className="flex flex-col md:flex-row md:items-center justify-between gap-4 mb-8">
          <div className="flex items-center gap-4">
            <button
              onClick={() => navigate('/jobs')}
              className="p-2 rounded-lg hover:bg-slate-100 dark:hover:bg-slate-800 transition-colors text-slate-500"
              title="목록으로"
            >
              <span className="material-symbols-outlined text-[20px]">arrow_back</span>
            </button>
            <div className="p-3 bg-white dark:bg-slate-800 rounded-xl shadow-sm border border-slate-200 dark:border-slate-700">
              <span className="material-symbols-outlined text-primary text-3xl">build</span>
            </div>
            <div>
              <h2 className="text-2xl font-bold">{job.jobName}</h2>
              <p className="text-slate-500 text-sm">
                ID: #{job.id} · 생성일: {new Date(job.createdAt).toLocaleDateString()}
              </p>
            </div>
          </div>
          <div className="flex items-center gap-3">
            {isActive && (
              <button
                onClick={handleExecute}
                disabled={executeJob.isPending}
                className="px-5 py-2.5 bg-primary text-white text-sm font-bold rounded-lg hover:bg-primary/90 transition-all flex items-center gap-2 disabled:opacity-50 disabled:cursor-not-allowed shadow-lg shadow-primary/20"
              >
                <span className="material-symbols-outlined text-sm">play_arrow</span>
                {executeJob.isPending ? '실행 중...' : '실행'}
              </button>
            )}
            <Link
              to={`/jobs/${jobId}/edit`}
              className="px-5 py-2.5 border border-slate-200 dark:border-slate-700 text-slate-600 dark:text-slate-400 text-sm font-bold rounded-lg hover:bg-slate-50 dark:hover:bg-slate-800 transition-colors flex items-center gap-2"
            >
              <span className="material-symbols-outlined text-sm">edit</span>
              편집
            </Link>
            <button
              onClick={handleDelete}
              disabled={deleteJob.isPending}
              className="px-5 py-2.5 bg-red-50 text-red-600 border border-red-200 text-sm font-bold rounded-lg hover:bg-red-100 transition-all flex items-center gap-2 disabled:opacity-50"
            >
              <span className="material-symbols-outlined text-sm">delete</span>
              삭제
            </button>
          </div>
        </div>

        {/* Info Card */}
        <div className="bg-white dark:bg-slate-900 rounded-xl border border-slate-200 dark:border-slate-800 shadow-sm overflow-hidden mb-6">
          <div className="p-5 border-b border-slate-100 dark:border-slate-800">
            <h3 className="font-bold">Job 정보</h3>
          </div>
          <div className="p-5 grid grid-cols-2 gap-6">
            <div>
              <label className="text-[11px] font-bold text-slate-400 uppercase tracking-wider">타입</label>
              <p className="mt-1 text-sm text-slate-700 dark:text-slate-200">{job.jobType}</p>
            </div>
            <div>
              <label className="text-[11px] font-bold text-slate-400 uppercase tracking-wider">목적</label>
              <p className="mt-1 text-sm text-slate-700 dark:text-slate-200">{job.purposeName || '-'}</p>
            </div>
            <div className="col-span-2">
              <label className="text-[11px] font-bold text-slate-400 uppercase tracking-wider">Jenkins 상태</label>
              <div className="mt-1">
                {badge ? (
                  <span className={`inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-xs font-medium ${badge.className}`}>
                    <span className="material-symbols-outlined text-[14px]">{badge.icon}</span>
                    {badge.label}
                  </span>
                ) : (
                  <span className="text-sm text-slate-400">-</span>
                )}
              </div>
            </div>
          </div>
        </div>

        {/* Config JSON */}
        {job.configJson && (() => {
          let configEntries: [string, string][] = [];
          try {
            const parsed = JSON.parse(job.configJson);
            if (typeof parsed === 'object' && parsed !== null && !Array.isArray(parsed)) {
              configEntries = Object.entries(parsed).map(([k, v]) => [k, String(v ?? '')]);
            }
          } catch { /* fallback below */ }

          return (
            <div className="bg-white dark:bg-slate-900 rounded-xl border border-slate-200 dark:border-slate-800 shadow-sm overflow-hidden mb-6">
              <div className="p-5 border-b border-slate-100 dark:border-slate-800">
                <h3 className="font-bold">{job.jobType === 'BUILD' ? '빌드 설정' : '배포 설정'}</h3>
              </div>
              <div className="p-5">
                {configEntries.length > 0 ? (
                  <div className="space-y-2">
                    {configEntries.map(([k, v]) => (
                      <div key={k} className="flex items-center gap-2">
                        <span className="text-xs font-mono text-slate-500 w-32 flex-shrink-0">{k}</span>
                        <span className="text-sm font-mono text-slate-700 dark:text-slate-300">{v}</span>
                      </div>
                    ))}
                  </div>
                ) : (
                  <pre className="text-xs font-mono text-slate-700 dark:text-slate-300 bg-slate-50 dark:bg-slate-800 rounded-lg p-4 overflow-x-auto whitespace-pre-wrap break-all">
                    {job.configJson}
                  </pre>
                )}
              </div>
            </div>
          );
        })()}

        {/* Jenkinsfile Script */}
        {job.jenkinsScript && (
          <div className="bg-white dark:bg-slate-900 rounded-xl border border-slate-200 dark:border-slate-800 shadow-sm overflow-hidden mb-6">
            <div className="p-5 border-b border-slate-100 dark:border-slate-800">
              <h3 className="font-bold">Jenkinsfile 스크립트</h3>
            </div>
            <div className="p-5">
              <pre className="text-xs font-mono text-slate-700 dark:text-slate-300 bg-slate-50 dark:bg-slate-800 rounded-lg p-4 overflow-x-auto whitespace-pre leading-relaxed">
                {job.jenkinsScript}
              </pre>
            </div>
          </div>
        )}

        {/* 실행 이력 */}
        <div className="bg-white dark:bg-slate-900 rounded-xl border border-slate-200 dark:border-slate-800 shadow-sm overflow-hidden">
          <div className="p-5 border-b border-slate-100 dark:border-slate-800">
            <h3 className="font-bold text-slate-700 dark:text-slate-200">실행 이력</h3>
          </div>
          <div className="p-5">
            {!executions || executions.length === 0 ? (
              <p className="text-sm text-slate-400 text-center py-4">실행 이력이 없습니다.</p>
            ) : (
              <div className="space-y-4">
                {executions.map((exec) => {
                  const jobExec = exec.jobExecutions?.find((je) => je.jobName === job.jobName) ?? exec.jobExecutions?.[0];
                  const statusClass: Record<string, string> = {
                    SUCCESS: 'text-emerald-600 bg-emerald-50 dark:bg-emerald-900/20',
                    FAILED:  'text-red-600 bg-red-50 dark:bg-red-900/20',
                    RUNNING: 'text-blue-600 bg-blue-50 dark:bg-blue-900/20',
                    PENDING: 'text-amber-600 bg-amber-50 dark:bg-amber-900/20',
                    CANCELLED: 'text-slate-500 bg-slate-100 dark:bg-slate-800',
                  };
                  const statusIcon: Record<string, string> = {
                    SUCCESS: 'check_circle',
                    FAILED:  'error',
                    RUNNING: 'sync',
                    PENDING: 'hourglass_empty',
                    CANCELLED: 'cancel',
                  };
                  const cls = statusClass[exec.status] ?? 'text-slate-500 bg-slate-100 dark:bg-slate-800';
                  const icon = statusIcon[exec.status] ?? 'info';
                  return (
                    <div key={exec.executionId} className="border border-slate-100 dark:border-slate-800 rounded-lg p-4 space-y-2">
                      <div className="flex items-center justify-between gap-3 flex-wrap">
                        <span className={`inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-xs font-medium ${cls}`}>
                          <span className="material-symbols-outlined text-[14px]">{icon}</span>
                          {exec.status}
                        </span>
                        <span className="text-xs text-slate-400 font-mono">
                          {exec.startedAt ? new Date(exec.startedAt).toLocaleString() : '-'}
                        </span>
                        <span
                          className="text-xs text-slate-400 font-mono cursor-pointer hover:text-slate-600 truncate max-w-[200px]"
                          title={exec.executionId}
                          onClick={() => { navigator.clipboard.writeText(exec.executionId); toast.success('복사됨'); }}
                        >
                          {exec.executionId.slice(0, 8)}…
                        </span>
                      </div>
                      {jobExec?.log && (
                        <pre className="font-mono text-xs bg-slate-800 text-slate-200 p-3 rounded-lg max-h-32 overflow-y-auto whitespace-pre-wrap break-all">
                          {jobExec.log}
                        </pre>
                      )}
                    </div>
                  );
                })}
              </div>
            )}
          </div>
        </div>
      </div>

    </div>
  );
}
