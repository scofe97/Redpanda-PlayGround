import { useState } from 'react';
import type { ParameterSchema } from '../api/pipelineDefinitionApi';

interface ParameterInputModalProps {
  schemas: ParameterSchema[];
  onSubmit: (params: Record<string, string>) => void;
  onCancel: () => void;
  isPending: boolean;
}

export default function ParameterInputModal({ schemas, onSubmit, onCancel, isPending }: ParameterInputModalProps) {
  const [values, setValues] = useState<Record<string, string>>(() => {
    const initial: Record<string, string> = {};
    for (const s of schemas) {
      initial[s.name] = s.defaultValue ?? '';
    }
    return initial;
  });

  const handleChange = (name: string, value: string) => {
    setValues((prev) => ({ ...prev, [name]: value }));
  };

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    const params: Record<string, string> = {};
    for (const s of schemas) {
      const v = values[s.name]?.trim();
      if (v) params[s.name] = v;
    }
    onSubmit(params);
  };

  const hasRequiredMissing = schemas.some(
    (s) => s.required && !values[s.name]?.trim(),
  );

  return (
    <>
      <div className="fixed inset-0 bg-slate-900/50 backdrop-blur-sm z-40" onClick={onCancel} />
      <div className="fixed inset-0 z-50 flex items-center justify-center p-4">
        <form
          onSubmit={handleSubmit}
          className="bg-white dark:bg-slate-900 rounded-xl border border-slate-200 dark:border-slate-800 shadow-2xl w-full max-w-md p-6 space-y-4"
        >
          <div className="flex items-center gap-2 mb-2">
            <span className="material-symbols-outlined text-primary">tune</span>
            <h3 className="text-lg font-bold">실행 파라미터</h3>
          </div>

          <p className="text-sm text-slate-500">
            파이프라인 실행에 필요한 파라미터를 입력하세요.
          </p>

          <div className="space-y-3">
            {schemas.map((s) => (
              <div key={s.name}>
                <label className="flex items-center gap-1.5 text-xs font-bold text-slate-500 uppercase mb-1.5">
                  {s.name}
                  {s.required && <span className="text-red-500">*</span>}
                  <span className="text-[10px] font-normal text-slate-400 lowercase">({s.type})</span>
                </label>
                <input
                  value={values[s.name] ?? ''}
                  onChange={(e) => handleChange(s.name, e.target.value)}
                  required={s.required}
                  placeholder={s.defaultValue ? `기본값: ${s.defaultValue}` : s.required ? '필수' : '선택'}
                  className="w-full px-3 py-2 border border-slate-300 dark:border-slate-700 dark:bg-slate-800 rounded-lg text-sm focus:ring-2 focus:ring-primary focus:border-primary outline-none transition-all font-mono"
                />
              </div>
            ))}
          </div>

          <div className="flex gap-3 pt-2">
            <button
              type="button"
              onClick={onCancel}
              className="flex-1 px-4 py-2.5 border border-slate-200 dark:border-slate-700 text-slate-600 dark:text-slate-400 font-bold rounded-lg text-sm hover:bg-slate-50 dark:hover:bg-slate-800 transition-colors"
            >
              취소
            </button>
            <button
              type="submit"
              disabled={hasRequiredMissing || isPending}
              className="flex-1 px-4 py-2.5 bg-primary text-white font-bold rounded-lg text-sm hover:bg-primary/90 shadow-lg shadow-primary/20 transition-all disabled:opacity-50 disabled:cursor-not-allowed flex items-center justify-center gap-2"
            >
              <span className="material-symbols-outlined text-sm">play_arrow</span>
              {isPending ? '실행 중...' : '실행'}
            </button>
          </div>
        </form>
      </div>
    </>
  );
}
