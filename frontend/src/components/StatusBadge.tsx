interface StatusBadgeProps {
  status: string;
}

const statusStyles: Record<string, string> = {
  draft: 'bg-slate-100 text-slate-600 dark:bg-slate-800 dark:text-slate-400 border border-slate-200 dark:border-slate-700',
  ready: 'bg-blue-100 text-blue-700 dark:bg-blue-900/30 dark:text-blue-400',
  deploying: 'bg-orange-100 text-orange-700 dark:bg-orange-900/30 dark:text-orange-400',
  deployed: 'bg-green-100 text-green-700 dark:bg-green-900/30 dark:text-green-400',
  failed: 'bg-red-100 text-red-700 dark:bg-red-900/30 dark:text-red-400',
  pending: 'bg-slate-100 text-slate-600 dark:bg-slate-800 dark:text-slate-400',
  running: 'bg-blue-100 text-blue-700 dark:bg-blue-900/30 dark:text-blue-400',
  waiting_webhook: 'bg-amber-100 text-amber-700 dark:bg-amber-900/30 dark:text-amber-400',
  success: 'bg-green-100 text-green-700 dark:bg-green-900/30 dark:text-green-400',
};

export default function StatusBadge({ status }: StatusBadgeProps) {
  const style = statusStyles[status.toLowerCase()] ?? 'bg-slate-100 text-slate-600';
  return (
    <span className={`inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-bold ${style}`}>
      {status}
    </span>
  );
}
