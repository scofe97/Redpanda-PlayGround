import { usePipelineExecutions } from '../hooks/usePipelineDefinition';
import ExecutionCard from './ExecutionCard';

interface PipelineExecutionPanelProps {
  pipelineId: number;
  onExecute: () => void;
  isPending: boolean;
  onSelectExecution?: (executionId: string | 'none') => void;
  selectedExecutionId?: string | null;
  // raw state from parent: null = auto-mode, 'none' = explicitly cleared, string = explicitly selected
  rawSelectedExecutionId?: string | 'none' | null;
}

export default function PipelineExecutionPanel({ pipelineId, onExecute, isPending, onSelectExecution, selectedExecutionId, rawSelectedExecutionId }: PipelineExecutionPanelProps) {
  const { data: executions, isLoading } = usePipelineExecutions(pipelineId);

  const sorted = executions
    ? [...executions].sort((a, b) => {
        const ta = a.startedAt ? new Date(a.startedAt).getTime() : 0;
        const tb = b.startedAt ? new Date(b.startedAt).getTime() : 0;
        return tb - ta;
      })
    : [];

  const latest = sorted[0];
  const history = sorted.slice(1, 5);
  const isRunning = latest?.status === 'RUNNING' || latest?.status === 'WAITING_WEBHOOK' || latest?.status === 'WAITING_EXECUTOR';

  return (
    <div className="bg-white dark:bg-slate-900 rounded-xl border border-slate-200 dark:border-slate-800 shadow-sm overflow-hidden">
      <div className="p-5 border-b border-slate-100 dark:border-slate-800 flex items-center justify-between">
        <h3 className="font-bold">실행</h3>
        {isRunning && (
          <span className="inline-flex items-center gap-1 text-[11px] font-medium text-blue-600">
            <span className="material-symbols-outlined text-[14px] animate-spin">sync</span>
            실행 중
          </span>
        )}
      </div>
      <div className="p-5 space-y-4">
        <p className="text-sm text-slate-500">
          파이프라인을 실행하면 정의된 Job들이 의존성 순서에 따라 DAG 방식으로 실행됩니다.
        </p>

        <button
          onClick={onExecute}
          disabled={isPending || isRunning}
          className="w-full px-5 py-3 bg-primary text-white text-sm font-bold rounded-lg hover:bg-primary/90 transition-all flex items-center justify-center gap-2 disabled:opacity-50 disabled:cursor-not-allowed shadow-lg shadow-primary/20"
        >
          <span className="material-symbols-outlined text-sm">play_arrow</span>
          {isPending ? '실행 요청 중...' : isRunning ? '실행 중...' : '파이프라인 실행'}
        </button>

        {/* Latest execution */}
        {isLoading ? (
          <p className="text-xs text-slate-400 text-center py-2">실행 이력 로딩 중...</p>
        ) : latest ? (
          <div className="space-y-2">
            <p className="text-[11px] font-bold text-slate-400 uppercase tracking-wider">최근 실행</p>
            <ExecutionCard
              execution={latest}
              defaultOpen
              isSelected={selectedExecutionId === latest.executionId}
              onSelect={() => {
                const isExplicitlySelected = rawSelectedExecutionId === latest.executionId;
                onSelectExecution?.(isExplicitlySelected ? 'none' : latest.executionId);
              }}
            />
          </div>
        ) : null}

        {/* History */}
        {history.length > 0 && (
          <div className="space-y-2">
            <p className="text-[11px] font-bold text-slate-400 uppercase tracking-wider">이전 실행 이력</p>
            <div className="space-y-2">
              {history.map((ex) => (
                <ExecutionCard
                  key={ex.executionId}
                  execution={ex}
                  isSelected={selectedExecutionId === ex.executionId}
                  onSelect={() => {
                    const isExplicitlySelected = rawSelectedExecutionId === ex.executionId;
                    onSelectExecution?.(isExplicitlySelected ? 'none' : ex.executionId);
                  }}
                />
              ))}
            </div>
          </div>
        )}
      </div>
    </div>
  );
}
