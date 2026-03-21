import { ReactFlow, Background, Controls, type NodeTypes, type EdgeTypes } from '@xyflow/react';
import type { PipelineJobLocal, PipelineExecutionResponse } from '../api/pipelineDefinitionApi';
import { useDagLayout } from '../hooks/useDagLayout';
import DagJobNode from './dag/DagJobNode';
import DagStatusEdge from './dag/DagStatusEdge';

const nodeTypes: NodeTypes = { dagJob: DagJobNode };
const edgeTypes: EdgeTypes = { dagStatus: DagStatusEdge };

interface LiveDagGraphProps {
  jobs: PipelineJobLocal[];
  execution?: PipelineExecutionResponse;
}

export default function LiveDagGraph({ jobs, execution }: LiveDagGraphProps) {
  const { nodes, edges } = useDagLayout(jobs, execution);

  if (jobs.length === 0) {
    return (
      <div className="flex items-center justify-center py-12 text-slate-400 text-sm">
        <span className="material-symbols-outlined mr-2">account_tree</span>
        Job을 추가하면 DAG 그래프가 표시됩니다.
      </div>
    );
  }

  const isCompensating = execution?.jobExecutions?.some((je) => je.status === 'COMPENSATED') ?? false;

  return (
    <div className="relative">
      {isCompensating && (
        <div className="absolute top-2 left-1/2 -translate-x-1/2 z-10 px-3 py-1.5 bg-orange-50 dark:bg-orange-900/30 border border-orange-300 dark:border-orange-700 rounded-lg flex items-center gap-2">
          <span className="material-symbols-outlined text-orange-500 text-sm">warning</span>
          <span className="text-xs font-medium text-orange-600 dark:text-orange-400">SAGA 보상 처리 중...</span>
        </div>
      )}
      <div className="h-[400px] w-full">
        <ReactFlow
          nodes={nodes}
          edges={edges}
          nodeTypes={nodeTypes}
          edgeTypes={edgeTypes}
          nodesDraggable={false}
          nodesConnectable={false}
          elementsSelectable={false}
          fitView
          fitViewOptions={{ padding: 0.2 }}
          colorMode="system"
          proOptions={{ hideAttribution: true }}
        >
          <Background />
          <Controls showInteractive={false} />
        </ReactFlow>
      </div>
    </div>
  );
}
