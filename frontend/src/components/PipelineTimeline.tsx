import type { PipelineStep } from '../api/pipelineApi';

interface PipelineTimelineProps {
  steps: PipelineStep[];
}

function stepDotStyle(status: string) {
  switch (status.toLowerCase()) {
    case 'success':
      return 'bg-green-500';
    case 'running':
      return 'bg-primary';
    case 'failed':
      return 'bg-red-500';
    case 'waiting_webhook':
      return 'bg-amber-500';
    default:
      return 'bg-slate-200 dark:bg-slate-800';
  }
}

function stepTextStyle(status: string) {
  switch (status.toLowerCase()) {
    case 'success':
      return 'text-green-600 dark:text-green-400';
    case 'running':
      return 'text-primary';
    case 'failed':
      return 'text-red-600 dark:text-red-400';
    case 'waiting_webhook':
      return 'text-amber-600 dark:text-amber-400';
    default:
      return 'text-slate-500';
  }
}

export default function PipelineTimeline({ steps }: PipelineTimelineProps) {
  if (!steps || steps.length === 0) {
    return <p className="text-sm text-slate-500">파이프라인 단계가 없습니다.</p>;
  }

  return (
    <div className="p-6">
      <div className="space-y-0">
        {steps.map((step, index) => {
          const isLast = index === steps.length - 1;
          const isRunning = step.status.toLowerCase() === 'running';
          const isSuccess = step.status.toLowerCase() === 'success';

          return (
            <div key={step.id} className={`relative pl-8 ${isLast ? '' : 'pb-10'}`}>
              {/* Dot */}
              <div
                className={`absolute left-0 top-1 w-5 h-5 rounded-full ${stepDotStyle(step.status)} border-4 border-white dark:border-slate-900 z-10 flex items-center justify-center`}
              >
                {isSuccess ? (
                  <span className="material-symbols-outlined text-[10px] text-white font-bold">check</span>
                ) : isRunning ? (
                  <span className="w-1.5 h-1.5 bg-white rounded-full animate-pulse" />
                ) : (
                  <span className="w-1.5 h-1.5 bg-slate-400 dark:bg-slate-600 rounded-full" />
                )}
              </div>

              {/* Vertical line */}
              {!isLast && (
                <div className="absolute left-2.5 top-1 bottom-0 w-px bg-slate-200 dark:bg-slate-800" />
              )}

              {/* Content */}
              <div className="space-y-2">
                <div className="flex justify-between items-center">
                  <div>
                    <p className={`text-sm font-bold ${isRunning ? 'text-primary' : 'text-slate-700 dark:text-slate-200'}`}>
                      {step.stepOrder}. {step.stepName}
                    </p>
                    <p className={`text-[11px] font-bold uppercase ${stepTextStyle(step.status)}`}>
                      {step.status}
                    </p>
                  </div>
                </div>
                {step.log && (
                  <p className="text-xs text-slate-500 leading-relaxed">{step.log}</p>
                )}
              </div>
            </div>
          );
        })}
      </div>
    </div>
  );
}
