import { useState } from 'react';
import { Link } from 'react-router-dom';
import type { PipelineExecutionResponse } from '../api/pipelineDefinitionApi';

export function CopyableId({ id, label }: { id: string; label?: string }) {
  const [copied, setCopied] = useState(false);
  const short = id.slice(0, 8);
  const handleCopy = async (e: React.MouseEvent) => {
    e.stopPropagation();
    await navigator.clipboard.writeText(id);
    setCopied(true);
    setTimeout(() => setCopied(false), 1500);
  };
  return (
    <button
      onClick={handleCopy}
      title={`${label ? label + ': ' : ''}${id}\n클릭하여 복사`}
      className="inline-flex items-center gap-1 px-1.5 py-0.5 rounded text-xs font-mono text-slate-500 hover:bg-slate-200 dark:hover:bg-slate-700 transition-colors cursor-pointer"
    >
      {copied ? (
        <span className="text-emerald-500 flex items-center gap-1">
          <span className="material-symbols-outlined text-[12px]">check</span>
          복사됨
        </span>
      ) : (
        <>
          <span className="material-symbols-outlined text-[12px] text-slate-400">content_copy</span>
          {short}...
        </>
      )}
    </button>
  );
}

export const EXECUTION_STATUS_BADGE: Record<string, { icon: string; label: string; className: string }> = {
  PENDING:         { icon: 'hourglass_empty', label: '대기',         className: 'text-amber-600 bg-amber-50 dark:bg-amber-900/20' },
  RUNNING:         { icon: 'sync',            label: '실행 중',      className: 'text-blue-600 bg-blue-50 dark:bg-blue-900/20' },
  SUCCESS:         { icon: 'check_circle',    label: '성공',         className: 'text-emerald-600 bg-emerald-50 dark:bg-emerald-900/20' },
  FAILED:          { icon: 'error',           label: '실패',         className: 'text-red-600 bg-red-50 dark:bg-red-900/20' },
  WAITING_WEBHOOK: { icon: 'webhook',         label: '웹훅 대기',    className: 'text-purple-600 bg-purple-50 dark:bg-purple-900/20' },
  COMPENSATED:     { icon: 'undo',            label: '보상 처리',    className: 'text-orange-600 bg-orange-50 dark:bg-orange-900/20' },
  SKIPPED:         { icon: 'skip_next',       label: '건너뜀',       className: 'text-slate-500 bg-slate-50 dark:bg-slate-800' },
};

export function ExecutionStatusBadge({ status }: { status: string }) {
  const badge = EXECUTION_STATUS_BADGE[status] ?? { icon: 'help', label: status, className: 'text-slate-500 bg-slate-50 dark:bg-slate-800' };
  return (
    <span className={`inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-xs font-medium ${badge.className}`}>
      <span className={`material-symbols-outlined text-[13px]${status === 'RUNNING' ? ' animate-spin' : ''}`}>{badge.icon}</span>
      {badge.label}
    </span>
  );
}

export function formatTime(iso?: string) {
  if (!iso) return '-';
  return new Date(iso).toLocaleString();
}

interface ExecutionCardProps {
  execution: PipelineExecutionResponse;
  defaultOpen?: boolean;
  isSelected?: boolean;
  onSelect?: () => void;
  pipelineName?: string;
  pipelineId?: number;
}

export default function ExecutionCard({ execution, defaultOpen, isSelected, onSelect, pipelineName, pipelineId }: ExecutionCardProps) {
  const jobs = execution.jobExecutions ?? [];

  const handleSummaryClick = () => {
    onSelect?.();
  };

  return (
    <details open={defaultOpen} className={`group bg-slate-50 dark:bg-slate-800/50 rounded-lg border overflow-hidden ${isSelected ? 'border-l-4 border-l-primary border-primary/50' : 'border-slate-200 dark:border-slate-700'}`}>
      <summary onClick={handleSummaryClick} className="flex items-center justify-between px-4 py-3 cursor-pointer list-none hover:bg-slate-100 dark:hover:bg-slate-800 transition-colors">
        <div className="flex items-center gap-3 min-w-0">
          <ExecutionStatusBadge status={execution.status} />
          {pipelineName && (
            <Link
              to={`/pipelines/${pipelineId}`}
              onClick={(e) => e.stopPropagation()}
              className="text-xs font-semibold text-primary hover:underline truncate max-w-[150px]"
              title={pipelineName}
            >
              {pipelineName}
            </Link>
          )}
          <CopyableId id={execution.executionId} label="Execution ID" />
        </div>
        <div className="flex items-center gap-3 flex-shrink-0">
          <span className="text-[11px] text-slate-400">{formatTime(execution.startedAt)}</span>
          <span className="material-symbols-outlined text-[16px] text-slate-400 group-open:rotate-180 transition-transform">expand_more</span>
        </div>
      </summary>

      <div className="px-4 pb-4 pt-2 space-y-3 border-t border-slate-200 dark:border-slate-700">
        {execution.parameters && Object.keys(execution.parameters).length > 0 && (
          <div className="p-3 bg-primary/5 border border-primary/20 rounded-lg">
            <p className="text-[10px] font-bold text-primary/60 uppercase tracking-wider mb-1.5">파라미터</p>
            <div className="flex flex-wrap gap-1.5">
              {Object.entries(execution.parameters).map(([k, v]) => (
                <span key={k} className="inline-flex items-center gap-1 px-2 py-0.5 bg-white dark:bg-slate-800 rounded text-[11px] font-mono border border-slate-200 dark:border-slate-700">
                  <span className="text-slate-500">{k}=</span>
                  <span className="text-slate-700 dark:text-slate-300 font-semibold">{v}</span>
                </span>
              ))}
            </div>
          </div>
        )}

        {execution.context && Object.keys(execution.context).length > 0 && (
          <div className="p-3 bg-amber-50 dark:bg-amber-900/10 border border-amber-200 dark:border-amber-800 rounded-lg">
            <p className="text-[10px] font-bold text-amber-600/60 uppercase tracking-wider mb-1.5">빌드 컨텍스트</p>
            <div className="flex flex-wrap gap-1.5">
              {Object.entries(execution.context).map(([k, v]) => (
                <span key={k} className="inline-flex items-center gap-1 px-2 py-0.5 bg-white dark:bg-slate-800 rounded text-[11px] font-mono border border-slate-200 dark:border-slate-700">
                  <span className="text-amber-600">{k}=</span>
                  <span className="text-slate-700 dark:text-slate-300 font-semibold truncate max-w-[200px]" title={v}>{v}</span>
                </span>
              ))}
            </div>
          </div>
        )}

        {execution.errorMessage && (
          <div className="p-3 bg-red-50 dark:bg-red-900/20 border border-red-200 dark:border-red-800 rounded-lg">
            <p className="text-xs text-red-700 dark:text-red-400 font-mono break-all">{execution.errorMessage}</p>
          </div>
        )}

        {jobs.length === 0 ? (
          <p className="text-xs text-slate-400 text-center py-2">Job 실행 정보가 없습니다.</p>
        ) : (
          <div className="space-y-2">
            {[...jobs]
              .sort((a, b) => a.jobOrder - b.jobOrder)
              .map((job) => (
                <div
                  key={job.id}
                  className="flex items-start gap-3 p-3 bg-white dark:bg-slate-900 rounded-lg border border-slate-200 dark:border-slate-700"
                >
                  <span className="text-[11px] font-bold text-slate-400 w-5 text-center mt-0.5 flex-shrink-0">{job.jobOrder}</span>
                  <div className="flex-1 min-w-0">
                    <div className="flex items-center justify-between gap-2 flex-wrap">
                      <span className="text-sm font-semibold text-slate-700 dark:text-slate-200">{job.jobName}</span>
                      <ExecutionStatusBadge status={job.status} />
                    </div>
                    <div className="flex items-center gap-2 mt-0.5">
                      <span className="text-[10px] text-slate-400 uppercase">{job.jobType}</span>
                      <span className="text-[10px] text-slate-400">ID: {job.id}</span>
                      {job.retryCount > 0 && (
                        <span className="text-[10px] text-amber-500 font-medium">retry: {job.retryCount}</span>
                      )}
                    </div>
                    {job.startedAt && (
                      <p className="text-[11px] text-slate-400 mt-1">
                        {formatTime(job.startedAt)}
                        {job.completedAt && ` → ${formatTime(job.completedAt)}`}
                      </p>
                    )}
                  </div>
                </div>
              ))}
          </div>
        )}
      </div>
    </details>
  );
}
