import { useState, useEffect } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import toast from 'react-hot-toast';
import { useJob, useUpdateJob, useJobList } from '../hooks/useJobs';
import { usePurposeList } from '../hooks/usePipelineDefinition';
import { useProjectList } from '../hooks/useProject';
import ConfigJsonEditor from '../components/ConfigJsonEditor';

const JOB_TYPES = [
  { value: 'BUILD', label: '빌드' },
  { value: 'DEPLOY', label: '배포' },
];

const BUILD_DEFAULTS = [
  { key: 'GIT_URL', placeholder: 'Git 저장소 URL' },
  { key: 'BRANCH', placeholder: '브랜치 (기본: main)' },
];

function getDefaultKeys(type: string) {
  if (type === 'BUILD') return BUILD_DEFAULTS;
  return [];
}

export default function JobEditPage() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const jobId = Number(id);
  const isValidId = !isNaN(jobId) && jobId > 0;

  const { data: job, isLoading, error } = useJob(isValidId ? jobId : -1);
  const { data: purposes } = usePurposeList();
  const { data: projects } = useProjectList();
  const { data: allJobs } = useJobList();
  const buildJobs = allJobs?.filter((j) => j.jobType === 'BUILD') ?? [];
  const updateJob = useUpdateJob();

  const [jobName, setJobName] = useState('');
  const [jobType, setJobType] = useState('BUILD');
  const [projectId, setProjectId] = useState<number | undefined>();
  const [purposeId, setPurposeId] = useState<number | undefined>();

  const filteredPurposes = purposes?.filter((p) => projectId ? p.projectId === projectId : true) ?? [];
  const [configJson, setConfigJson] = useState('');
  const [jenkinsScript, setJenkinsScript] = useState('');
  const [deployMode, setDeployMode] = useState<'BUILD_REQUIRED' | 'IMPORT'>('BUILD_REQUIRED');
  const [requiredBuildJobId, setRequiredBuildJobId] = useState<number | undefined>();
  const [nexusUrl, setNexusUrl] = useState('');

  useEffect(() => {
    if (job) {
      setJobName(job.jobName);
      setJobType(job.jobType);
      setPurposeId(job.purposeId);
      // 목적의 projectId로 프로젝트 자동 선택
      const purpose = purposes?.find((p) => p.id === job.purposeId);
      if (purpose?.projectId) setProjectId(purpose.projectId);
      setConfigJson(job.configJson ?? '');
      setJenkinsScript(job.jenkinsScript ?? '');
      if (job.jobType === 'DEPLOY') {
        setDeployMode(job.deployMode ?? 'BUILD_REQUIRED');
        setRequiredBuildJobId(job.requiredBuildJobId);
        // IMPORT 모드에서 Nexus URL 복원
        if (job.deployMode === 'IMPORT' && job.configJson) {
          try {
            const parsed = JSON.parse(job.configJson);
            if (parsed.ARTIFACT_URL) {
              setNexusUrl(parsed.ARTIFACT_URL);
            }
          } catch { /* ignore */ }
        }
      }
    }
  }, [job]);

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

  if (error || !job) {
    const isNotFound = error && 'status' in error && (error as any).status === 404;
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
              <p className="text-red-600 mt-2 text-sm">{error?.message}</p>
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

  const resolveConfigJson = (): string | undefined => {
    if (jobType === 'DEPLOY') {
      if (deployMode === 'IMPORT' && nexusUrl.trim()) {
        return JSON.stringify({ ARTIFACT_URL: nexusUrl.trim() });
      }
      return undefined;
    }
    return configJson.trim() || undefined;
  };

  const handleSubmit = async (e: React.FormEvent<HTMLFormElement>) => {
    e.preventDefault();
    if (!jobName.trim() || !purposeId) return;
    try {
      await updateJob.mutateAsync({
        id: jobId,
        data: {
          jobName: jobName.trim(),
          jobType,
          purposeId,
          configJson: resolveConfigJson(),
          jenkinsScript: jenkinsScript.trim() || undefined,
          ...(jobType === 'DEPLOY' && {
            deployMode,
            requiredBuildJobId: deployMode === 'BUILD_REQUIRED' ? requiredBuildJobId : undefined,
          }),
        },
      });
      navigate(`/jobs/${jobId}`);
    } catch (err) {
      toast.error(err instanceof Error ? err.message : 'Failed to update job');
    }
  };

  return (
    <div className="flex-1 overflow-y-auto">
      <div className="max-w-[1200px] mx-auto py-10 px-8">
        {/* Header */}
        <div className="flex items-center gap-3 mb-8">
          <button
            type="button"
            onClick={() => navigate(`/jobs/${jobId}`)}
            className="p-2 rounded-lg hover:bg-slate-100 dark:hover:bg-slate-800 transition-colors text-slate-500"
            title="돌아가기"
          >
            <span className="material-symbols-outlined text-[20px]">arrow_back</span>
          </button>
          <div>
            <h2 className="text-3xl font-black tracking-tight">Job 편집</h2>
            <p className="text-slate-500 mt-1 text-sm">{job.jobName}</p>
          </div>
        </div>

        <form onSubmit={handleSubmit} className="space-y-6">
          {/* Basic Info Card */}
          <div className="bg-white dark:bg-slate-900 rounded-xl border border-slate-200 dark:border-slate-800 shadow-sm overflow-hidden">
            <div className="p-5 border-b border-slate-100 dark:border-slate-800">
              <h3 className="font-bold text-slate-700 dark:text-slate-200">기본 정보</h3>
            </div>
            <div className="p-5 space-y-5">
              <div>
                <label className="block text-xs font-bold text-slate-500 uppercase tracking-wider mb-1.5">
                  이름 <span className="text-red-500">*</span>
                </label>
                <input
                  value={jobName}
                  onChange={(e) => setJobName(e.target.value)}
                  required
                  maxLength={100}
                  autoFocus
                  className="w-full px-3 py-2 border border-slate-300 dark:border-slate-700 dark:bg-slate-800 rounded-lg text-sm focus:ring-2 focus:ring-primary focus:border-primary outline-none transition-all"
                />
              </div>

              <div className="grid grid-cols-3 gap-4">
                <div>
                  <label className="block text-xs font-bold text-slate-500 uppercase tracking-wider mb-1.5">
                    타입 <span className="text-red-500">*</span>
                  </label>
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
                  <label className="block text-xs font-bold text-slate-500 uppercase tracking-wider mb-1.5">
                    프로젝트 <span className="text-red-500">*</span>
                  </label>
                  <select
                    value={projectId ?? ''}
                    onChange={(e) => { setProjectId(e.target.value ? Number(e.target.value) : undefined); setPurposeId(undefined); }}
                    required
                    className="w-full px-3 py-2 border border-slate-300 dark:border-slate-700 dark:bg-slate-800 rounded-lg text-sm focus:ring-2 focus:ring-primary focus:border-primary outline-none transition-all"
                  >
                    <option value="">프로젝트 선택</option>
                    {projects?.map((p) => (
                      <option key={p.id} value={p.id}>{p.name}</option>
                    ))}
                  </select>
                </div>

                <div>
                  <label className="block text-xs font-bold text-slate-500 uppercase tracking-wider mb-1.5">
                    목적 <span className="text-red-500">*</span>
                  </label>
                  <select
                    value={purposeId ?? ''}
                    onChange={(e) => setPurposeId(e.target.value ? Number(e.target.value) : undefined)}
                    required
                    disabled={!projectId}
                    className="w-full px-3 py-2 border border-slate-300 dark:border-slate-700 dark:bg-slate-800 rounded-lg text-sm focus:ring-2 focus:ring-primary focus:border-primary outline-none transition-all disabled:opacity-50"
                  >
                    <option value="">{projectId ? '목적 선택' : '프로젝트를 먼저 선택'}</option>
                    {filteredPurposes.map((p) => (
                      <option key={p.id} value={p.id}>{p.name}</option>
                    ))}
                  </select>
                </div>
              </div>
            </div>
          </div>

          {/* Script Card */}
          <div className="bg-white dark:bg-slate-900 rounded-xl border border-slate-200 dark:border-slate-800 shadow-sm overflow-hidden">
            <div className="p-5 border-b border-slate-100 dark:border-slate-800">
              <h3 className="font-bold text-slate-700 dark:text-slate-200">Jenkinsfile 스크립트</h3>
            </div>
            <div className="p-5">
              <textarea
                value={jenkinsScript}
                onChange={(e) => setJenkinsScript(e.target.value)}
                rows={14}
                className="w-full px-3 py-2 border border-slate-300 dark:border-slate-700 dark:bg-slate-800 rounded-lg text-sm focus:ring-2 focus:ring-primary focus:border-primary outline-none transition-all font-mono resize-y leading-relaxed"
              />
            </div>
          </div>

          {/* DEPLOY: 배포 모드 */}
          {jobType === 'DEPLOY' && (
            <div className="bg-white dark:bg-slate-900 rounded-xl border border-slate-200 dark:border-slate-800 shadow-sm overflow-hidden">
              <div className="p-5 border-b border-slate-100 dark:border-slate-800">
                <h3 className="font-bold text-slate-700 dark:text-slate-200">배포 모드</h3>
              </div>
              <div className="p-5 space-y-4">
                <label className="flex items-start gap-3 p-3 rounded-lg border border-slate-200 dark:border-slate-700 cursor-pointer hover:bg-slate-50 dark:hover:bg-slate-800/50 transition-colors">
                  <input type="radio" name="deployMode" value="BUILD_REQUIRED" checked={deployMode === 'BUILD_REQUIRED'} onChange={() => { setDeployMode('BUILD_REQUIRED'); setNexusUrl(''); }} className="mt-0.5" />
                  <div>
                    <p className="font-semibold text-sm text-slate-700 dark:text-slate-200">빌드 연계</p>
                    <p className="text-xs text-slate-500 mt-0.5">빌드 Job의 결과물을 배포합니다. 파이프라인에서 빌드 Job이 자동으로 연결됩니다.</p>
                  </div>
                </label>
                {deployMode === 'BUILD_REQUIRED' && (
                  <div className="pl-8">
                    <label className="block text-xs font-bold text-slate-500 uppercase tracking-wider mb-1.5">
                      빌드 Job 선택 <span className="text-red-500">*</span>
                    </label>
                    <select
                      value={requiredBuildJobId ?? ''}
                      onChange={(e) => setRequiredBuildJobId(e.target.value ? Number(e.target.value) : undefined)}
                      className="w-full px-3 py-2 border border-slate-300 dark:border-slate-700 dark:bg-slate-800 rounded-lg text-sm focus:ring-2 focus:ring-primary focus:border-primary outline-none transition-all"
                    >
                      <option value="">빌드 Job을 선택하세요</option>
                      {buildJobs.map((j) => (
                        <option key={j.id} value={j.id}>{j.jobName}</option>
                      ))}
                    </select>
                    {buildJobs.length === 0 && (
                      <p className="text-xs text-amber-600 mt-1.5">등록된 빌드 Job이 없습니다. 먼저 빌드 Job을 생성하세요.</p>
                    )}
                  </div>
                )}
                <label className="flex items-start gap-3 p-3 rounded-lg border border-slate-200 dark:border-slate-700 cursor-pointer hover:bg-slate-50 dark:hover:bg-slate-800/50 transition-colors">
                  <input type="radio" name="deployMode" value="IMPORT" checked={deployMode === 'IMPORT'} onChange={() => { setDeployMode('IMPORT'); setRequiredBuildJobId(undefined); }} className="mt-0.5" />
                  <div>
                    <p className="font-semibold text-sm text-slate-700 dark:text-slate-200">반입 (Nexus URL)</p>
                    <p className="text-xs text-slate-500 mt-0.5">Nexus 저장소에 이미 올라가 있는 아티팩트 URL을 직접 입력합니다. 단독 실행 가능.</p>
                  </div>
                </label>
                {deployMode === 'IMPORT' && (
                  <div className="pl-8">
                    <label className="block text-xs font-bold text-slate-500 uppercase tracking-wider mb-1.5">ARTIFACT_URL</label>
                    <input
                      value={nexusUrl}
                      onChange={(e) => setNexusUrl(e.target.value)}
                      placeholder="http://nexus-host/repository/.../artifact.war"
                      className="w-full px-3 py-2 border border-slate-300 dark:border-slate-700 dark:bg-slate-800 rounded-lg text-sm focus:ring-2 focus:ring-primary focus:border-primary outline-none transition-all"
                    />
                  </div>
                )}
              </div>
            </div>
          )}

          {/* Config: BUILD only */}
          {jobType === 'BUILD' && (
            <div className="bg-white dark:bg-slate-900 rounded-xl border border-slate-200 dark:border-slate-800 shadow-sm overflow-hidden">
              <div className="p-5 border-b border-slate-100 dark:border-slate-800">
                <div className="flex items-center justify-between">
                  <h3 className="font-bold text-slate-700 dark:text-slate-200">빌드 설정</h3>
                  <span className="text-xs text-slate-400">선택</span>
                </div>
              </div>
              <div className="p-5">
                <ConfigJsonEditor value={configJson} onChange={setConfigJson} defaults={getDefaultKeys(jobType)} />
              </div>
            </div>
          )}

          {/* Actions */}
          <div className="flex items-center justify-end gap-3 pt-2">
            <button
              type="button"
              onClick={() => navigate(`/jobs/${jobId}`)}
              className="px-6 py-2.5 text-sm font-bold text-slate-600 dark:text-slate-400 hover:bg-slate-100 dark:hover:bg-slate-800 rounded-lg transition-colors"
            >
              취소
            </button>
            <button
              type="submit"
              disabled={!jobName.trim() || !purposeId || updateJob.isPending}
              className="px-8 py-2.5 text-sm font-bold text-white bg-primary hover:bg-primary/90 rounded-lg shadow-lg shadow-primary/20 transition-all disabled:opacity-50 disabled:cursor-not-allowed"
            >
              {updateJob.isPending ? '저장 중...' : '변경사항 저장'}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}
