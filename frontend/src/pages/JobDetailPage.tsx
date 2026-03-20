import { useState } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { useJob, useUpdateJob, useDeleteJob, useExecuteJob } from '../hooks/useJobs';
import { usePresetList } from '../hooks/usePipelineDefinition';

const JOB_TYPES = [
  { value: 'BUILD', label: '빌드' },
  { value: 'DEPLOY', label: '배포' },
];

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
  const { data: presets } = usePresetList();
  const updateJob = useUpdateJob();
  const deleteJob = useDeleteJob();
  const executeJob = useExecuteJob();

  const [showEdit, setShowEdit] = useState(false);
  const [jobName, setJobName] = useState('');
  const [jobType, setJobType] = useState('BUILD');
  const [presetId, setPresetId] = useState<number | undefined>();
  const [configJson, setConfigJson] = useState('');
  const [jenkinsScript, setJenkinsScript] = useState('');

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

  const openEdit = () => {
    setJobName(job.jobName);
    setJobType(job.jobType);
    setPresetId(job.presetId);
    setConfigJson(job.configJson ?? '');
    setJenkinsScript(job.jenkinsScript ?? '');
    setShowEdit(true);
  };

  const resetEdit = () => {
    setShowEdit(false);
  };

  const handleUpdate = async (e: React.FormEvent<HTMLFormElement>) => {
    e.preventDefault();
    if (!jobName.trim() || !presetId) return;
    try {
      await updateJob.mutateAsync({
        id: jobId,
        data: {
          jobName: jobName.trim(),
          jobType,
          presetId,
          configJson: configJson.trim() || undefined,
          jenkinsScript: jenkinsScript.trim() || undefined,
        },
      });
      setShowEdit(false);
    } catch (err) {
      alert(err instanceof Error ? err.message : 'Failed to update job');
    }
  };

  const handleDelete = async () => {
    if (!window.confirm(`"${job.jobName}" Job을 삭제하시겠습니까?`)) return;
    try {
      await deleteJob.mutateAsync(jobId);
      navigate('/jobs');
    } catch (err) {
      alert(err instanceof Error ? err.message : 'Failed to delete job');
    }
  };

  const handleExecute = async () => {
    try {
      await executeJob.mutateAsync(jobId);
      alert('실행이 시작되었습니다');
    } catch (err) {
      alert(err instanceof Error ? err.message : 'Failed to execute job');
    }
  };

  return (
    <div className="flex-1 overflow-y-auto p-8">
      <div className="max-w-3xl mx-auto">
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
            <button
              onClick={openEdit}
              className="px-5 py-2.5 border border-slate-200 dark:border-slate-700 text-slate-600 dark:text-slate-400 text-sm font-bold rounded-lg hover:bg-slate-50 dark:hover:bg-slate-800 transition-colors flex items-center gap-2"
            >
              <span className="material-symbols-outlined text-sm">edit</span>
              편집
            </button>
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
              <label className="text-[11px] font-bold text-slate-400 uppercase tracking-wider">프리셋</label>
              <p className="mt-1 text-sm text-slate-700 dark:text-slate-200">{job.presetName || '-'}</p>
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
        {job.configJson && (
          <div className="bg-white dark:bg-slate-900 rounded-xl border border-slate-200 dark:border-slate-800 shadow-sm overflow-hidden mb-6">
            <div className="p-5 border-b border-slate-100 dark:border-slate-800">
              <h3 className="font-bold">Config JSON</h3>
            </div>
            <div className="p-5">
              <pre className="text-xs font-mono text-slate-700 dark:text-slate-300 bg-slate-50 dark:bg-slate-800 rounded-lg p-4 overflow-x-auto whitespace-pre-wrap break-all">
                {(() => {
                  try { return JSON.stringify(JSON.parse(job.configJson), null, 2); }
                  catch { return job.configJson; }
                })()}
              </pre>
            </div>
          </div>
        )}

        {/* Jenkinsfile Script */}
        {job.jenkinsScript && (
          <div className="bg-white dark:bg-slate-900 rounded-xl border border-slate-200 dark:border-slate-800 shadow-sm overflow-hidden">
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
      </div>

      {/* Edit Modal */}
      {showEdit && (
        <>
          <div className="fixed inset-0 bg-slate-900/50 backdrop-blur-sm z-40" onClick={resetEdit} />
          <div className="fixed inset-0 z-50 flex items-center justify-center p-4">
            <form
              onSubmit={handleUpdate}
              className="bg-white dark:bg-slate-900 rounded-xl border border-slate-200 dark:border-slate-800 shadow-2xl w-full max-w-lg p-6 space-y-4 max-h-[90vh] overflow-y-auto"
            >
              <div className="flex items-center gap-2 mb-2">
                <span className="material-symbols-outlined text-primary">edit</span>
                <h3 className="text-lg font-bold">Job 편집</h3>
              </div>

              <div>
                <label className="block text-xs font-bold text-slate-500 uppercase mb-1.5">이름</label>
                <input
                  value={jobName}
                  onChange={(e) => setJobName(e.target.value)}
                  required
                  maxLength={100}
                  className="w-full px-3 py-2 border border-slate-300 dark:border-slate-700 dark:bg-slate-800 rounded-lg text-sm focus:ring-2 focus:ring-primary focus:border-primary outline-none transition-all"
                  autoFocus
                />
              </div>

              <div>
                <label className="block text-xs font-bold text-slate-500 uppercase mb-1.5">타입</label>
                <select
                  value={jobType}
                  onChange={(e) => setJobType(e.target.value)}
                  className="w-full px-3 py-2 border border-slate-300 dark:border-slate-700 dark:bg-slate-800 rounded-lg text-sm focus:ring-2 focus:ring-primary focus:border-primary outline-none transition-all"
                >
                  {JOB_TYPES.map((t) => (
                    <option key={t.value} value={t.value}>{t.label}</option>
                  ))}
                </select>
              </div>

              <div>
                <label className="block text-xs font-bold text-slate-500 uppercase mb-1.5">프리셋</label>
                <select
                  value={presetId ?? ''}
                  onChange={(e) => setPresetId(e.target.value ? Number(e.target.value) : undefined)}
                  required
                  className="w-full px-3 py-2 border border-slate-300 dark:border-slate-700 dark:bg-slate-800 rounded-lg text-sm focus:ring-2 focus:ring-primary focus:border-primary outline-none transition-all"
                >
                  <option value="">프리셋 선택</option>
                  {presets?.map((p) => (
                    <option key={p.id} value={p.id}>{p.name}</option>
                  ))}
                </select>
              </div>

              <div>
                <label className="block text-xs font-bold text-slate-500 uppercase mb-1.5">Jenkinsfile 스크립트</label>
                <textarea
                  value={jenkinsScript}
                  onChange={(e) => setJenkinsScript(e.target.value)}
                  rows={10}
                  className="w-full px-3 py-2 border border-slate-300 dark:border-slate-700 dark:bg-slate-800 rounded-lg text-sm focus:ring-2 focus:ring-primary focus:border-primary outline-none transition-all font-mono resize-none leading-relaxed"
                />
              </div>

              <div>
                <label className="block text-xs font-bold text-slate-500 uppercase mb-1.5">Config JSON (선택)</label>
                <textarea
                  value={configJson}
                  onChange={(e) => setConfigJson(e.target.value)}
                  rows={3}
                  placeholder='{"key": "value"}'
                  className="w-full px-3 py-2 border border-slate-300 dark:border-slate-700 dark:bg-slate-800 rounded-lg text-sm focus:ring-2 focus:ring-primary focus:border-primary outline-none transition-all font-mono resize-none"
                />
              </div>

              <div className="flex gap-3 pt-2">
                <button
                  type="button"
                  onClick={resetEdit}
                  className="flex-1 px-4 py-2.5 border border-slate-200 dark:border-slate-700 text-slate-600 dark:text-slate-400 font-bold rounded-lg text-sm hover:bg-slate-50 dark:hover:bg-slate-800 transition-colors"
                >
                  취소
                </button>
                <button
                  type="submit"
                  disabled={!jobName.trim() || !presetId || updateJob.isPending}
                  className="flex-1 px-4 py-2.5 bg-primary text-white font-bold rounded-lg text-sm hover:bg-primary/90 shadow-lg shadow-primary/20 transition-all disabled:opacity-50 disabled:cursor-not-allowed"
                >
                  {updateJob.isPending ? '저장 중...' : '저장'}
                </button>
              </div>
            </form>
          </div>
        </>
      )}
    </div>
  );
}
