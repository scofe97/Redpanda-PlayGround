package com.study.playground.pipeline.dag.dto;

import java.util.List;

/**
 * Grafana Node Graph 패널이 기대하는 형식으로 DAG 구조를 반환한다.
 * nodes와 edges 두 배열로 구성되며, Infinity 데이터소스가 각각을 별도 프레임으로 매핑한다.
 */
public record DagGraphResponse(
        List<Node> nodes,
        List<Edge> edges
) {
    public record Node(
            String id,
            String title,
            String subTitle,
            String mainStat,
            String color
    ) {}

    public record Edge(
            String id,
            String source,
            String target
    ) {}
}
