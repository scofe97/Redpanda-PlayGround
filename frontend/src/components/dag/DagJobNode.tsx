import { Handle, Position, type NodeProps, type Node } from '@xyflow/react';
import type { PipelineJobLocal, JobExecutionResponse } from '../../api/pipelineDefinitionApi';
import { getNodeStyle } from './dagStyles';

export interface DagJobNodeData {
  job: PipelineJobLocal;
  jobExecution?: JobExecutionResponse;
  [key: string]: unknown;
}

function formatDuration(start?: string, end?: string): string | null {
  if (!start) return null;
  const s = new Date(start).getTime();
  if (isNaN(s)) return null;
  const e = end ? new Date(end).getTime() : Date.now();
  if (isNaN(e)) return null;
  const sec = Math.round((e - s) / 1000);
  if (sec < 60) return `${sec}s`;
  return `${Math.floor(sec / 60)}m ${sec % 60}s`;
}

export default function DagJobNode({ data }: NodeProps<Node<DagJobNodeData>>) {
  const { job, jobExecution } = data;
  const status = jobExecution?.status;
  const style = getNodeStyle(status, job.jobType);

  const duration = formatDuration(jobExecution?.startedAt, jobExecution?.completedAt);

  return (
    <>
      <Handle type="target" position={Position.Left} className="!bg-slate-400 !w-2 !h-2 !border-0" />
      <div
        className={`
          px-3 py-2 rounded-lg border-2 min-w-[180px]
          flex items-center gap-2
          ${style.borderClass} ${style.bgClass}
          ${style.nodeAnimation ?? ''}
        `}
      >
        <span
          className={`material-symbols-outlined text-lg text-slate-600 dark:text-slate-300 ${style.iconAnimation ?? ''}`}
        >
          {style.icon}
        </span>
        <div className="min-w-0 flex-1">
          <p className="text-xs font-bold text-slate-700 dark:text-slate-200 truncate">{job.jobName}</p>
          <div className="flex items-center gap-1.5 flex-wrap">
            <span className="text-[10px] text-slate-500 uppercase">{job.jobType}</span>
            {job.purposeName && (
              <span className="text-[9px] px-1 py-0.5 rounded bg-indigo-100 dark:bg-indigo-900 text-indigo-600 dark:text-indigo-300 font-medium">
                {job.purposeName}
              </span>
            )}
            {jobExecution?.retryCount !== undefined && jobExecution.retryCount > 0 && (
              <span className="text-[10px] text-amber-500 font-medium">retry:{jobExecution.retryCount}</span>
            )}
            {duration && (
              <span className="text-[10px] text-slate-400">{duration}</span>
            )}
          </div>
        </div>
      </div>
      <Handle type="source" position={Position.Right} className="!bg-slate-400 !w-2 !h-2 !border-0" />
    </>
  );
}
