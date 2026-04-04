import { useState } from 'react';
import toast from 'react-hot-toast';
import { useProjectList, useCreateProject, useUpdateProject, useDeleteProject } from '../hooks/useProject';
import { Project } from '../api/projectApi';

interface FormState {
  name: string;
  description: string;
}

const EMPTY_FORM: FormState = { name: '', description: '' };

export default function ProjectListPage() {
  const { data: projects, isLoading, error } = useProjectList();
  const createProject = useCreateProject();
  const updateProject = useUpdateProject();
  const deleteProject = useDeleteProject();

  const [showForm, setShowForm] = useState(false);
  const [editProject, setEditProject] = useState<Project | null>(null);
  const [form, setForm] = useState<FormState>(EMPTY_FORM);

  const openCreate = () => {
    setEditProject(null);
    setForm(EMPTY_FORM);
    setShowForm(true);
  };

  const openEdit = (project: Project) => {
    setEditProject(project);
    setForm({ name: project.name, description: project.description ?? '' });
    setShowForm(true);
  };

  const closeForm = () => {
    setShowForm(false);
    setEditProject(null);
    setForm(EMPTY_FORM);
  };

  const handleSubmit = async (e: React.FormEvent<HTMLFormElement>) => {
    e.preventDefault();
    if (!form.name.trim()) return;
    const data = { name: form.name.trim(), description: form.description.trim() || undefined };
    try {
      if (editProject) {
        await updateProject.mutateAsync({ id: editProject.id, data });
      } else {
        await createProject.mutateAsync(data);
      }
      toast.success('저장되었습니다');
      closeForm();
    } catch (err) {
      toast.error(err instanceof Error ? err.message : '저장에 실패했습니다');
    }
  };

  const handleDelete = async (id: number, name: string) => {
    if (!confirm(`"${name}" 프로젝트를 삭제하시겠습니까?`)) return;
    try {
      await deleteProject.mutateAsync(id);
      toast.success('삭제되었습니다');
    } catch {
      toast.error('삭제에 실패했습니다');
    }
  };

  const isPending = createProject.isPending || updateProject.isPending;

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
          <h2 className="text-lg font-bold">Failed to load projects</h2>
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
            <span className="material-symbols-outlined text-primary">folder</span>
            <h2 className="text-lg font-bold">프로젝트 관리</h2>
          </div>
          <button
            onClick={openCreate}
            className="bg-primary hover:bg-primary/90 text-white px-4 py-2 rounded-lg text-sm font-bold flex items-center gap-2 transition-all"
          >
            <span className="material-symbols-outlined text-sm">add</span>
            새 프로젝트
          </button>
        </header>

        {/* Content */}
        <div className="flex-1 overflow-y-auto p-8">
          {(!projects || projects.length === 0) ? (
            <div className="flex flex-col items-center justify-center py-24 text-slate-400">
              <span className="material-symbols-outlined text-4xl mb-3">folder_open</span>
              <p className="text-sm font-medium">등록된 프로젝트가 없습니다</p>
              <p className="text-xs mt-1">새 프로젝트 버튼을 눌러 시작하세요</p>
            </div>
          ) : (
            <div className="bg-white dark:bg-slate-900 rounded-xl border border-slate-200 dark:border-slate-800 shadow-sm overflow-hidden">
              <table className="w-full text-sm">
                <thead>
                  <tr className="border-b border-slate-100 dark:border-slate-800 bg-slate-50 dark:bg-slate-800/50">
                    <th className="px-6 py-3 text-left text-xs font-bold text-slate-500 uppercase tracking-wider">ID</th>
                    <th className="px-6 py-3 text-left text-xs font-bold text-slate-500 uppercase tracking-wider">이름</th>
                    <th className="px-6 py-3 text-left text-xs font-bold text-slate-500 uppercase tracking-wider">설명</th>
                    <th className="px-6 py-3 text-left text-xs font-bold text-slate-500 uppercase tracking-wider">생성일</th>
                    <th className="px-6 py-3 text-right text-xs font-bold text-slate-500 uppercase tracking-wider">작업</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-slate-100 dark:divide-slate-800">
                  {projects.map((project) => (
                    <tr key={project.id} className="hover:bg-slate-50 dark:hover:bg-slate-800/30 transition-colors">
                      <td className="px-6 py-4 text-slate-400 font-mono text-xs">{project.id}</td>
                      <td className="px-6 py-4 font-semibold text-slate-900 dark:text-slate-100">{project.name}</td>
                      <td className="px-6 py-4 text-slate-500 text-xs max-w-xs truncate">{project.description ?? '—'}</td>
                      <td className="px-6 py-4 text-slate-400 text-xs">
                        {new Date(project.createdAt).toLocaleDateString('ko-KR')}
                      </td>
                      <td className="px-6 py-4 text-right">
                        <div className="flex items-center justify-end gap-3">
                          <button
                            onClick={() => openEdit(project)}
                            className="text-xs font-bold text-slate-600 dark:text-slate-400 hover:text-primary underline"
                          >
                            수정
                          </button>
                          <button
                            onClick={() => handleDelete(project.id, project.name)}
                            className="text-xs font-bold text-red-500 hover:text-red-600 underline"
                          >
                            삭제
                          </button>
                        </div>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </div>
      </div>

      {/* Form Dialog */}
      {showForm && (
        <>
          <div
            className="fixed inset-0 bg-slate-900/50 backdrop-blur-sm z-40 transition-opacity"
            onClick={closeForm}
          />
          <div className="fixed inset-0 flex items-center justify-center z-50 p-4">
            <div className="bg-white dark:bg-slate-900 rounded-xl shadow-2xl border border-slate-200 dark:border-slate-800 w-full max-w-md">
              <div className="px-6 py-4 border-b border-slate-200 dark:border-slate-800 flex items-center justify-between">
                <div className="flex items-center gap-2">
                  <span className="material-symbols-outlined text-primary">{editProject ? 'edit' : 'add'}</span>
                  <h3 className="text-lg font-bold">{editProject ? '프로젝트 수정' : '새 프로젝트'}</h3>
                </div>
                <button
                  onClick={closeForm}
                  className="p-2 hover:bg-slate-100 dark:hover:bg-slate-800 rounded-full transition-colors"
                >
                  <span className="material-symbols-outlined">close</span>
                </button>
              </div>

              <form onSubmit={handleSubmit} className="p-6 space-y-5">
                <div>
                  <label className="block text-xs font-bold text-slate-500 uppercase mb-1.5">
                    이름 <span className="text-red-400">*</span>
                  </label>
                  <input
                    value={form.name}
                    onChange={(e) => setForm((f) => ({ ...f, name: e.target.value }))}
                    required
                    maxLength={100}
                    autoFocus
                    placeholder="예: my-service"
                    className="w-full px-3 py-2 border border-slate-300 dark:border-slate-700 dark:bg-slate-800 rounded-lg text-sm focus:ring-2 focus:ring-primary focus:border-primary outline-none transition-all"
                  />
                </div>

                <div>
                  <label className="block text-xs font-bold text-slate-500 uppercase mb-1.5">설명</label>
                  <textarea
                    value={form.description}
                    onChange={(e) => setForm((f) => ({ ...f, description: e.target.value }))}
                    maxLength={500}
                    rows={3}
                    placeholder="프로젝트 설명을 입력하세요 (선택)"
                    className="w-full px-3 py-2 border border-slate-300 dark:border-slate-700 dark:bg-slate-800 rounded-lg text-sm focus:ring-2 focus:ring-primary focus:border-primary outline-none transition-all resize-none"
                  />
                </div>

                <div className="flex gap-3 pt-2">
                  <button
                    type="button"
                    onClick={closeForm}
                    className="flex-1 px-4 py-2.5 border border-slate-200 dark:border-slate-700 text-slate-600 dark:text-slate-400 font-bold rounded-lg text-sm hover:bg-slate-50 dark:hover:bg-slate-800 transition-colors"
                  >
                    취소
                  </button>
                  <button
                    type="submit"
                    disabled={isPending || !form.name.trim()}
                    className="flex-1 px-4 py-2.5 bg-primary text-white font-bold rounded-lg text-sm hover:bg-primary/90 shadow-lg shadow-primary/20 transition-all disabled:opacity-50 disabled:cursor-not-allowed"
                  >
                    {isPending ? '저장 중...' : '저장'}
                  </button>
                </div>
              </form>
            </div>
          </div>
        </>
      )}
    </>
  );
}
