import { BaseEdge, getBezierPath, type EdgeProps } from '@xyflow/react';

export interface DagStatusEdgeData {
  fromStatus?: string;
  toStatus?: string;
  [key: string]: unknown;
}

export default function DagStatusEdge({
  id
  , sourceX, sourceY, sourcePosition
  , targetX, targetY, targetPosition
  , style
  , markerEnd
  , data
}: EdgeProps) {
  const [edgePath] = getBezierPath({
    sourceX, sourceY, sourcePosition
    , targetX, targetY, targetPosition
  });

  const edgeData = data as DagStatusEdgeData | undefined;
  const isCompensating = edgeData?.toStatus === 'COMPENSATED';

  return (
    <g className={isCompensating ? 'dag-edge-compensating' : ''}>
      <BaseEdge
        id={id}
        path={edgePath}
        style={{ strokeWidth: 2, ...style }}
        markerEnd={markerEnd}
      />
    </g>
  );
}
