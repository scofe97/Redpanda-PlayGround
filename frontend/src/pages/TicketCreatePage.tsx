import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
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
      alert(err instanceof Error ? err.message : 'Failed to create ticket');
    }
  };

  return (
    <div className="flex-1 overflow-y-auto">
      <div className="max-w-4xl mx-auto py-10 px-8">
        <div className="mb-8">
          <h2 className="text-3xl font-black tracking-tight">새 티켓 생성</h2>
          <p className="text-slate-500 mt-1">새로운 리소스 배포 또는 설정을 위한 티켓을 작성합니다.</p>
        </div>

        <form onSubmit={handleSubmit} className="space-y-8">
          {/* Basic Info */}
          <section className="space-y-4">
            <div className="grid gap-6">
              <label className="block">
                <span className="block text-sm font-bold text-slate-700 dark:text-slate-300 mb-2">이름 (필수)</span>
                <input
                  value={name}
                  onChange={(e) => setName(e.target.value)}
                  required
                  maxLength={200}
                  className="w-full rounded-lg border-slate-300 dark:border-slate-700 dark:bg-slate-800 focus:ring-primary focus:border-primary px-4 py-3"
                  placeholder="티켓의 제목을 입력하세요"
                />
              </label>
              <label className="block">
                <span className="block text-sm font-bold text-slate-700 dark:text-slate-300 mb-2">상세 설명</span>
                <textarea
                  value={description}
                  onChange={(e) => setDescription(e.target.value)}
                  rows={4}
                  className="w-full rounded-lg border-slate-300 dark:border-slate-700 dark:bg-slate-800 focus:ring-primary focus:border-primary px-4 py-3"
                  placeholder="티켓에 대한 상세 내용을 설명해주세요"
                />
              </label>
            </div>
          </section>

          {/* Source Selection */}
          <section className="space-y-6">
            <div>
              <h3 className="text-lg font-bold mb-4">배포 리소스 구성</h3>
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
