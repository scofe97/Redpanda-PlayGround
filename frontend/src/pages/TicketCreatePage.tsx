import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import toast from 'react-hot-toast';
import { useCreateTicket } from '../hooks/useTickets';
import SourceSelector from '../components/SourceSelector';
import type { TicketSource } from '../api/ticketApi';

export default function TicketCreatePage() {
  const navigate = useNavigate();
  const createTicket = useCreateTicket();
  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const [sources, setSources] = useState<TicketSource[]>([]);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    try {
      const result = await createTicket.mutateAsync({ name, description, sources });
      navigate(`/tickets/${result.id}`);
    } catch (err) {
      toast.error(err instanceof Error ? err.message : 'Failed to create ticket');
    }
  };

  return (
    <div className="flex-1 overflow-y-auto">
      <div className="max-w-4xl mx-auto py-10 px-8">
        <div className="mb-8 flex items-center gap-3">
          <button
            type="button"
            onClick={() => navigate('/tickets')}
            className="p-2 rounded-lg hover:bg-slate-100 dark:hover:bg-slate-800 transition-colors text-slate-500"
            title="목록으로"
          >
            <span className="material-symbols-outlined text-[20px]">arrow_back</span>
          </button>
          <div>
            <h2 className="text-3xl font-black tracking-tight">새 티켓 생성</h2>
            <p className="text-slate-500 mt-1">새로운 리소스 배포 또는 설정을 위한 티켓을 작성합니다.</p>
          </div>
        </div>

        <form onSubmit={handleSubmit} className="space-y-8">
          {/* Basic Info */}
          <section className="bg-white dark:bg-slate-900 rounded-xl border border-slate-200 dark:border-slate-800 shadow-sm overflow-hidden">
            <div className="p-5 border-b border-slate-100 dark:border-slate-800">
              <h3 className="font-bold">기본 정보</h3>
            </div>
            <div className="p-5 grid gap-5">
              <label className="block">
                <span className="block text-xs font-bold text-slate-500 uppercase tracking-wider mb-1.5">
                  이름 <span className="text-red-500">*</span>
                </span>
                <input
                  value={name}
                  onChange={(e) => setName(e.target.value)}
                  required
                  maxLength={200}
                  autoFocus
                  className="w-full rounded-lg border border-slate-300 dark:border-slate-700 dark:bg-slate-800 focus:ring-primary focus:border-primary px-3 py-2"
                  placeholder="티켓의 제목을 입력하세요"
                />
              </label>
              <label className="block">
                <span className="block text-xs font-bold text-slate-500 uppercase tracking-wider mb-1.5">상세 설명</span>
                <textarea
                  value={description}
                  onChange={(e) => setDescription(e.target.value)}
                  rows={4}
                  className="w-full rounded-lg border border-slate-300 dark:border-slate-700 dark:bg-slate-800 focus:ring-primary focus:border-primary px-3 py-2"
                  placeholder="티켓에 대한 상세 내용을 설명해주세요"
                />
              </label>
            </div>
          </section>

          {/* Source Selection */}
          <section className="bg-white dark:bg-slate-900 rounded-xl border border-slate-200 dark:border-slate-800 shadow-sm overflow-hidden">
            <div className="p-5 border-b border-slate-100 dark:border-slate-800">
              <h3 className="font-bold">배포 리소스 구성</h3>
            </div>
            <div className="p-5">
              <SourceSelector sources={sources} onChange={setSources} />
            </div>
          </section>

          {/* Form Actions */}
          <div className="flex items-center justify-end gap-3 pt-6 border-t border-slate-200 dark:border-slate-800">
            <button
              type="button"
              onClick={() => navigate('/tickets')}
              className="px-6 py-2.5 text-sm font-bold text-slate-600 dark:text-slate-400 hover:bg-slate-100 dark:hover:bg-slate-800 rounded-lg transition-colors"
            >
              취소
            </button>
            <button
              type="submit"
              disabled={createTicket.isPending || !name}
              className="px-8 py-2.5 text-sm font-bold text-white bg-primary hover:bg-primary/90 rounded-lg shadow-lg shadow-primary/20 transition-all disabled:opacity-50 disabled:cursor-not-allowed"
            >
              {createTicket.isPending ? '생성 중...' : '티켓 생성'}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}
