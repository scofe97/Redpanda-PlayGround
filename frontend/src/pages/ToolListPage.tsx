import { useState } from 'react';
import { useToolList, useDeleteTool, useTestTool } from '../hooks/useTools';
import ToolFormDrawer from '../components/ToolFormDrawer';

const CATEGORY_DISPLAY: Record<string, { label: string; icon: string }> = {
  CI_CD_TOOL: { label: 'CI/CD', icon: 'build' },
  VCS: { label: 'VCS', icon: 'source' },
  LIBRARY: { label: 'Library', icon: 'inventory_2' },
  CONTAINER_REGISTRY: { label: 'Registry', icon: 'deployed_code' },
  STORAGE: { label: 'Storage', icon: 'cloud_upload' },
  CLUSTER_APPLICATION: { label: 'Cluster App', icon: 'hub' },
};

export default function ToolListPage() {
  const { data: tools, isLoading, error } = useToolList();
  const deleteTool = useDeleteTool();
  const testTool = useTestTool();
  const [testResults, setTestResults] = useState<Record<number, boolean | null>>({});
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [editToolId, setEditToolId] = useState<number | undefined>();

  const handleTest = async (id: number) => {
    setTestResults((prev) => ({ ...prev, [id]: null }));
    try {
      const result = await testTool.mutateAsync(id);
      setTestResults((prev) => ({ ...prev, [id]: result.reachable }));
    } catch {
      setTestResults((prev) => ({ ...prev, [id]: false }));
    }
  };

  const handleDelete = async (id: number, name: string) => {
    if (!confirm(`Delete "${name}"?`)) return;
    await deleteTool.mutateAsync(id);
  };

  const openCreate = () => {
    setEditToolId(undefined);
    setDrawerOpen(true);
  };

  const openEdit = (id: number) => {
    setEditToolId(id);
    setDrawerOpen(true);
  };

  const closeDrawer = () => {
    setDrawerOpen(false);
    setEditToolId(undefined);
  };

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
          <h2 className="text-lg font-bold">Failed to load tools</h2>
          <p className="text-red-600 mt-2 text-sm">{error.message}</p>
        </div>
      </div>
    );
  }

  return (
    <>
      <div className="flex-1 flex flex-col min-w-0 overflow-hidden">
        {/* Header */}
        <header className="h-16 border-b border-slate-200 dark:border-slate-800 bg-white dark:bg-slate-900 px-8 flex items-center justify-between">
          <div className="flex items-center gap-2">
            <span className="material-symbols-outlined text-primary">settings_applications</span>
            <h2 className="text-lg font-bold">지원 도구 관리</h2>
          </div>
          <button
            onClick={openCreate}
            className="bg-primary hover:bg-primary/90 text-white px-4 py-2 rounded-lg text-sm font-bold flex items-center gap-2 transition-all"
          >
            <span className="material-symbols-outlined text-sm">add</span>
            도구 추가
          </button>
        </header>

        {/* Content */}
        <div className="flex-1 overflow-y-auto p-8">
          <div className="bg-white dark:bg-slate-900 rounded-xl border border-slate-200 dark:border-slate-800 overflow-hidden shadow-sm">
            <div className="overflow-x-auto">
              <table className="w-full text-left border-collapse">
                <thead>
                  <tr className="bg-slate-50 dark:bg-slate-800/50 border-b border-slate-200 dark:border-slate-800">
                    <th className="px-6 py-4 text-xs font-bold text-slate-500 uppercase tracking-wider">이름</th>
                    <th className="px-6 py-4 text-xs font-bold text-slate-500 uppercase tracking-wider">카테고리</th>
                    <th className="px-6 py-4 text-xs font-bold text-slate-500 uppercase tracking-wider">구현체</th>
                    <th className="px-6 py-4 text-xs font-bold text-slate-500 uppercase tracking-wider">URL</th>
                    <th className="px-6 py-4 text-xs font-bold text-slate-500 uppercase tracking-wider text-center">활성</th>
                    <th className="px-6 py-4 text-xs font-bold text-slate-500 uppercase tracking-wider">상태</th>
                    <th className="px-6 py-4 text-xs font-bold text-slate-500 uppercase tracking-wider text-right">액션</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-slate-100 dark:divide-slate-800">
                  {tools?.map((tool) => (
                    <tr key={tool.id} className="hover:bg-slate-50 dark:hover:bg-slate-800/30 transition-colors">
                      <td className="px-6 py-4 font-medium">{tool.name}</td>
                      <td className="px-6 py-4">
                        {(() => {
                          const cat = CATEGORY_DISPLAY[tool.category];
                          return cat ? (
                            <span className="inline-flex items-center gap-1 px-2.5 py-0.5 rounded-full text-xs font-bold bg-primary/10 text-primary">
                              <span className="material-symbols-outlined text-xs">{cat.icon}</span>
                              {cat.label}
                            </span>
                          ) : (
                            <span className="text-xs text-slate-400">{tool.category}</span>
                          );
                        })()}
                      </td>
                      <td className="px-6 py-4">
                        <span className="inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-bold bg-slate-100 text-slate-700 dark:bg-slate-800 dark:text-slate-300">
                          {tool.implementation}
                        </span>
                      </td>
                      <td className="px-6 py-4 text-sm text-slate-500 dark:text-slate-400">{tool.url}</td>
                      <td className="px-6 py-4 text-center">
                        <span
                          className={`material-symbols-outlined font-bold ${
                            tool.active ? 'text-emerald-500' : 'text-slate-300 dark:text-slate-700'
                          }`}
                        >
                          {tool.active ? 'check_circle' : 'cancel'}
                        </span>
                      </td>
                      <td className="px-6 py-4">
                        {testResults[tool.id] === undefined ? (
                          <span className="text-sm text-slate-400">—</span>
                        ) : testResults[tool.id] === null ? (
                          <div className="flex items-center gap-2">
                            <span className="size-2 rounded-full bg-amber-500 animate-pulse" />
                            <span className="text-sm font-medium text-amber-600 dark:text-amber-400">Testing...</span>
                          </div>
                        ) : testResults[tool.id] ? (
                          <div className="flex items-center gap-2">
                            <span className="size-2 rounded-full bg-emerald-500" />
                            <span className="text-sm font-medium text-emerald-600 dark:text-emerald-400">Reachable</span>
                          </div>
                        ) : (
                          <div className="flex items-center gap-2">
                            <span className="size-2 rounded-full bg-red-500" />
                            <span className="text-sm font-medium text-red-600 dark:text-red-400">Unreachable</span>
                          </div>
                        )}
                      </td>
                      <td className="px-6 py-4 text-right space-x-2">
                        <button
                          onClick={() => handleTest(tool.id)}
                          className="text-xs font-bold text-slate-600 dark:text-slate-400 hover:text-primary underline"
                        >
                          테스트
                        </button>
                        <button
                          onClick={() => openEdit(tool.id)}
                          className="text-xs font-bold text-slate-600 dark:text-slate-400 hover:text-primary underline"
                        >
                          수정
                        </button>
                        <button
                          onClick={() => handleDelete(tool.id, tool.name)}
                          className="text-xs font-bold text-red-500 hover:text-red-600 underline"
                        >
                          삭제
                        </button>
                      </td>
                    </tr>
                  ))}
                  {(!tools || tools.length === 0) && (
                    <tr>
                      <td colSpan={7} className="px-6 py-12 text-center text-slate-400 text-sm">
                        등록된 도구가 없습니다.
                      </td>
                    </tr>
                  )}
                </tbody>
              </table>
            </div>
          </div>
        </div>
      </div>

      <ToolFormDrawer isOpen={drawerOpen} onClose={closeDrawer} editToolId={editToolId} />
    </>
  );
}
