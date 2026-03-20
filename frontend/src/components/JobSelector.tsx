import { useState } from 'react';
import { useJobList } from '../hooks/useJobs';

export interface PipelineJobMappingLocal {
  jobId: number;
  jobName: string;
  jobType: string;
  presetName?: string;
  executionOrder: number;
  dependsOnJobIds: number[];
}

interface JobSelectorProps {
  selectedMappings: PipelineJobMappingLocal[];
  onUpdate: (mappings: PipelineJobMappingLocal[]) => void;
  onCancel: () => void;
}

export default function JobSelector({ selectedMappings, onUpdate, onCancel }: JobSelectorProps) {
  const { data: availableJobs, isLoading } = useJobList();
  const [mappings, setMappings] = useState<PipelineJobMappingLocal[]>(selectedMappings);

  const selectedJobIds = new Set(mappings.map((m) => m.jobId));

  const handleToggleJob = (jobId: number) => {
    if (selectedJobIds.has(jobId)) {
      setMappings((prev) => {
        const removed = prev.filter((m) => m.jobId !== jobId);
        // 의존성에서도 제거
        return removed.map((m) => ({
          ...m,
          dependsOnJobIds: m.dependsOnJobIds.filter((id) => id !== jobId),
        }));
      });
    } else {
      const job = availableJobs?.find((j) => j.id === jobId);
      if (!job) return;
      setMappings((prev) => [
        ...prev,
        {
          jobId: job.id,
          jobName: job.jobName,
          jobType: job.jobType,
          presetName: job.presetName,
          executionOrder: prev.length + 1,
          dependsOnJobIds: [],
        },
      ]);
    }
  };

  const handleOrderChange = (jobId: number, order: number) => {
    setMappings((prev) =>
      prev.map((m) => (m.jobId === jobId ? { ...m, executionOrder: order } : m)),
    );
  };

  const handleDependencyToggle = (jobId: number, depJobId: number) => {
    setMappings((prev) =>
      prev.map((m) => {
        if (m.jobId !== jobId) return m;
        const deps = m.dependsOnJobIds.includes(depJobId)
          ? m.dependsOnJobIds.filter((id) => id !== depJobId)
          : [...m.dependsOnJobIds, depJobId];
        return { ...m, dependsOnJobIds: deps };
      }),
    );
  };

  const sortedMappings = [...mappings].sort((a, b) => a.executionOrder - b.executionOrder);

  return (
    <div className="space-y-4">
      <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
        {/* 좌측: 사용 가능한 Job 목록 */}
        <div className="border border-slate-200 dark:border-slate-700 rounded-lg overflow-hidden">
          <div className="px-4 py-3 bg-slate-50 dark:bg-slate-800 border-b border-slate-200 dark:border-slate-700">
            <p className="text-xs font-bold text-slate-500 uppercase tracking-wider">사용 가능한 Job</p>
          </div>
          <div className="max-h-64 overflow-y-auto divide-y divide-slate-100 dark:divide-slate-800">
            {isLoading && (
              <p className="px-4 py-3 text-sm text-slate-400">Loading...</p>
            )}
            {!isLoading && (!availableJobs || availableJobs.length === 0) && (
              <p className="px-4 py-3 text-sm text-slate-400">등록된 Job이 없습니다.</p>
            )}
            {availableJobs?.map((job) => (
              <label
                key={job.id}
                className="flex items-center gap-3 px-4 py-3 hover:bg-slate-50 dark:hover:bg-slate-800/50 cursor-pointer transition-colors"
              >
                <input
                  type="checkbox"
                  checked={selectedJobIds.has(job.id)}
                  onChange={() => handleToggleJob(job.id)}
                  className="rounded border-slate-300 text-primary focus:ring-primary"
                />
                <div className="flex-1 min-w-0">
                  <p className="text-sm font-semibold text-slate-700 dark:text-slate-200 truncate">{job.jobName}</p>
                  <p className="text-[10px] uppercase font-bold text-slate-400">{job.jobType}</p>
                </div>
              </label>
            ))}
          </div>
        </div>

        {/* 우측: 선택된 Job 순서/의존성 설정 */}
        <div className="border border-slate-200 dark:border-slate-700 rounded-lg overflow-hidden">
          <div className="px-4 py-3 bg-slate-50 dark:bg-slate-800 border-b border-slate-200 dark:border-slate-700">
            <p className="text-xs font-bold text-slate-500 uppercase tracking-wider">선택된 Job ({mappings.length}개)</p>
          </div>
          <div className="max-h-64 overflow-y-auto divide-y divide-slate-100 dark:divide-slate-800">
            {mappings.length === 0 && (
              <p className="px-4 py-3 text-sm text-slate-400">좌측에서 Job을 선택하세요.</p>
            )}
            {sortedMappings.map((mapping) => (
              <div key={mapping.jobId} className="px-4 py-3 space-y-2">
                <div className="flex items-center gap-2">
                  <div className="flex-1 min-w-0">
                    <p className="text-sm font-semibold text-slate-700 dark:text-slate-200 truncate">{mapping.jobName}</p>
                    <p className="text-[10px] uppercase font-bold text-slate-400">{mapping.jobType}</p>
                  </div>
                  <div className="flex items-center gap-1.5 flex-shrink-0">
                    <label className="text-[10px] text-slate-400 font-bold">순서</label>
                    <input
                      type="number"
                      min={1}
                      value={mapping.executionOrder}
                      onChange={(e) => handleOrderChange(mapping.jobId, Number(e.target.value))}
                      className="w-14 px-2 py-1 border border-slate-300 dark:border-slate-700 dark:bg-slate-800 rounded text-sm text-center focus:ring-2 focus:ring-primary focus:border-primary outline-none"
                    />
                  </div>
                </div>
                {mappings.length > 1 && (
                  <div>
                    <p className="text-[10px] text-slate-400 font-bold mb-1">의존 Job</p>
                    <div className="flex flex-wrap gap-1.5">
                      {mappings
                        .filter((m) => m.jobId !== mapping.jobId)
                        .map((dep) => (
                          <label
                            key={dep.jobId}
                            className="flex items-center gap-1 cursor-pointer"
                          >
                            <input
                              type="checkbox"
                              checked={mapping.dependsOnJobIds.includes(dep.jobId)}
                              onChange={() => handleDependencyToggle(mapping.jobId, dep.jobId)}
                              className="rounded border-slate-300 text-primary focus:ring-primary"
                            />
                            <span className="text-[11px] text-slate-600 dark:text-slate-300">{dep.jobName}</span>
                          </label>
                        ))}
                    </div>
                  </div>
                )}
              </div>
            ))}
          </div>
        </div>
      </div>

      {/* 하단 버튼 */}
      <div className="flex gap-3 pt-2">
        <button
          type="button"
          onClick={onCancel}
          className="flex-1 px-4 py-2.5 border border-slate-200 dark:border-slate-700 text-slate-600 dark:text-slate-400 font-bold rounded-lg text-sm hover:bg-slate-50 dark:hover:bg-slate-800 transition-colors"
        >
          취소
        </button>
        <button
          type="button"
          onClick={() => onUpdate(mappings)}
          className="flex-1 px-4 py-2.5 bg-primary text-white font-bold rounded-lg text-sm hover:bg-primary/90 shadow-lg shadow-primary/20 transition-all"
        >
          저장
        </button>
      </div>
    </div>
  );
}
