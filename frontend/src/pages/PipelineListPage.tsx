import { Link } from 'react-router-dom';
import toast from 'react-hot-toast';
import { usePipelineDefinitionList, useDeletePipelineDefinition } from '../hooks/usePipelineDefinition';
import StatusBadge from '../components/StatusBadge';

export default function PipelineListPage() {
  const { data: pipelines, isLoading, error } = usePipelineDefinitionList();
  const deletePipeline = useDeletePipelineDefinition();

  if (isLoading) {
    return (
      <div className="flex-1 flex items-center justify-center">
        <p className="text-slate-500">Loading...</p>
      </div>
    );
  }

  if (error) {
    return (
      <div className="flex-1 flex items-center justify-center">
        <div className="text-center">
          <h2 className="text-lg font-bold">Failed to load pipelines</h2>
          <p className="text-red-600 mt-2 text-sm">{error.message}</p>
        </div>
      </div>
    );
  }

  const handleDelete = async (id: number, pName: string) => {
    if (!window.confirm(`"${pName}" 파이프라인을 삭제하시겠습니까?`)) return;
    try {
      await deletePipeline.mutateAsync(id);
      toast.success('삭제되었습니다');
    } catch (err) {
      toast.error(err instanceof Error ? err.message : 'Failed to delete pipeline');
    }
  };

  return (
    <div className="flex-1 overflow-y-auto p-8 space-y-6">
      {/* Title & Action */}
      <div className="flex items-end justify-between">
        <h2 className="text-3xl font-bold tracking-tight">파이프라인 관리</h2>
        <Link
          to="/pipelines/new"
          className="flex items-center gap-2 px-5 py-2.5 bg-primary hover:bg-primary/90 text-white rounded-lg font-bold text-sm transition-all shadow-sm"
        >
          <span className="material-symbols-outlined text-[20px]">add</span>
          새 파이프라인
        </Link>
      </div>

      {/* Table Card */}
      <div className="bg-white dark:bg-slate-900 rounded-xl border border-slate-200 dark:border-slate-800 shadow-sm overflow-hidden">
        <table className="w-full text-left border-collapse">
          <thead>
            <tr className="bg-slate-50/50 dark:bg-slate-800/50 text-slate-500 text-xs font-semibold uppercase tracking-wider">
              <th className="px-6 py-4 border-b border-slate-200 dark:border-slate-800">ID</th>
              <th className="px-6 py-4 border-b border-slate-200 dark:border-slate-800">이름</th>
              <th className="px-6 py-4 border-b border-slate-200 dark:border-slate-800">상태</th>
              <th className="px-6 py-4 border-b border-slate-200 dark:border-slate-800">생성일</th>
              <th className="px-6 py-4 border-b border-slate-200 dark:border-slate-800">액션</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-slate-100 dark:divide-slate-800">
            {pipelines?.map((p) => (
              <tr key={p.id} className="hover:bg-slate-50 dark:hover:bg-slate-800/50 transition-colors">
                <td className="px-6 py-4 text-sm font-medium text-slate-500">#{p.id}</td>
                <td className="px-6 py-4">
                  <Link
                    to={`/pipelines/${p.id}`}
                    className="text-sm font-semibold text-primary hover:underline decoration-2 underline-offset-4"
                  >
                    {p.name}
                  </Link>
                </td>
                <td className="px-6 py-4">
                  <StatusBadge status={p.status} />
                </td>
                <td className="px-6 py-4 text-sm text-slate-500">
                  {new Date(p.createdAt).toLocaleDateString()}
                </td>
                <td className="px-6 py-4">
                  <button
                    onClick={() => handleDelete(p.id, p.name)}
                    className="text-red-500 hover:text-red-700 transition-colors"
                    title="삭제"
                  >
                    <span className="material-symbols-outlined text-[20px]">delete</span>
                  </button>
                </td>
              </tr>
            ))}
            {(!pipelines || pipelines.length === 0) && (
              <tr>
                <td colSpan={5} className="px-6 py-12 text-center text-slate-400 text-sm">
                  등록된 파이프라인이 없습니다.
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>
    </div>
  );
}
