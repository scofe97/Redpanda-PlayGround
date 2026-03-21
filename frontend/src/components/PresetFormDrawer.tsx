import { useState, useEffect } from 'react';
import toast from 'react-hot-toast';
import { useToolList } from '../hooks/useTools';
import { usePreset, useCreatePreset, useUpdatePreset } from '../hooks/usePipelineDefinition';

const CATEGORY_OPTIONS = [
  { value: 'CI_CD_TOOL', label: 'CI/CD' },
  { value: 'VCS', label: 'VCS' },
  { value: 'LIBRARY', label: 'Library' },
  { value: 'CONTAINER_REGISTRY', label: 'Registry' },
  { value: 'STORAGE', label: 'Storage' },
  { value: 'CLUSTER_APPLICATION', label: 'Cluster App' },
];

interface EntryRow {
  category: string;
  toolId: number | '';
}

interface PresetFormDrawerProps {
  isOpen: boolean;
  onClose: () => void;
  editPresetId?: number;
}

export default function PresetFormDrawer({ isOpen, onClose, editPresetId }: PresetFormDrawerProps) {
  const isEdit = !!editPresetId;
  const { data: existing } = usePreset(editPresetId ?? 0);
  const { data: tools = [] } = useToolList();
  const createPreset = useCreatePreset();
  const updatePreset = useUpdatePreset();

  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const [entries, setEntries] = useState<EntryRow[]>([{ category: 'CI_CD_TOOL', toolId: '' }]);

  // Populate form when editing
  useEffect(() => {
    if (existing && isEdit) {
      setName(existing.name);
      setDescription(existing.description ?? '');
      if (existing.entries.length > 0) {
        setEntries(existing.entries.map((e) => ({ category: e.category, toolId: e.toolId })));
      } else {
        setEntries([{ category: 'CI_CD_TOOL', toolId: '' }]);
      }
    }
  }, [existing, isEdit]);

  // Reset form when drawer closes
  useEffect(() => {
    if (!isOpen) {
      setName('');
      setDescription('');
      setEntries([{ category: 'CI_CD_TOOL', toolId: '' }]);
    }
  }, [isOpen]);

  const usedCategories = entries.map((e) => e.category);

  const addEntry = () => {
    const available = CATEGORY_OPTIONS.find((opt) => !usedCategories.includes(opt.value));
    if (!available) return;
    setEntries((prev) => [...prev, { category: available.value, toolId: '' }]);
  };

  const removeEntry = (index: number) => {
    setEntries((prev) => prev.filter((_, i) => i !== index));
  };

  const updateEntryCategory = (index: number, category: string) => {
    setEntries((prev) =>
      prev.map((e, i) => (i === index ? { category, toolId: '' } : e)),
    );
  };

  const updateEntryTool = (index: number, toolId: number | '') => {
    setEntries((prev) => prev.map((e, i) => (i === index ? { ...e, toolId } : e)));
  };

  const handleSubmit = async (e: React.FormEvent<HTMLFormElement>) => {
    e.preventDefault();

    if (!name.trim()) {
      toast.error('이름을 입력해주세요');
      return;
    }
    if (entries.length === 0) {
      toast.error('도구 매핑을 1개 이상 추가해주세요');
      return;
    }
    const incomplete = entries.some((e) => e.toolId === '');
    if (incomplete) {
      toast.error('모든 매핑에 도구를 선택해주세요');
      return;
    }
    const categories = entries.map((e) => e.category);
    const hasDuplicates = categories.length !== new Set(categories).size;
    if (hasDuplicates) {
      toast.error('카테고리는 중복될 수 없습니다');
      return;
    }

    const data = {
      name: name.trim(),
      description: description.trim() || undefined,
      entries: entries.map((e) => ({ category: e.category, toolId: e.toolId as number })),
    };

    try {
      if (isEdit && editPresetId) {
        await updatePreset.mutateAsync({ id: editPresetId, data });
      } else {
        await createPreset.mutateAsync(data);
      }
      toast.success('저장되었습니다');
      onClose();
    } catch (err) {
      toast.error(err instanceof Error ? err.message : '저장에 실패했습니다');
    }
  };

  const isPending = createPreset.isPending || updatePreset.isPending;
  const allCategoriesUsed = usedCategories.length >= CATEGORY_OPTIONS.length;

  if (!isOpen) return null;

  return (
    <>
      {/* Overlay */}
      <div
        className="fixed inset-0 bg-slate-900/50 backdrop-blur-sm z-40 transition-opacity"
        onClick={onClose}
      />

      {/* Drawer */}
      <div className="fixed inset-y-0 right-0 w-full max-w-md bg-white dark:bg-slate-900 shadow-2xl z-50 flex flex-col border-l border-slate-200 dark:border-slate-800">
        {/* Header */}
        <div className="px-6 py-4 border-b border-slate-200 dark:border-slate-800 flex items-center justify-between">
          <div className="flex items-center gap-2">
            <span className="material-symbols-outlined text-primary">{isEdit ? 'edit' : 'add'}</span>
            <h3 className="text-lg font-bold">{isEdit ? '프리셋 수정' : '프리셋 추가'}</h3>
          </div>
          <button
            onClick={onClose}
            className="p-2 hover:bg-slate-100 dark:hover:bg-slate-800 rounded-full transition-colors"
          >
            <span className="material-symbols-outlined">close</span>
          </button>
        </div>

        {/* Form */}
        <form onSubmit={handleSubmit} className="flex-1 flex flex-col overflow-hidden">
          <div className="flex-1 overflow-y-auto p-6 space-y-6">
            {/* Name */}
            <div>
              <label className="block text-xs font-bold text-slate-500 uppercase mb-1.5">
                이름 <span className="text-red-400">*</span>
              </label>
              <input
                value={name}
                onChange={(e) => setName(e.target.value)}
                required
                maxLength={100}
                placeholder="예: 기본 CI/CD 프리셋"
                className="w-full px-3 py-2 border border-slate-300 dark:border-slate-700 dark:bg-slate-800 rounded-lg text-sm focus:ring-2 focus:ring-primary focus:border-primary outline-none transition-all"
              />
            </div>

            {/* Description */}
            <div>
              <label className="block text-xs font-bold text-slate-500 uppercase mb-1.5">설명</label>
              <textarea
                value={description}
                onChange={(e) => setDescription(e.target.value)}
                maxLength={500}
                rows={3}
                placeholder="프리셋에 대한 설명을 입력하세요 (선택)"
                className="w-full px-3 py-2 border border-slate-300 dark:border-slate-700 dark:bg-slate-800 rounded-lg text-sm focus:ring-2 focus:ring-primary focus:border-primary outline-none transition-all resize-none"
              />
            </div>

            {/* Tool Mappings */}
            <div className="pt-2 border-t border-slate-100 dark:border-slate-800">
              <div className="flex items-center justify-between mb-3">
                <h4 className="text-xs font-bold text-slate-500 uppercase">
                  도구 매핑 <span className="text-red-400">*</span>
                </h4>
                <button
                  type="button"
                  onClick={addEntry}
                  disabled={allCategoriesUsed}
                  className="flex items-center gap-1 text-xs font-bold text-primary hover:text-primary/80 disabled:opacity-40 disabled:cursor-not-allowed transition-colors"
                >
                  <span className="material-symbols-outlined text-base">add_circle</span>
                  매핑 추가
                </button>
              </div>

              {entries.length === 0 ? (
                <div className="text-center py-6 text-slate-400 text-sm border-2 border-dashed border-slate-200 dark:border-slate-700 rounded-lg">
                  <span className="material-symbols-outlined block text-2xl mb-1">category</span>
                  매핑을 추가해주세요
                </div>
              ) : (
                <div className="space-y-3">
                  {entries.map((entry, index) => {
                    const toolsForCategory = tools.filter(
                      (t) => t.category === entry.category && t.active,
                    );

                    return (
                      <div
                        key={index}
                        className="flex items-center gap-2 p-3 bg-slate-50 dark:bg-slate-800/50 rounded-lg border border-slate-200 dark:border-slate-700"
                      >
                        {/* Category dropdown */}
                        <select
                          value={entry.category}
                          onChange={(e) => updateEntryCategory(index, e.target.value)}
                          className="flex-1 px-2 py-1.5 border border-slate-300 dark:border-slate-700 dark:bg-slate-800 rounded-md text-xs focus:ring-2 focus:ring-primary focus:border-primary outline-none transition-all"
                        >
                          {CATEGORY_OPTIONS.map((opt) => {
                            const isUsedElsewhere =
                              usedCategories.includes(opt.value) && opt.value !== entry.category;
                            return (
                              <option key={opt.value} value={opt.value} disabled={isUsedElsewhere}>
                                {opt.label}
                                {isUsedElsewhere ? ' (사용중)' : ''}
                              </option>
                            );
                          })}
                        </select>

                        {/* Tool dropdown */}
                        <select
                          value={entry.toolId}
                          onChange={(e) =>
                            updateEntryTool(index, e.target.value ? Number(e.target.value) : '')
                          }
                          className="flex-1 px-2 py-1.5 border border-slate-300 dark:border-slate-700 dark:bg-slate-800 rounded-md text-xs focus:ring-2 focus:ring-primary focus:border-primary outline-none transition-all"
                        >
                          <option value="">도구 선택</option>
                          {toolsForCategory.length === 0 ? (
                            <option disabled>사용 가능한 도구 없음</option>
                          ) : (
                            toolsForCategory.map((t) => (
                              <option key={t.id} value={t.id}>
                                {t.name}
                              </option>
                            ))
                          )}
                        </select>

                        {/* Delete button */}
                        <button
                          type="button"
                          onClick={() => removeEntry(index)}
                          className="p-1 text-slate-400 hover:text-red-500 hover:bg-red-50 dark:hover:bg-red-950/30 rounded transition-colors flex-shrink-0"
                        >
                          <span className="material-symbols-outlined text-base">delete</span>
                        </button>
                      </div>
                    );
                  })}
                </div>
              )}

              {entries.length > 0 && (
                <p className="mt-2 text-xs text-slate-400">
                  카테고리당 1개의 도구만 매핑할 수 있습니다.
                </p>
              )}
            </div>
          </div>

          {/* Footer */}
          <div className="p-6 border-t border-slate-200 dark:border-slate-800 flex gap-3">
            <button
              type="button"
              onClick={onClose}
              className="flex-1 px-4 py-2.5 border border-slate-200 dark:border-slate-700 text-slate-600 dark:text-slate-400 font-bold rounded-lg text-sm hover:bg-slate-50 dark:hover:bg-slate-800 transition-colors"
            >
              취소
            </button>
            <button
              type="submit"
              disabled={isPending || !name.trim() || entries.length === 0}
              className="flex-1 px-4 py-2.5 bg-primary text-white font-bold rounded-lg text-sm hover:bg-primary/90 shadow-lg shadow-primary/20 transition-all disabled:opacity-50 disabled:cursor-not-allowed"
            >
              {isPending ? '저장 중...' : '저장'}
            </button>
          </div>
        </form>
      </div>
    </>
  );
}
