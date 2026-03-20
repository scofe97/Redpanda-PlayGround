import { useState } from 'react';
import type { PipelineJobLocal } from '../api/pipelineDefinitionApi';

const JOB_TYPES = ['BUILD', 'DEPLOY'] as const;

interface PipelineJobFormProps {
  existingJobs: PipelineJobLocal[];
  onAdd: (job: PipelineJobLocal) => void;
  onCancel: () => void;
  editJob?: PipelineJobLocal;
}

export default function PipelineJobForm({ existingJobs, onAdd, onCancel, editJob }: PipelineJobFormProps) {
  const [jobName, setJobName] = useState(editJob?.jobName ?? '');
  const [jobType, setJobType] = useState(editJob?.jobType ?? 'BUILD');
  const [dependsOn, setDependsOn] = useState<string[]>(editJob?.dependsOn ?? []);

  const availableDeps = existingJobs.filter((j) => j.jobName !== editJob?.jobName);

  const toggleDep = (name: string) => {
    setDependsOn((prev) => (prev.includes(name) ? prev.filter((d) => d !== name) : [...prev, name]));
  };

  const handleSubmit = (e: React.FormEvent<HTMLFormElement>) => {
    e.preventDefault();
    if (!jobName.trim()) return;

    const maxOrder = existingJobs.reduce((max, j) => Math.max(max, j.executionOrder), 0);
    onAdd({
      id: editJob?.id,
      jobName: jobName.trim(),
      jobType,
      executionOrder: editJob?.executionOrder ?? maxOrder + 1,
      dependsOn,
    });
  };

  return (
    <form onSubmit={handleSubmit} className="bg-white dark:bg-slate-900 rounded-xl border border-primary/30 shadow-sm p-5 space-y-4">
      <div className="flex items-center gap-2 mb-2">
        <span className="material-symbols-outlined text-primary text-lg">{editJob ? 'edit' : 'add_circle'}</span>
        <h4 className="font-bold text-sm">{editJob ? 'Job 수정' : '새 Job 추가'}</h4>
      </div>

      <div className="grid grid-cols-2 gap-4">
        <div>
          <label className="block text-xs font-bold text-slate-500 uppercase mb-1.5">Job 이름</label>
          <input
            value={jobName}
            onChange={(e) => setJobName(e.target.value)}
            required
            maxLength={100}
            placeholder="예: build-app"
            className="w-full px-3 py-2 border border-slate-300 dark:border-slate-700 dark:bg-slate-800 rounded-lg text-sm focus:ring-2 focus:ring-primary focus:border-primary outline-none transition-all"
          />
        </div>

        <div>
          <label className="block text-xs font-bold text-slate-500 uppercase mb-1.5">Job 타입</label>
          <select
            value={jobType}
            onChange={(e) => setJobType(e.target.value)}
            className="w-full px-3 py-2 border border-slate-300 dark:border-slate-700 dark:bg-slate-800 rounded-lg text-sm focus:ring-2 focus:ring-primary focus:border-primary outline-none transition-all"
          >
            {JOB_TYPES.map((t) => (
              <option key={t} value={t}>{t}</option>
            ))}
          </select>
        </div>
      </div>

      {availableDeps.length > 0 && (
        <div>
          <label className="block text-xs font-bold text-slate-500 uppercase mb-1.5">의존성 (dependsOn)</label>
          <div className="flex flex-wrap gap-2">
            {availableDeps.map((j) => {
              const selected = dependsOn.includes(j.jobName);
              return (
                <button
                  key={j.jobName}
                  type="button"
                  onClick={() => toggleDep(j.jobName)}
                  className={`px-3 py-1.5 rounded-full text-xs font-bold border transition-all ${
                    selected
                      ? 'bg-primary/10 border-primary text-primary'
                      : 'border-slate-200 dark:border-slate-700 text-slate-500 hover:border-slate-400'
                  }`}
                >
                  {j.jobName}
                </button>
              );
            })}
          </div>
        </div>
      )}

      <div className="flex gap-3 pt-2">
        <button
          type="button"
          onClick={onCancel}
          className="px-4 py-2 border border-slate-200 dark:border-slate-700 text-slate-600 dark:text-slate-400 font-bold rounded-lg text-sm hover:bg-slate-50 dark:hover:bg-slate-800 transition-colors"
        >
          취소
        </button>
        <button
          type="submit"
          disabled={!jobName.trim()}
          className="px-4 py-2 bg-primary text-white font-bold rounded-lg text-sm hover:bg-primary/90 shadow-sm transition-all disabled:opacity-50 disabled:cursor-not-allowed"
        >
          {editJob ? '수정' : '추가'}
        </button>
      </div>
    </form>
  );
}
