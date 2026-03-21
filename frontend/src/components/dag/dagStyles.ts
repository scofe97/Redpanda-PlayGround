export interface DagNodeStyle {
  borderClass: string;
  bgClass: string;
  icon: string;
  iconAnimation?: string;
  nodeAnimation?: string;
}

export interface DagEdgeStyle {
  stroke: string;
  animated: boolean;
  className?: string;
}

const JOB_TYPE_STYLES: Record<string, { borderClass: string; bgClass: string; icon: string }> = {
  BUILD:  { borderClass: 'border-blue-400', bgClass: 'bg-blue-50 dark:bg-blue-900/20', icon: 'build' },
  DEPLOY: { borderClass: 'border-green-400', bgClass: 'bg-green-50 dark:bg-green-900/20', icon: 'rocket_launch' },
};

const DEFAULT_JOB_STYLE = {
  borderClass: 'border-slate-300',
  bgClass: 'bg-slate-50 dark:bg-slate-800',
  icon: 'settings',
};

export function getNodeStyle(status?: string, jobType?: string): DagNodeStyle {
  if (!status) {
    const jt = JOB_TYPE_STYLES[jobType?.toUpperCase() ?? ''] ?? DEFAULT_JOB_STYLE;
    return { borderClass: jt.borderClass, bgClass: jt.bgClass, icon: jt.icon };
  }

  switch (status) {
    case 'PENDING':
      return { borderClass: 'border-slate-400', bgClass: 'bg-slate-50 dark:bg-slate-800', icon: 'hourglass_empty' };
    case 'RUNNING':
      return { borderClass: 'border-blue-500', bgClass: 'bg-blue-50 dark:bg-blue-900/30', icon: 'sync', iconAnimation: 'animate-spin', nodeAnimation: 'dag-node-running' };
    case 'SUCCESS':
      return { borderClass: 'border-emerald-500', bgClass: 'bg-emerald-50 dark:bg-emerald-900/30', icon: 'check_circle' };
    case 'FAILED':
      return { borderClass: 'border-red-500', bgClass: 'bg-red-50 dark:bg-red-900/30', icon: 'error' };
    case 'COMPENSATED':
      return { borderClass: 'border-orange-500', bgClass: 'bg-orange-50 dark:bg-orange-900/30', icon: 'undo', nodeAnimation: 'dag-node-compensated' };
    case 'SKIPPED':
      return { borderClass: 'border-slate-300 border-dashed', bgClass: 'bg-slate-50 dark:bg-slate-800 opacity-60', icon: 'skip_next' };
    case 'WAITING_WEBHOOK':
      return { borderClass: 'border-purple-500', bgClass: 'bg-purple-50 dark:bg-purple-900/30', icon: 'webhook', nodeAnimation: 'dag-node-running' };
    default:
      return { borderClass: 'border-slate-300', bgClass: 'bg-slate-50 dark:bg-slate-800', icon: 'help' };
  }
}

export function getEdgeStyle(fromStatus?: string, toStatus?: string): DagEdgeStyle {
  if (toStatus === 'COMPENSATED') {
    return { stroke: '#f97316', animated: false, className: 'dag-edge-compensating' };
  }
  if (toStatus === 'RUNNING' || toStatus === 'WAITING_WEBHOOK') {
    return { stroke: '#3b82f6', animated: true };
  }
  if (fromStatus === 'SUCCESS' && toStatus === 'SUCCESS') {
    return { stroke: '#10b981', animated: false };
  }
  if (toStatus === 'FAILED') {
    return { stroke: '#ef4444', animated: false };
  }
  if (fromStatus === 'SUCCESS') {
    return { stroke: '#10b981', animated: false };
  }
  return { stroke: '#94a3b8', animated: false };
}
