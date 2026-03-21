import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import toast from 'react-hot-toast';
import { useCreateJob } from '../hooks/useJobs';
import { usePresetList } from '../hooks/usePipelineDefinition';
import ConfigJsonEditor from '../components/ConfigJsonEditor';

const JOB_TYPES = [
  { value: 'BUILD', label: '빌드' },
  { value: 'DEPLOY', label: '배포' },
];

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

const BUILD_DEFAULTS = [
  { key: 'GIT_URL', placeholder: 'Git 저장소 URL' },
  { key: 'BRANCH', placeholder: '브랜치 (기본: main)' },
];

function getDefaultKeys(type: string) {
  if (type === 'BUILD') return BUILD_DEFAULTS;
  return [];
}

export default function JobCreatePage() {
  const navigate = useNavigate();
  const createJob = useCreateJob();
  const { data: presets } = usePresetList();

  const [jobName, setJobName] = useState('');
  const [jobType, setJobType] = useState('BUILD');
  const [presetId, setPresetId] = useState<number | undefined>();
  const [configJson, setConfigJson] = useState('');
  const [jenkinsScript, setJenkinsScript] = useState(DEFAULT_SCRIPTS.BUILD);
  const [artifactSource, setArtifactSource] = useState<'build' | 'nexus'>('build');
  const [nexusUrl, setNexusUrl] = useState('');

  const handleTypeChange = (newType: string) => {
    setJobType(newType);
    if (Object.values(DEFAULT_SCRIPTS).includes(jenkinsScript)) {
      setJenkinsScript(DEFAULT_SCRIPTS[newType] || DEFAULT_SCRIPTS.BUILD);
    }
    setConfigJson('');
    setArtifactSource('build');
    setNexusUrl('');
  };

  const resolveConfigJson = (): string | undefined => {
    if (jobType === 'DEPLOY') {
      if (artifactSource === 'nexus' && nexusUrl.trim()) {
        return JSON.stringify({ ARTIFACT_URL: nexusUrl.trim() });
      }
      return undefined;
    }
    return configJson.trim() || undefined;
  };

  const handleSubmit = async (e: React.FormEvent<HTMLFormElement>) => {
    e.preventDefault();
    if (!jobName.trim() || !presetId) return;
    try {
      await createJob.mutateAsync({
        jobName: jobName.trim(),
        jobType,
        presetId,
        configJson: resolveConfigJson(),
        jenkinsScript: jenkinsScript.trim() || undefined,
      });
      navigate('/jobs');
    } catch (err) {
      toast.error(err instanceof Error ? err.message : 'Failed to create job');
    }
  };

  return (
    <div className="flex-1 overflow-y-auto">
      <div className="max-w-2xl mx-auto py-10 px-8">
        {/* Header */}
        <div className="flex items-center gap-3 mb-8">
          <button
            type="button"
            onClick={() => navigate('/jobs')}
            className="p-2 rounded-lg hover:bg-slate-100 dark:hover:bg-slate-800 transition-colors text-slate-500"
            title="목록으로"
          >
            <span className="material-symbols-outlined text-[20px]">arrow_back</span>
          </button>
          <div>
            <h2 className="text-3xl font-black tracking-tight">새 Job</h2>
            <p className="text-slate-500 mt-1 text-sm">새로운 Jenkins Job을 생성합니다.</p>
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
                  placeholder="예: build-backend"
                  autoFocus
                  className="w-full px-3 py-2 border border-slate-300 dark:border-slate-700 dark:bg-slate-800 rounded-lg text-sm focus:ring-2 focus:ring-primary focus:border-primary outline-none transition-all"
                />
              </div>

              <div className="grid grid-cols-2 gap-4">
                <div>
                  <label className="block text-xs font-bold text-slate-500 uppercase tracking-wider mb-1.5">
                    타입 <span className="text-red-500">*</span>
                  </label>
                  <select
                    value={jobType}
                    onChange={(e) => handleTypeChange(e.target.value)}
                    className="w-full px-3 py-2 border border-slate-300 dark:border-slate-700 dark:bg-slate-800 rounded-lg text-sm focus:ring-2 focus:ring-primary focus:border-primary outline-none transition-all"
                  >
                    {JOB_TYPES.map((t) => (
                      <option key={t.value} value={t.value}>{t.label}</option>
                    ))}
                  </select>
                </div>

                <div>
                  <label className="block text-xs font-bold text-slate-500 uppercase tracking-wider mb-1.5">
                    프리셋 <span className="text-red-500">*</span>
                  </label>
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
                placeholder={DEFAULT_SCRIPTS[jobType] || DEFAULT_SCRIPTS.BUILD}
                className="w-full px-3 py-2 border border-slate-300 dark:border-slate-700 dark:bg-slate-800 rounded-lg text-sm focus:ring-2 focus:ring-primary focus:border-primary outline-none transition-all font-mono resize-y leading-relaxed"
              />
            </div>
          </div>

          {/* DEPLOY: Artifact Source */}
          {jobType === 'DEPLOY' && (
            <div className="bg-white dark:bg-slate-900 rounded-xl border border-slate-200 dark:border-slate-800 shadow-sm overflow-hidden">
              <div className="p-5 border-b border-slate-100 dark:border-slate-800">
                <h3 className="font-bold text-slate-700 dark:text-slate-200">아티팩트 소스</h3>
              </div>
              <div className="p-5 space-y-4">
                <label className="flex items-start gap-3 p-3 rounded-lg border border-slate-200 dark:border-slate-700 cursor-pointer hover:bg-slate-50 dark:hover:bg-slate-800/50 transition-colors">
                  <input type="radio" name="artifactSource" value="build" checked={artifactSource === 'build'} onChange={() => setArtifactSource('build')} className="mt-0.5" />
                  <div>
                    <p className="font-semibold text-sm text-slate-700 dark:text-slate-200">빌드 연계</p>
                    <p className="text-xs text-slate-500 mt-0.5">파이프라인에서 BUILD Job에 의존성을 설정하면 빌드 결과물이 자동으로 주입됩니다.</p>
                  </div>
                </label>
                <label className="flex items-start gap-3 p-3 rounded-lg border border-slate-200 dark:border-slate-700 cursor-pointer hover:bg-slate-50 dark:hover:bg-slate-800/50 transition-colors">
                  <input type="radio" name="artifactSource" value="nexus" checked={artifactSource === 'nexus'} onChange={() => setArtifactSource('nexus')} className="mt-0.5" />
                  <div>
                    <p className="font-semibold text-sm text-slate-700 dark:text-slate-200">Nexus 직접 입력</p>
                    <p className="text-xs text-slate-500 mt-0.5">Nexus 저장소에 이미 올라가 있는 아티팩트 URL을 직접 입력합니다.</p>
                  </div>
                </label>
                {artifactSource === 'nexus' && (
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
              onClick={() => navigate('/jobs')}
              className="px-6 py-2.5 text-sm font-bold text-slate-600 dark:text-slate-400 hover:bg-slate-100 dark:hover:bg-slate-800 rounded-lg transition-colors"
            >
              취소
            </button>
            <button
              type="submit"
              disabled={!jobName.trim() || !presetId || createJob.isPending}
              className="px-8 py-2.5 text-sm font-bold text-white bg-primary hover:bg-primary/90 rounded-lg shadow-lg shadow-primary/20 transition-all disabled:opacity-50 disabled:cursor-not-allowed"
            >
              {createJob.isPending ? '생성 중...' : 'Job 생성'}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}
