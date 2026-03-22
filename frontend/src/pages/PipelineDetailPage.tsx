import { useState, useMemo } from 'react';
import toast from 'react-hot-toast';
import { useParams, useNavigate } from 'react-router-dom';
import {
  usePipelineDefinition,
  useUpdatePipelineMappings,
  useExecutePipeline,
  useDeletePipelineDefinition,
  usePipelineExecutions,
} from '../hooks/usePipelineDefinition';
import type { PipelineJobResponse, PipelineJobMappingRequest } from '../api/pipelineDefinitionApi';
import StatusBadge from '../components/StatusBadge';
import LiveDagGraph from '../components/LiveDagGraph';
import JobSelector, { PipelineJobMappingLocal } from '../components/JobSelector';
import PipelineExecutionPanel from '../components/PipelineExecutionPanel';
import ParameterInputModal from '../components/ParameterInputModal';

/** 서버 응답 → UI 로컬 상태 변환 */
function toMappingLocals(jobs: PipelineJobResponse[]): PipelineJobMappingLocal[] {
  return jobs.map((j) => ({
    jobId: j.id,
    jobName: j.jobName,
    jobType: j.jobType,
    presetName: j.presetName,
    executionOrder: j.executionOrder,
    dependsOnJobIds: j.dependsOnJobIds,
  }));
}

/** UI 로컬 상태 → 서버 요청 변환 */
function toMappingRequests(mappings: PipelineJobMappingLocal[]): PipelineJobMappingRequest[] {
  return mappings.map((m) => ({
    jobId: m.jobId,
    executionOrder: m.executionOrder,
    dependsOnJobIds: m.dependsOnJobIds,
  }));
}

export default function PipelineDetailPage() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const pipelineId = Number(id);
  const isValidId = !isNaN(pipelineId) && pipelineId > 0;

  const { data: pipeline, isLoading, error } = usePipelineDefinition(isValidId ? pipelineId : -1);
  const updateMappings = useUpdatePipelineMappings();
  const executePipeline = useExecutePipeline();
  const deletePipeline = useDeletePipelineDefinition();

  const [localMappings, setLocalMappings] = useState<PipelineJobMappingLocal[] | null>(null);
  const [showJobSelector, setShowJobSelector] = useState(false);
  const [isDirty, setIsDirty] = useState(false);
  const [showParamModal, setShowParamModal] = useState(false);
  // 'none' = 사용자가 명시적으로 해제, null = 자동 선택
  const [selectedExecutionId, setSelectedExecutionId] = useState<string | 'none' | null>(null);

  const { data: executions } = usePipelineExecutions(isValidId ? pipelineId : -1);

  // 동기적으로 선택된 실행 결정 (깜빡임 방지)
  const selectedExecution = useMemo(() => {
    if (!executions || executions.length === 0) return undefined;
    if (selectedExecutionId === 'none') return undefined;

    // 사용자가 명시적으로 선택한 경우
    if (selectedExecutionId) {
      return executions.find((e) => e.executionId === selectedExecutionId);
    }

    // 자동 선택: RUNNING 우선, 없으면 최신 실행
    const running = executions.find(
      (e) => e.status === 'RUNNING' || e.status === 'PENDING' || e.status === 'WAITING_WEBHOOK'
    );
    if (running) return running;

    const sorted = [...executions].sort((a, b) => {
      const ta = a.startedAt ? new Date(a.startedAt).getTime() : 0;
      const tb = b.startedAt ? new Date(b.startedAt).getTime() : 0;
      return tb - ta;
    });
    return sorted[0];
  }, [executions, selectedExecutionId]);

  const effectiveSelectedId = selectedExecution?.executionId ?? null;

  const serverMappings = useMemo(
    () => (pipeline?.jobs ? toMappingLocals(pipeline.jobs) : []),
    [pipeline?.jobs],
  );

  const mappings = localMappings ?? serverMappings;

  // LiveDagGraph는 PipelineJobLocal[] 형태를 기대하므로 변환
  const dagJobs = mappings.map((m) => ({
    id: m.jobId,
    jobName: m.jobName,
    jobType: m.jobType,
    executionOrder: m.executionOrder,
    dependsOn: mappings
      .filter((other) => m.dependsOnJobIds.includes(other.jobId))
      .map((other) => other.jobName),
  }));

  if (!isValidId) {
    return (
      <div className="flex-1 flex items-center justify-center">
        <div className="text-center">
          <h2 className="text-lg font-bold">Invalid Pipeline ID</h2>
          <p className="text-slate-500 mt-2 text-sm">"{id}" is not a valid pipeline ID.</p>
          <button
            className="mt-4 px-5 py-2.5 bg-primary text-white text-sm font-bold rounded-lg hover:bg-primary/90 transition-all"
            onClick={() => navigate('/pipelines')}
          >
            Back to Pipelines
          </button>
        </div>
      </div>
    );
  }

  if (isLoading) {
    return (
      <div className="flex-1 flex items-center justify-center">
        <p className="text-slate-500">Loading...</p>
      </div>
    );
  }

  if (error) {
    const isNotFound = 'status' in error && (error as any).status === 404;
    return (
      <div className="flex-1 flex items-center justify-center">
        <div className="text-center">
          {isNotFound ? (
            <>
              <span className="material-symbols-outlined text-5xl text-slate-300 mb-3">search_off</span>
              <h2 className="text-lg font-bold text-slate-700">파이프라인을 찾을 수 없습니다</h2>
              <p className="text-slate-400 mt-2 text-sm">요청하신 파이프라인(ID: {id})이 존재하지 않거나 삭제되었습니다.</p>
            </>
          ) : (
            <>
              <h2 className="text-lg font-bold">Failed to load pipeline</h2>
              <p className="text-red-600 mt-2 text-sm">{error.message}</p>
            </>
          )}
          <button
            className="mt-4 px-5 py-2.5 bg-primary text-white text-sm font-bold rounded-lg hover:bg-primary/90 transition-all"
            onClick={() => navigate('/pipelines')}
          >
            Back to Pipelines
          </button>
        </div>
      </div>
    );
  }

  if (!pipeline) return <p className="p-8 text-slate-500">Pipeline not found</p>;

  const handleSaveMappings = async () => {
    try {
      await updateMappings.mutateAsync({
        id: pipelineId,
        data: { mappings: toMappingRequests(mappings) },
      });
      setLocalMappings(null);
      setIsDirty(false);
      toast.success('매핑이 저장되었습니다');
    } catch (err) {
      toast.error(err instanceof Error ? err.message : 'Failed to save mappings');
    }
  };

  const handleJobSelectorUpdate = (updated: PipelineJobMappingLocal[]) => {
    setLocalMappings(updated);
    setIsDirty(true);
    setShowJobSelector(false);
  };

  const handleRemoveMapping = (jobId: number) => {
    const updated = mappings
      .filter((m) => m.jobId !== jobId)
      .map((m) => ({
        ...m,
        dependsOnJobIds: m.dependsOnJobIds.filter((id) => id !== jobId),
      }));
    setLocalMappings(updated);
    setIsDirty(true);
  };

  const schemas = (pipeline?.jobs ?? []).flatMap((j) => j.parameterSchemas ?? []);

  const handleExecute = async (params?: Record<string, string>) => {
    try {
      await executePipeline.mutateAsync({ id: pipelineId, params });
      setShowParamModal(false);
      // 실행 후 자동 선택을 위해 초기화 → useEffect가 RUNNING 실행을 잡아줌
      setSelectedExecutionId(null);
      toast.success('실행이 시작되었습니다');
    } catch (err) {
      toast.error(err instanceof Error ? err.message : 'Failed to execute pipeline');
    }
  };

  const handleExecuteClick = () => {
    if (schemas.length > 0) {
      setShowParamModal(true);
    } else {
      handleExecute();
    }
  };

  const handleDelete = async () => {
    if (!window.confirm(`"${pipeline.name}" 파이프라인을 삭제하시겠습니까?`)) return;
    try {
      await deletePipeline.mutateAsync(pipelineId);
      navigate('/pipelines');
    } catch (err) {
      toast.error(err instanceof Error ? err.message : 'Failed to delete pipeline');
    }
  };

  return (
    <div className="flex-1 overflow-y-auto p-8">
      <div className="max-w-6xl mx-auto">
        {/* Header */}
        <div className="flex flex-col md:flex-row md:items-center justify-between gap-4 mb-8">
          <div className="flex items-center gap-4">
            <button
              type="button"
              onClick={() => navigate('/pipelines')}
              className="p-2 rounded-lg hover:bg-slate-100 dark:hover:bg-slate-800 transition-colors text-slate-500"
              title="목록으로"
            >
              <span className="material-symbols-outlined text-[20px]">arrow_back</span>
            </button>
            <div className="p-3 bg-white dark:bg-slate-800 rounded-xl shadow-sm border border-slate-200 dark:border-slate-700">
              <span className="material-symbols-outlined text-primary text-3xl">account_tree</span>
            </div>
            <div>
              <h2 className="text-2xl font-bold">{pipeline.name}</h2>
              <p className="text-slate-500 text-sm">
                ID: #{pipeline.id} · 생성일: {new Date(pipeline.createdAt).toLocaleDateString()}
              </p>
            </div>
          </div>
          <div className="flex items-center gap-3">
            <button
              className="px-5 py-2.5 bg-red-50 text-red-600 border border-red-200 text-sm font-bold rounded-lg hover:bg-red-100 transition-all flex items-center gap-2"
              onClick={handleDelete}
            >
              <span className="material-symbols-outlined text-sm">delete</span>
              삭제
            </button>
          </div>
        </div>

        <div className="grid grid-cols-1 lg:grid-cols-3 gap-8">
          {/* Left Column: Info + Execution */}
          <div className="lg:col-span-1 space-y-6">
            {/* Section A: Pipeline Info */}
            <div className="bg-white dark:bg-slate-900 rounded-xl border border-slate-200 dark:border-slate-800 shadow-sm overflow-hidden">
              <div className="p-5 border-b border-slate-100 dark:border-slate-800">
                <h3 className="font-bold">파이프라인 정보</h3>
              </div>
              <div className="p-5 space-y-4">
                <div>
                  <label className="text-[11px] font-bold text-slate-400 uppercase tracking-wider">상태</label>
                  <div className="mt-1">
                    <StatusBadge status={pipeline.status} />
                  </div>
                </div>
                {pipeline.description && (
                  <div>
                    <label className="text-[11px] font-bold text-slate-400 uppercase tracking-wider">설명</label>
                    <p className="mt-1 text-sm text-slate-600 dark:text-slate-300 leading-relaxed">
                      {pipeline.description}
                    </p>
                  </div>
                )}
                <div>
                  <label className="text-[11px] font-bold text-slate-400 uppercase tracking-wider">실패 정책</label>
                  <p className="mt-1 text-sm text-slate-600 dark:text-slate-300">{pipeline.failurePolicy || 'STOP_ALL'}</p>
                </div>
                <div>
                  <label className="text-[11px] font-bold text-slate-400 uppercase tracking-wider">Job 수</label>
                  <p className="mt-1 text-sm text-slate-600 dark:text-slate-300">{mappings.length}개</p>
                </div>
              </div>
            </div>

            {showParamModal && (
              <ParameterInputModal
                schemas={schemas}
                onSubmit={handleExecute}
                onCancel={() => setShowParamModal(false)}
                isPending={executePipeline.isPending}
              />
            )}
          </div>

          {/* Right Column: Job Config + DAG */}
          <div className="lg:col-span-2 space-y-6">
            {/* Section B: Job Config */}
            <div className="bg-white dark:bg-slate-900 rounded-xl border border-slate-200 dark:border-slate-800 shadow-sm overflow-hidden">
              <div className="p-5 border-b border-slate-100 dark:border-slate-800 flex items-center justify-between">
                <h3 className="font-bold">Job 구성</h3>
                <div className="flex items-center gap-3">
                  {isDirty && (
                    <button
                      onClick={handleSaveMappings}
                      disabled={updateMappings.isPending}
                      className="px-4 py-2 bg-primary text-white text-xs font-bold rounded-lg hover:bg-primary/90 transition-all flex items-center gap-1.5 disabled:opacity-50"
                    >
                      <span className="material-symbols-outlined text-sm">save</span>
                      {updateMappings.isPending ? '저장 중...' : '저장'}
                    </button>
                  )}
                  {!showJobSelector && (
                    <button
                      onClick={() => setShowJobSelector(true)}
                      className="px-4 py-2 border border-slate-200 dark:border-slate-700 text-slate-600 dark:text-slate-400 text-xs font-bold rounded-lg hover:bg-slate-50 dark:hover:bg-slate-800 transition-colors flex items-center gap-1.5"
                    >
                      <span className="material-symbols-outlined text-sm">checklist</span>
                      Job 선택
                    </button>
                  )}
                </div>
              </div>

              <div className="p-5 space-y-4">
                {/* Job Selector */}
                {showJobSelector && (
                  <JobSelector
                    selectedMappings={mappings}
                    onUpdate={handleJobSelectorUpdate}
                    onCancel={() => setShowJobSelector(false)}
                  />
                )}

                {/* Job Cards */}
                {mappings.length === 0 && !showJobSelector ? (
                  <p className="text-sm text-slate-400 text-center py-8">
                    등록된 Job이 없습니다. "Job 선택" 버튼으로 시작하세요.
                  </p>
                ) : (
                  !showJobSelector && (
                    <div className="space-y-3">
                      {[...mappings]
                        .sort((a, b) => a.executionOrder - b.executionOrder)
                        .map((mapping) => (
                          <div
                            key={mapping.jobId}
                            className="flex items-center justify-between p-4 bg-slate-50 dark:bg-slate-800/50 rounded-lg border border-slate-200 dark:border-slate-700"
                          >
                            <div className="flex items-center gap-3">
                              <span className="text-xs font-bold text-slate-400 w-6 text-center">{mapping.executionOrder}</span>
                              <div>
                                <p className="text-sm font-bold text-slate-700 dark:text-slate-200">{mapping.jobName}</p>
                                <div className="flex items-center gap-2 mt-0.5">
                                  <span className="text-[10px] uppercase font-bold text-slate-500">{mapping.jobType}</span>
                                  {mapping.presetName && (
                                    <span className="text-[10px] text-slate-400">
                                      preset: {mapping.presetName}
                                    </span>
                                  )}
                                  {mapping.dependsOnJobIds.length > 0 && (
                                    <span className="text-[10px] text-slate-400">
                                      depends: {mappings
                                        .filter((m) => mapping.dependsOnJobIds.includes(m.jobId))
                                        .map((m) => m.jobName)
                                        .join(', ')}
                                    </span>
                                  )}
                                </div>
                              </div>
                            </div>
                            <button
                              onClick={() => handleRemoveMapping(mapping.jobId)}
                              className="p-1.5 text-slate-400 hover:text-red-500 transition-colors rounded"
                              title="제거"
                            >
                              <span className="material-symbols-outlined text-[18px]">close</span>
                            </button>
                          </div>
                        ))}
                    </div>
                  )
                )}
              </div>
            </div>

            {/* DAG Graph */}
            <div className="bg-white dark:bg-slate-900 rounded-xl border border-slate-200 dark:border-slate-800 shadow-sm overflow-hidden">
              <div className="p-5 border-b border-slate-100 dark:border-slate-800 flex items-center justify-between">
                <h3 className="font-bold">DAG 그래프</h3>
                {selectedExecution && (
                  <button
                    onClick={() => setSelectedExecutionId('none')}
                    className="text-xs text-slate-400 hover:text-slate-600 dark:hover:text-slate-300 flex items-center gap-1"
                  >
                    <span className="material-symbols-outlined text-[14px]">close</span>
                    실행 보기 해제
                  </button>
                )}
              </div>
              <div className="p-5">
                <LiveDagGraph jobs={dagJobs} execution={selectedExecution} />
              </div>
            </div>

            {/* Execution Panel — DAG 하단 */}
            <PipelineExecutionPanel
              pipelineId={pipelineId}
              onExecute={handleExecuteClick}
              isPending={executePipeline.isPending}
              onSelectExecution={setSelectedExecutionId}
              selectedExecutionId={effectiveSelectedId}
              rawSelectedExecutionId={selectedExecutionId}
            />
          </div>
        </div>
      </div>
    </div>
  );
}
