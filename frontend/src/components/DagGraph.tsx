import type { PipelineJobLocal } from '../api/pipelineDefinitionApi';

interface DagGraphProps {
  jobs: PipelineJobLocal[];
}

interface NodePosition {
  job: PipelineJobLocal;
  x: number;
  y: number;
  round: number;
}

function topoSort(jobs: PipelineJobLocal[]): NodePosition[] {
  if (jobs.length === 0) return [];

  const nameToJob = new Map(jobs.map((j) => [j.jobName, j]));
  const inDegree = new Map<string, number>();
  const adj = new Map<string, string[]>();

  for (const job of jobs) {
    inDegree.set(job.jobName, 0);
    adj.set(job.jobName, []);
  }

  for (const job of jobs) {
    for (const dep of job.dependsOn) {
      if (adj.has(dep)) {
        adj.get(dep)!.push(job.jobName);
        inDegree.set(job.jobName, (inDegree.get(job.jobName) ?? 0) + 1);
      }
    }
  }

  const rounds: string[][] = [];
  const visited = new Set<string>();

  while (visited.size < jobs.length) {
    const round: string[] = [];
    for (const [name, deg] of inDegree) {
      if (!visited.has(name) && deg === 0) {
        round.push(name);
      }
    }
    if (round.length === 0) break; // cycle detected
    for (const name of round) {
      visited.add(name);
      for (const next of adj.get(name) ?? []) {
        inDegree.set(next, (inDegree.get(next) ?? 0) - 1);
      }
    }
    rounds.push(round);
  }

  const NODE_W = 180;
  const NODE_H = 70;
  const GAP_X = 60;
  const GAP_Y = 30;

  const positions: NodePosition[] = [];
  for (let r = 0; r < rounds.length; r++) {
    const nodesInRound = rounds[r];
    for (let i = 0; i < nodesInRound.length; i++) {
      const job = nameToJob.get(nodesInRound[i])!;
      positions.push({
        job,
        x: r * (NODE_W + GAP_X),
        y: i * (NODE_H + GAP_Y),
        round: r,
      });
    }
  }

  return positions;
}

const NODE_W = 180;
const NODE_H = 56;

function jobTypeColor(jobType: string): string {
  switch (jobType.toUpperCase()) {
    case 'BUILD': return 'border-blue-400 bg-blue-50 dark:bg-blue-900/20';
    case 'DEPLOY': return 'border-green-400 bg-green-50 dark:bg-green-900/20';
    default: return 'border-slate-300 bg-slate-50 dark:bg-slate-800';
  }
}

function jobTypeIcon(jobType: string): string {
  switch (jobType.toUpperCase()) {
    case 'BUILD': return 'build';
    case 'DEPLOY': return 'rocket_launch';
    default: return 'settings';
  }
}

export default function DagGraph({ jobs }: DagGraphProps) {
  if (!jobs || jobs.length === 0) {
    return (
      <div className="flex items-center justify-center py-12 text-slate-400 text-sm">
        <span className="material-symbols-outlined mr-2">account_tree</span>
        Job을 추가하면 DAG 그래프가 표시됩니다.
      </div>
    );
  }

  const positions = topoSort(jobs);
  if (positions.length === 0) return null;

  const nameToPos = new Map(positions.map((p) => [p.job.jobName, p]));

  const maxX = Math.max(...positions.map((p) => p.x)) + NODE_W;
  const maxY = Math.max(...positions.map((p) => p.y)) + NODE_H;
  const svgW = maxX + 40;
  const svgH = maxY + 40;

  const edges: { x1: number; y1: number; x2: number; y2: number }[] = [];
  for (const pos of positions) {
    for (const dep of pos.job.dependsOn) {
      const from = nameToPos.get(dep);
      if (from) {
        edges.push({
          x1: from.x + NODE_W + 20,
          y1: from.y + NODE_H / 2 + 20,
          x2: pos.x + 20,
          y2: pos.y + NODE_H / 2 + 20,
        });
      }
    }
  }

  return (
    <div className="overflow-x-auto">
      <svg width={svgW} height={svgH} className="min-w-full">
        <defs>
          <marker id="arrow" viewBox="0 0 10 7" refX="10" refY="3.5" markerWidth="8" markerHeight="6" orient="auto-start-reverse">
            <path d="M 0 0 L 10 3.5 L 0 7 z" className="fill-slate-400 dark:fill-slate-500" />
          </marker>
        </defs>

        {edges.map((e, i) => (
          <line
            key={i}
            x1={e.x1} y1={e.y1} x2={e.x2} y2={e.y2}
            className="stroke-slate-300 dark:stroke-slate-600"
            strokeWidth={2}
            markerEnd="url(#arrow)"
          />
        ))}

        {positions.map((pos) => (
          <foreignObject
            key={pos.job.jobName}
            x={pos.x + 20}
            y={pos.y + 20}
            width={NODE_W}
            height={NODE_H}
          >
            <div className={`h-full rounded-lg border-2 px-3 py-2 flex items-center gap-2 ${jobTypeColor(pos.job.jobType)}`}>
              <span className="material-symbols-outlined text-lg text-slate-600 dark:text-slate-300">
                {jobTypeIcon(pos.job.jobType)}
              </span>
              <div className="min-w-0 flex-1">
                <p className="text-xs font-bold text-slate-700 dark:text-slate-200 truncate">{pos.job.jobName}</p>
                <p className="text-[10px] text-slate-500 uppercase">{pos.job.jobType}</p>
              </div>
            </div>
          </foreignObject>
        ))}
      </svg>
    </div>
  );
}
