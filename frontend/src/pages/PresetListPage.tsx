import { useState } from 'react';
import toast from 'react-hot-toast';
import { usePresetList, useDeletePreset } from '../hooks/usePipelineDefinition';
import PresetFormDrawer from '../components/PresetFormDrawer';

const CATEGORY_DISPLAY: Record<string, { label: string; icon: string }> = {
  CI_CD_TOOL: { label: 'CI/CD', icon: 'build' },
  VCS: { label: 'VCS', icon: 'source' },
  LIBRARY: { label: 'Library', icon: 'inventory_2' },
  CONTAINER_REGISTRY: { label: 'Registry', icon: 'deployed_code' },
  STORAGE: { label: 'Storage', icon: 'cloud_upload' },
  CLUSTER_APPLICATION: { label: 'Cluster App', icon: 'hub' },
};

export default function PresetListPage() {
  const { data: presets, isLoading, error } = usePresetList();
  const deletePreset = useDeletePreset();
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [editPresetId, setEditPresetId] = useState<number | undefined>();

  const handleDelete = async (id: number, name: string) => {
    if (!confirm(`"${name}" 프리셋을 삭제하시겠습니까?`)) return;
    try {
      await deletePreset.mutateAsync(id);
      toast.success('삭제되었습니다');
    } catch {
      toast.error('삭제에 실패했습니다');
    }
  };

  const openCreate = () => {
    setEditPresetId(undefined);
    setDrawerOpen(true);
  };

  const openEdit = (id: number) => {
    setEditPresetId(id);
    setDrawerOpen(true);
  };

  const closeDrawer = () => {
    setDrawerOpen(false);
    setEditPresetId(undefined);
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
          <h2 className="text-lg font-bold">Failed to load presets</h2>
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
            <span className="material-symbols-outlined text-primary">tune</span>
            <h2 className="text-lg font-bold">프리셋 관리</h2>
          </div>
          <button
            onClick={openCreate}
            className="bg-primary hover:bg-primary/90 text-white px-4 py-2 rounded-lg text-sm font-bold flex items-center gap-2 transition-all"
          >
            <span className="material-symbols-outlined text-sm">add</span>
            프리셋 추가
          </button>
        </header>

        {/* Content */}
        <div className="flex-1 overflow-y-auto p-8 space-y-6">
          {(!presets || presets.length === 0) ? (
            <div className="flex flex-col items-center justify-center py-24 text-slate-400">
              <span className="material-symbols-outlined text-4xl mb-3">tune</span>
              <p className="text-sm font-medium">등록된 프리셋이 없습니다</p>
              <p className="text-xs mt-1">프리셋 추가 버튼을 눌러 시작하세요</p>
            </div>
          ) : (
            presets.map((preset) => (
              <div
                key={preset.id}
                className="bg-white dark:bg-slate-900 rounded-xl border border-slate-200 dark:border-slate-800 shadow-sm overflow-hidden"
              >
                {/* Card Header */}
                <div className="px-6 py-4 border-b border-slate-100 dark:border-slate-800 flex items-start justify-between gap-4">
                  <div className="min-w-0">
                    <p className="font-bold text-slate-900 dark:text-slate-100 truncate">{preset.name}</p>
                    {preset.description && (
                      <p className="text-xs text-slate-500 mt-0.5 truncate">{preset.description}</p>
                    )}
                  </div>
                  <div className="flex items-center gap-3 flex-shrink-0">
                    <button
                      onClick={() => openEdit(preset.id)}
                      className="text-xs font-bold text-slate-600 dark:text-slate-400 hover:text-primary underline"
                    >
                      수정
                    </button>
                    <button
                      onClick={() => handleDelete(preset.id, preset.name)}
                      className="text-xs font-bold text-red-500 hover:text-red-600 underline"
                    >
                      삭제
                    </button>
                  </div>
                </div>

                {/* Card Body */}
                <div className="px-6 py-4">
                  {preset.entries.length === 0 ? (
                    <p className="text-xs text-slate-400">도구 항목이 없습니다</p>
                  ) : (
                    <div className="flex flex-wrap gap-2">
                      {preset.entries.map((entry) => {
                        const cat = CATEGORY_DISPLAY[entry.category];
                        return (
                          <div
                            key={entry.id}
                            className="flex items-center gap-2 px-3 py-1.5 rounded-lg bg-slate-50 dark:bg-slate-800 border border-slate-200 dark:border-slate-700"
                          >
                            {cat && (
                              <span className="material-symbols-outlined text-sm text-primary">
                                {cat.icon}
                              </span>
                            )}
                            <span className="text-xs font-bold text-slate-700 dark:text-slate-300">
                              {entry.toolName}
                            </span>
                            <span className="text-xs text-slate-400">—</span>
                            <span className="text-xs text-slate-500 font-mono">{entry.toolUrl}</span>
                          </div>
                        );
                      })}
                    </div>
                  )}
                </div>
              </div>
            ))
          )}
        </div>
      </div>

      <PresetFormDrawer isOpen={drawerOpen} onClose={closeDrawer} editPresetId={editPresetId} />
    </>
  );
}
