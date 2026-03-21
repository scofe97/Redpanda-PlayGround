import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import toast from 'react-hot-toast';
import { useCreatePipelineDefinition } from '../hooks/usePipelineDefinition';

const FAILURE_POLICIES = [
  { value: 'STOP', label: 'STOP — 실패 시 즉시 중단' },
  { value: 'CONTINUE', label: 'CONTINUE — 실패해도 계속 진행' },
];

export default function PipelineCreatePage() {
  const navigate = useNavigate();
  const createPipeline = useCreatePipelineDefinition();

  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const [failurePolicy, setFailurePolicy] = useState('STOP');

  const handleSubmit = async (e: React.FormEvent<HTMLFormElement>) => {
    e.preventDefault();
    if (!name.trim()) return;
    try {
      const result = await createPipeline.mutateAsync({
        name: name.trim(),
        description: description.trim() || undefined,
        failurePolicy,
      });
      navigate(`/pipelines/${result.id}`);
    } catch (err) {
      toast.error(err instanceof Error ? err.message : 'Failed to create pipeline');
    }
  };

  return (
    <div className="flex-1 overflow-y-auto">
      <div className="max-w-2xl mx-auto py-10 px-8">
        {/* Header */}
        <div className="flex items-center gap-3 mb-8">
          <button
            type="button"
            onClick={() => navigate('/pipelines')}
            className="p-2 rounded-lg hover:bg-slate-100 dark:hover:bg-slate-800 transition-colors text-slate-500"
            title="목록으로"
          >
            <span className="material-symbols-outlined text-[20px]">arrow_back</span>
          </button>
          <div>
            <h2 className="text-3xl font-black tracking-tight">새 파이프라인</h2>
            <p className="text-slate-500 mt-1 text-sm">새로운 파이프라인 정의를 생성합니다.</p>
          </div>
        </div>

        <form onSubmit={handleSubmit}>
          {/* Form Card */}
          <div className="bg-white dark:bg-slate-900 rounded-xl border border-slate-200 dark:border-slate-800 shadow-sm overflow-hidden mb-6">
            <div className="p-5 border-b border-slate-100 dark:border-slate-800">
              <h3 className="font-bold text-slate-700 dark:text-slate-200">기본 정보</h3>
            </div>
            <div className="p-5 space-y-5">
              <div>
                <label className="block text-xs font-bold text-slate-500 uppercase tracking-wider mb-1.5">
                  이름 <span className="text-red-500">*</span>
                </label>
                <input
                  value={name}
                  onChange={(e) => setName(e.target.value)}
                  required
                  maxLength={100}
                  placeholder="예: my-deploy-pipeline"
                  autoFocus
                  className="w-full px-3 py-2 border border-slate-300 dark:border-slate-700 dark:bg-slate-800 rounded-lg text-sm focus:ring-2 focus:ring-primary focus:border-primary outline-none transition-all"
                />
              </div>

              <div>
                <label className="block text-xs font-bold text-slate-500 uppercase tracking-wider mb-1.5">
                  설명 <span className="text-slate-400 font-normal normal-case">(선택)</span>
                </label>
                <textarea
                  value={description}
                  onChange={(e) => setDescription(e.target.value)}
                  maxLength={500}
                  rows={3}
                  placeholder="파이프라인에 대한 설명을 입력하세요"
                  className="w-full px-3 py-2 border border-slate-300 dark:border-slate-700 dark:bg-slate-800 rounded-lg text-sm focus:ring-2 focus:ring-primary focus:border-primary outline-none transition-all resize-none"
                />
              </div>

              <div>
                <label className="block text-xs font-bold text-slate-500 uppercase tracking-wider mb-1.5">
                  실패 정책
                </label>
                <select
                  value={failurePolicy}
                  onChange={(e) => setFailurePolicy(e.target.value)}
                  className="w-full px-3 py-2 border border-slate-300 dark:border-slate-700 dark:bg-slate-800 rounded-lg text-sm focus:ring-2 focus:ring-primary focus:border-primary outline-none transition-all"
                >
                  {FAILURE_POLICIES.map((p) => (
                    <option key={p.value} value={p.value}>{p.label}</option>
                  ))}
                </select>
              </div>
            </div>
          </div>

          {/* Actions */}
          <div className="flex items-center justify-end gap-3 pt-2">
            <button
              type="button"
              onClick={() => navigate('/pipelines')}
              className="px-6 py-2.5 text-sm font-bold text-slate-600 dark:text-slate-400 hover:bg-slate-100 dark:hover:bg-slate-800 rounded-lg transition-colors"
            >
              취소
            </button>
            <button
              type="submit"
              disabled={!name.trim() || createPipeline.isPending}
              className="px-8 py-2.5 text-sm font-bold text-white bg-primary hover:bg-primary/90 rounded-lg shadow-lg shadow-primary/20 transition-all disabled:opacity-50 disabled:cursor-not-allowed"
            >
              {createPipeline.isPending ? '생성 중...' : '파이프라인 생성'}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}
