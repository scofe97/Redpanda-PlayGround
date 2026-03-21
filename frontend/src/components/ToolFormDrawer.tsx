import { useState, useEffect } from 'react';
import toast from 'react-hot-toast';
import { useCreateTool, useUpdateTool, useTool } from '../hooks/useTools';

const TOOL_CATEGORIES = [
  { key: 'CI_CD_TOOL', label: 'CI/CD', icon: 'build', implementations: ['JENKINS', 'GITHUB_ACTIONS'] },
  { key: 'VCS', label: 'VCS', icon: 'source', implementations: ['GITLAB', 'GITHUB', 'BITBUCKET'] },
  { key: 'LIBRARY', label: 'Library', icon: 'inventory_2', implementations: ['NEXUS', 'ARTIFACTORY'] },
  { key: 'CONTAINER_REGISTRY', label: 'Registry', icon: 'deployed_code', implementations: ['HARBOR', 'DOCKER_REGISTRY', 'ECR'] },
  { key: 'STORAGE', label: 'Storage', icon: 'cloud_upload', implementations: ['MINIO', 'S3'] },
  { key: 'CLUSTER_APPLICATION', label: 'Cluster App', icon: 'hub', implementations: ['ARGOCD', 'FLUX'] },
] as const;

interface ToolFormDrawerProps {
  isOpen: boolean;
  onClose: () => void;
  editToolId?: number;
}

export default function ToolFormDrawer({ isOpen, onClose, editToolId }: ToolFormDrawerProps) {
  const isEdit = !!editToolId;
  const { data: existing } = useTool(editToolId ?? 0);
  const createTool = useCreateTool();
  const updateTool = useUpdateTool();

  const [category, setCategory] = useState('CI_CD_TOOL');
  const [implementation, setImplementation] = useState('JENKINS');
  const [name, setName] = useState('');
  const [url, setUrl] = useState('');
  const [username, setUsername] = useState('');
  const [credential, setCredential] = useState('');
  const [active, setActive] = useState(true);

  useEffect(() => {
    if (existing && isEdit) {
      setCategory(existing.category);
      setImplementation(existing.implementation);
      setName(existing.name);
      setUrl(existing.url);
      setUsername(existing.username || '');
      setCredential('');
      setActive(existing.active);
    }
  }, [existing, isEdit]);

  useEffect(() => {
    if (!isOpen) {
      setCategory('CI_CD_TOOL');
      setImplementation('JENKINS');
      setName('');
      setUrl('');
      setUsername('');
      setCredential('');
      setActive(true);
    }
  }, [isOpen]);

  const selectedCategory = TOOL_CATEGORIES.find((c) => c.key === category);
  const availableImplementations = selectedCategory ? selectedCategory.implementations : [];

  const handleCategoryChange = (catKey: string) => {
    setCategory(catKey);
    const cat = TOOL_CATEGORIES.find((c) => c.key === catKey);
    if (cat) {
      setImplementation(cat.implementations[0]);
    }
  };

  const handleSubmit = async (e: React.FormEvent<HTMLFormElement>) => {
    e.preventDefault();
    const data = {
      category,
      implementation,
      name,
      url,
      username: username || undefined,
      credential: credential || undefined,
      active,
    };
    try {
      if (isEdit && editToolId) {
        await updateTool.mutateAsync({ id: editToolId, data });
      } else {
        await createTool.mutateAsync(data);
      }
      onClose();
      toast.success('저장되었습니다');
    } catch (err) {
      toast.error(err instanceof Error ? err.message : 'Failed to save tool');
    }
  };

  const isPending = createTool.isPending || updateTool.isPending;

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
            <h3 className="text-lg font-bold">{isEdit ? '도구 수정' : '도구 추가'}</h3>
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
            <div className="space-y-4">
              <div>
                <label className="block text-xs font-bold text-slate-500 uppercase mb-1.5">도구 이름</label>
                <input
                  value={name}
                  onChange={(e) => setName(e.target.value)}
                  required
                  maxLength={100}
                  placeholder="예: Jenkins CI"
                  className="w-full px-3 py-2 border border-slate-300 dark:border-slate-700 dark:bg-slate-800 rounded-lg text-sm focus:ring-2 focus:ring-primary focus:border-primary outline-none transition-all"
                />
              </div>

              <div>
                <label className="block text-xs font-bold text-slate-500 uppercase mb-1.5">카테고리</label>
                <div className="grid grid-cols-3 gap-2">
                  {TOOL_CATEGORIES.map((cat) => (
                    <button
                      key={cat.key}
                      type="button"
                      onClick={() => handleCategoryChange(cat.key)}
                      className={`flex items-center justify-center gap-1.5 px-3 py-2 rounded-lg text-xs font-bold border transition-all ${
                        category === cat.key
                          ? 'bg-primary/10 border-primary text-primary'
                          : 'border-slate-200 dark:border-slate-700 text-slate-500 hover:border-slate-400'
                      }`}
                    >
                      <span className="material-symbols-outlined text-sm">{cat.icon}</span>
                      {cat.label}
                    </button>
                  ))}
                </div>
              </div>

              <div>
                <label className="block text-xs font-bold text-slate-500 uppercase mb-1.5">구현체</label>
                <select
                  value={implementation}
                  onChange={(e) => setImplementation(e.target.value)}
                  className="w-full px-3 py-2 border border-slate-300 dark:border-slate-700 dark:bg-slate-800 rounded-lg text-sm focus:ring-2 focus:ring-primary focus:border-primary outline-none transition-all"
                >
                  {availableImplementations.map((impl) => (
                    <option key={impl} value={impl}>
                      {impl}
                    </option>
                  ))}
                </select>
              </div>

              <div>
                <label className="block text-xs font-bold text-slate-500 uppercase mb-1.5">연결 URL</label>
                <input
                  value={url}
                  onChange={(e) => setUrl(e.target.value)}
                  required
                  maxLength={500}
                  placeholder="https://..."
                  className="w-full px-3 py-2 border border-slate-300 dark:border-slate-700 dark:bg-slate-800 rounded-lg text-sm focus:ring-2 focus:ring-primary focus:border-primary outline-none transition-all"
                />
              </div>

              <div className="pt-4 border-t border-slate-100 dark:border-slate-800">
                <h4 className="text-xs font-bold text-slate-400 uppercase mb-4">인증 정보 (Optional)</h4>
                <div className="space-y-4">
                  <div>
                    <label className="block text-xs font-bold text-slate-500 uppercase mb-1.5">사용자 ID</label>
                    <input
                      value={username}
                      onChange={(e) => setUsername(e.target.value)}
                      maxLength={100}
                      className="w-full px-3 py-2 border border-slate-300 dark:border-slate-700 dark:bg-slate-800 rounded-lg text-sm focus:ring-2 focus:ring-primary focus:border-primary outline-none transition-all"
                    />
                  </div>
                  <div>
                    <label className="block text-xs font-bold text-slate-500 uppercase mb-1.5">
                      비밀번호 / 토큰 {isEdit && '(빈칸이면 기존 유지)'}
                    </label>
                    <input
                      type="password"
                      value={credential}
                      onChange={(e) => setCredential(e.target.value)}
                      maxLength={500}
                      className="w-full px-3 py-2 border border-slate-300 dark:border-slate-700 dark:bg-slate-800 rounded-lg text-sm focus:ring-2 focus:ring-primary focus:border-primary outline-none transition-all"
                    />
                  </div>
                </div>
              </div>

              <div className="flex items-center gap-2 pt-2">
                <input
                  type="checkbox"
                  id="drawer-active"
                  checked={active}
                  onChange={(e) => setActive(e.target.checked)}
                  className="rounded text-primary focus:ring-primary"
                />
                <label htmlFor="drawer-active" className="text-sm font-medium cursor-pointer">
                  활성화
                </label>
              </div>
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
              disabled={isPending || !name || !url}
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
