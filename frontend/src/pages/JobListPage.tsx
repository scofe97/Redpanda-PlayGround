import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useJobList, useCreateJob, useDeleteJob, useExecuteJob, useRetryJenkinsProvision } from '../hooks/useJobs';
import { usePresetList } from '../hooks/usePipelineDefinition';

const JOB_TYPES = [
  { value: 'BUILD', label: '빌드' },
  { value: 'DEPLOY', label: '배포' },
];

const JOB_TYPE_LABEL: Record<string, string> = {
  BUILD: '빌드',
  DEPLOY: '배포',
};

const JENKINS_STATUS_BADGE: Record<string, { icon: string; label: string; className: string }> = {
  PENDING: { icon: 'hourglass_empty', label: '대기', className: 'text-amber-600 bg-amber-50 dark:bg-amber-900/20' },
  ACTIVE: { icon: 'check_circle', label: '활성', className: 'text-emerald-600 bg-emerald-50 dark:bg-emerald-900/20' },
  FAILED: { icon: 'error', label: '실패', className: 'text-red-600 bg-red-50 dark:bg-red-900/20' },
  DELETING: { icon: 'delete_sweep', label: '삭제 중', className: 'text-slate-500 bg-slate-50 dark:bg-slate-800' },
};

const DEFAULT_SCRIPTS: Record<string, string> = {
  BUILD: `pipeline {
    agent any
    stages {
        stage('Build') {
            steps {
                echo 'Building...'
            }
        }
    }
}`,
  DEPLOY: `pipeline {
    agent any
    stages {
        stage('Deploy') {
            steps {
                echo 'Deploying...'
            }
        }
    }
}`,
};

export default function JobListPage() {
  const navigate = useNavigate();
  const { data: jobs, isLoading, error } = useJobList();
  const { data: presets } = usePresetList();
  const createJob = useCreateJob();
  const deleteJob = useDeleteJob();
  const executeJob = useExecuteJob();
  const retryProvision = useRetryJenkinsProvision();

  const [showCreate, setShowCreate] = useState(false);
  const [jobName, setJobName] = useState('');
  const [jobType, setJobType] = useState('BUILD');
  const [presetId, setPresetId] = useState<number | undefined>();
  const [configJson, setConfigJson] = useState('');
  const [jenkinsScript, setJenkinsScript] = useState(DEFAULT_SCRIPTS.BUILD);

  if (isLoading) {
    return (
      <div className="flex-1 flex items-center justify-center">
        <p className="text-slate-500">Loading...</p>
      </div>
    );
  }

  if (error) {
    return (
      <div className="flex-1 flex items-center justify-center">
        <div className="text-center">
          <h2 className="text-lg font-bold">Failed to load jobs</h2>
          <p className="text-red-600 mt-2 text-sm">{error.message}</p>
        </div>
      </div>
    );
  }

  const resetForm = () => {
    setJobName('');
    setJobType('BUILD');
    setPresetId(undefined);
    setConfigJson('');
    setJenkinsScript(DEFAULT_SCRIPTS.BUILD);
  };

  const handleCreate = async (e: React.FormEvent<HTMLFormElement>) => {
    e.preventDefault();
    if (!jobName.trim() || !presetId) return;
    try {
      await createJob.mutateAsync({
        jobName: jobName.trim(),
        jobType,
        presetId,
        configJson: configJson.trim() || undefined,
        jenkinsScript: jenkinsScript.trim() || undefined,
      });
      setShowCreate(false);
      resetForm();
    } catch (err) {
      alert(err instanceof Error ? err.message : 'Failed to create job');
    }
  };

  const handleDelete = async (id: number, name: string) => {
    if (!window.confirm(`"${name}" Job을 삭제하시겠습니까?`)) return;
    try {
      await deleteJob.mutateAsync(id);
    } catch (err) {
      alert(err instanceof Error ? err.message : 'Failed to delete job');
    }
  };

  const handleExecute = async (id: number) => {
    try {
      await executeJob.mutateAsync(id);
      alert('실행이 시작되었습니다');
    } catch (err) {
      alert(err instanceof Error ? err.message : 'Failed to execute job');
    }
  };

  const handleRetryProvision = async (id: number) => {
    try {
      await retryProvision.mutateAsync(id);
      alert('Jenkins 등록을 재시도합니다');
    } catch (err) {
      alert(err instanceof Error ? err.message : 'Failed to retry provision');
    }
  };

  return (
    <div className="flex-1 overflow-y-auto p-8 space-y-6">
      {/* Title & Action */}
      <div className="flex items-end justify-between">
        <h2 className="text-3xl font-bold tracking-tight">Job 관리</h2>
        <button
          onClick={() => setShowCreate(true)}
          className="flex items-center gap-2 px-5 py-2.5 bg-primary hover:bg-primary/90 text-white rounded-lg font-bold text-sm transition-all shadow-sm"
        >
          <span className="material-symbols-outlined text-[20px]">add</span>
          새 Job
        </button>
      </div>

      {/* Create Modal */}
      {showCreate && (
        <>
          <div className="fixed inset-0 bg-slate-900/50 backdrop-blur-sm z-40" onClick={() => { setShowCreate(false); resetForm(); }} />
          <div className="fixed inset-0 z-50 flex items-center justify-center p-4">
            <form
              onSubmit={handleCreate}
              className="bg-white dark:bg-slate-900 rounded-xl border border-slate-200 dark:border-slate-800 shadow-2xl w-full max-w-lg p-6 space-y-4 max-h-[90vh] overflow-y-auto"
            >
              <div className="flex items-center gap-2 mb-2">
                <span className="material-symbols-outlined text-primary">add_circle</span>
                <h3 className="text-lg font-bold">새 Job</h3>
              </div>

              <div>
                <label className="block text-xs font-bold text-slate-500 uppercase mb-1.5">이름</label>
                <input
                  value={jobName}
                  onChange={(e) => setJobName(e.target.value)}
                  required
                  maxLength={100}
                  placeholder="예: build-backend"
                  className="w-full px-3 py-2 border border-slate-300 dark:border-slate-700 dark:bg-slate-800 rounded-lg text-sm focus:ring-2 focus:ring-primary focus:border-primary outline-none transition-all"
                  autoFocus
                />
              </div>

              <div>
                <label className="block text-xs font-bold text-slate-500 uppercase mb-1.5">타입</label>
                <select
                  value={jobType}
                  onChange={(e) => {
                    const newType = e.target.value;
                    setJobType(newType);
                    if (Object.values(DEFAULT_SCRIPTS).includes(jenkinsScript)) {
                      setJenkinsScript(DEFAULT_SCRIPTS[newType] || DEFAULT_SCRIPTS.BUILD);
                    }
                  }}
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
                  placeholder={DEFAULT_SCRIPTS[jobType] || DEFAULT_SCRIPTS.BUILD}
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
                  onClick={() => { setShowCreate(false); resetForm(); }}
                  className="flex-1 px-4 py-2.5 border border-slate-200 dark:border-slate-700 text-slate-600 dark:text-slate-400 font-bold rounded-lg text-sm hover:bg-slate-50 dark:hover:bg-slate-800 transition-colors"
                >
                  취소
                </button>
                <button
                  type="submit"
                  disabled={!jobName.trim() || !presetId || createJob.isPending}
                  className="flex-1 px-4 py-2.5 bg-primary text-white font-bold rounded-lg text-sm hover:bg-primary/90 shadow-lg shadow-primary/20 transition-all disabled:opacity-50 disabled:cursor-not-allowed"
                >
                  {createJob.isPending ? '생성 중...' : '생성'}
                </button>
              </div>
            </form>
          </div>
        </>
      )}

      {/* Table Card */}
      <div className="bg-white dark:bg-slate-900 rounded-xl border border-slate-200 dark:border-slate-800 shadow-sm overflow-hidden">
        <table className="w-full text-left border-collapse">
          <thead>
            <tr className="bg-slate-50/50 dark:bg-slate-800/50 text-slate-500 text-xs font-semibold uppercase tracking-wider">
              <th className="px-6 py-4 border-b border-slate-200 dark:border-slate-800">ID</th>
              <th className="px-6 py-4 border-b border-slate-200 dark:border-slate-800">이름</th>
              <th className="px-6 py-4 border-b border-slate-200 dark:border-slate-800">타입</th>
              <th className="px-6 py-4 border-b border-slate-200 dark:border-slate-800">프리셋</th>
              <th className="px-6 py-4 border-b border-slate-200 dark:border-slate-800">Jenkins</th>
              <th className="px-6 py-4 border-b border-slate-200 dark:border-slate-800">생성일</th>
              <th className="px-6 py-4 border-b border-slate-200 dark:border-slate-800">액션</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-slate-100 dark:divide-slate-800">
            {jobs?.map((job) => {
              const badge = job.jenkinsStatus ? JENKINS_STATUS_BADGE[job.jenkinsStatus] : null;
              const isActive = !job.jenkinsScript || job.jenkinsStatus === 'ACTIVE';

              return (
                <tr
                  key={job.id}
                  onClick={() => navigate(`/jobs/${job.id}`)}
                  className="hover:bg-slate-50 dark:hover:bg-slate-800/50 transition-colors cursor-pointer"
                >
                  <td className="px-6 py-4 text-sm font-medium text-slate-500">#{job.id}</td>
                  <td className="px-6 py-4 text-sm font-semibold text-slate-700 dark:text-slate-200">{job.jobName}</td>
                  <td className="px-6 py-4 text-sm text-slate-500">{JOB_TYPE_LABEL[job.jobType] ?? job.jobType}</td>
                  <td className="px-6 py-4 text-sm text-slate-500">{job.presetName || '-'}</td>
                  <td className="px-6 py-4 text-sm">
                    {badge ? (
                      <span className={`inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-xs font-medium ${badge.className}`}>
                        <span className="material-symbols-outlined text-[14px]">{badge.icon}</span>
                        {badge.label}
                      </span>
                    ) : (
                      <span className="text-slate-400">-</span>
                    )}
                  </td>
                  <td className="px-6 py-4 text-sm text-slate-500">
                    {new Date(job.createdAt).toLocaleDateString()}
                  </td>
                  <td className="px-6 py-4" onClick={(e) => e.stopPropagation()}>
                    <div className="flex items-center gap-2">
                      <button
                        onClick={() => handleExecute(job.id)}
                        disabled={!isActive}
                        className="text-primary hover:text-primary/70 transition-colors disabled:opacity-30 disabled:cursor-not-allowed"
                        title={isActive ? '실행' : 'Jenkins 파이프라인이 아직 준비되지 않음'}
                      >
                        <span className="material-symbols-outlined text-[20px]">play_arrow</span>
                      </button>
                      {job.jenkinsStatus === 'FAILED' && (
                        <button
                          onClick={() => handleRetryProvision(job.id)}
                          className="text-amber-500 hover:text-amber-700 transition-colors"
                          title="Jenkins 등록 재시도"
                        >
                          <span className="material-symbols-outlined text-[20px]">refresh</span>
                        </button>
                      )}
                      <button
                        onClick={() => handleDelete(job.id, job.jobName)}
                        className="text-red-500 hover:text-red-700 transition-colors"
                        title="삭제"
                      >
                        <span className="material-symbols-outlined text-[20px]">delete</span>
                      </button>
                    </div>
                  </td>
                </tr>
              );
            })}
            {(!jobs || jobs.length === 0) && (
              <tr>
                <td colSpan={7} className="px-6 py-12 text-center text-slate-400 text-sm">
                  등록된 Job이 없습니다.
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>
    </div>
  );
}
