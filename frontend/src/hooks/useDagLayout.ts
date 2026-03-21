import { useMemo } from 'react';
import dagre from '@dagrejs/dagre';
import { Position, MarkerType, type Node, type Edge } from '@xyflow/react';
import type { PipelineJobLocal, PipelineExecutionResponse, JobExecutionResponse } from '../api/pipelineDefinitionApi';
import type { DagJobNodeData } from '../components/dag/DagJobNode';
import type { DagStatusEdgeData } from '../components/dag/DagStatusEdge';
import { getEdgeStyle } from '../components/dag/dagStyles';

const NODE_WIDTH = 200;
const NODE_HEIGHT = 56;

function buildLayout(
  jobs: PipelineJobLocal[]
  , jobStatusMap: Map<string, JobExecutionResponse>
): { nodes: Node<DagJobNodeData>[]; edges: Edge[] } {
  if (jobs.length === 0) return { nodes: [], edges: [] };

  const g = new dagre.graphlib.Graph();
  g.setDefaultEdgeLabel(() => ({}));
  g.setGraph({ rankdir: 'LR', nodesep: 50, ranksep: 100 });

  for (const job of jobs) {
    g.setNode(job.jobName, { width: NODE_WIDTH, height: NODE_HEIGHT });
  }

  const edgeDefs: { source: string; target: string }[] = [];
  for (const job of jobs) {
    for (const dep of job.dependsOn) {
      if (jobs.some((j) => j.jobName === dep)) {
        g.setEdge(dep, job.jobName);
        edgeDefs.push({ source: dep, target: job.jobName });
      }
    }
  }

  dagre.layout(g);

  const nodes: Node<DagJobNodeData>[] = jobs.map((job) => {
    const pos = g.node(job.jobName);
    return {
      id: job.jobName
      , type: 'dagJob'
      , position: { x: pos.x - NODE_WIDTH / 2, y: pos.y - NODE_HEIGHT / 2 }
      , sourcePosition: Position.Right
      , targetPosition: Position.Left
      , draggable: false
      , selectable: false
      , data: {
          job
          , jobExecution: jobStatusMap.get(job.jobName)
        }
    };
  });

  const edges: Edge[] = edgeDefs.map((e) => {
    const fromStatus = jobStatusMap.get(e.source)?.status;
    const toStatus = jobStatusMap.get(e.target)?.status;
    const style = getEdgeStyle(fromStatus, toStatus);

    return {
      id: `${e.source}->${e.target}`
      , source: e.source
      , target: e.target
      , type: 'dagStatus'
      , animated: style.animated
      , style: { stroke: style.stroke }
      , markerEnd: { type: MarkerType.ArrowClosed, color: style.stroke }
      , className: style.className
      , data: { fromStatus, toStatus } as DagStatusEdgeData
    };
  });

  return { nodes, edges };
}

export function useDagLayout(
  jobs: PipelineJobLocal[]
  , execution?: PipelineExecutionResponse
) {
  return useMemo(() => {
    const jobStatusMap = new Map<string, JobExecutionResponse>();
    if (execution?.jobExecutions) {
      for (const je of execution.jobExecutions) {
        jobStatusMap.set(je.jobName, je);
      }
    }
    return buildLayout(jobs, jobStatusMap);
  }, [jobs, execution]);
}
