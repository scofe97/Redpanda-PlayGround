# API 레퍼런스 — DAG Pipeline

`PipelineDefinitionController`가 제공하는 12개 REST 엔드포인트를 정리한다. 모든 엔드포인트는 `/api/pipelines` 기본 경로 하위에 위치한다.

---

## 엔드포인트 요약

| Method | Path | 설명 |
|--------|------|------|
| POST | /api/pipelines | 파이프라인 정의 생성 |
| GET | /api/pipelines | 전체 정의 목록 조회 |
| GET | /api/pipelines/{id} | 정의 상세 조회 |
| PUT | /api/pipelines/{id}/mappings | Job 매핑 갱신 |
| DELETE | /api/pipelines/{id} | 정의 삭제 |
| POST | /api/pipelines/{id}/execute | 파이프라인 실행 |
| POST | /api/pipelines/{id}/executions/{executionId}/restart | 실패 실행 재시작 |
| GET | /api/pipelines/{id}/executions | 실행 이력 조회 |
| GET | /api/pipelines/executions/{executionId} | 실행 상세 조회 |
| GET | /api/pipelines/executions/{executionId}/dag-graph | DAG 그래프 (전체) |
| GET | /api/pipelines/executions/{executionId}/dag-graph/nodes | DAG 노드 목록 |
| GET | /api/pipelines/executions/{executionId}/dag-graph/edges | DAG 엣지 목록 |

---

## 1. 파이프라인 정의 생성

**`POST /api/pipelines`**

새 파이프라인 정의를 생성한다. Job 매핑은 이 시점에 포함되지 않으며, 생성 후 `PUT /{id}/mappings`로 별도 설정한다.

**Request Body:**
```json
{
  "name": "build-and-deploy",
  "description": "빌드 후 배포 파이프라인"
}
```

| 필드 | 타입 | 필수 | 제약 | 설명 |
|------|------|------|------|------|
| name | String | O | 최대 200자 | 파이프라인 이름 |
| description | String | X | - | 파이프라인 설명 |

**Response:** `201 Created`
```json
{
  "id": 1,
  "name": "build-and-deploy",
  "description": "빌드 후 배포 파이프라인",
  "status": "ACTIVE",
  "failurePolicy": "STOP_ON_FAILURE",
  "createdAt": "2026-03-22T10:00:00",
  "jobs": []
}
```

| 필드 | 타입 | 설명 |
|------|------|------|
| id | Long | 파이프라인 정의 ID |
| name | String | 파이프라인 이름 |
| description | String | 설명 |
| status | String | 정의 상태. 예: `ACTIVE` |
| failurePolicy | String | 실패 정책. 예: `STOP_ON_FAILURE` |
| createdAt | LocalDateTime | 생성 시각 |
| jobs | Array | Job 목록 (생성 직후 빈 배열) |

---

## 2. 전체 정의 목록 조회

**`GET /api/pipelines`**

등록된 모든 파이프라인 정의를 반환한다.

**Response:** `200 OK`
```json
[
  {
    "id": 1,
    "name": "build-and-deploy",
    "description": "빌드 후 배포 파이프라인",
    "status": "ACTIVE",
    "failurePolicy": "STOP_ON_FAILURE",
    "createdAt": "2026-03-22T10:00:00",
    "jobs": [ ... ]
  }
]
```

응답 필드 구조는 [1. 파이프라인 정의 생성](#1-파이프라인-정의-생성)의 Response와 동일하다.

---

## 3. 정의 상세 조회

**`GET /api/pipelines/{id}`**

특정 파이프라인 정의와 포함된 Job 목록을 반환한다.

**Path Variable:**

| 변수 | 타입 | 설명 |
|------|------|------|
| id | Long | 파이프라인 정의 ID |

**Response:** `200 OK`
```json
{
  "id": 1,
  "name": "build-and-deploy",
  "description": "빌드 후 배포 파이프라인",
  "status": "ACTIVE",
  "failurePolicy": "STOP_ON_FAILURE",
  "createdAt": "2026-03-22T10:00:00",
  "jobs": [
    {
      "id": 10,
      "jobName": "Build Job",
      "jobType": "BUILD",
      "executionOrder": 1,
      "presetId": 3,
      "presetName": "maven-build",
      "configJson": "{\"GIT_URL\":\"http://gitlab/repo.git\"}",
      "parameterSchemas": [
        {
          "key": "GIT_URL",
          "required": true,
          "defaultValue": null
        }
      ],
      "dependsOnJobIds": []
    }
  ]
}
```

`jobs` 배열 내 각 항목의 필드:

| 필드 | 타입 | 설명 |
|------|------|------|
| id | Long | Job ID |
| jobName | String | Job 이름 |
| jobType | String | Job 타입. 예: `BUILD`, `DEPLOY`, `ARTIFACT_DOWNLOAD` |
| executionOrder | Integer | 실행 순서 |
| presetId | Long | 연결된 프리셋 ID |
| presetName | String | 프리셋 이름 |
| configJson | String | Job 설정 JSON 문자열 |
| parameterSchemas | Array | 파라미터 스키마 목록 |
| dependsOnJobIds | Array\<Long\> | 선행 의존 Job ID 목록 |

---

## 4. Job 매핑 갱신

**`PUT /api/pipelines/{id}/mappings`**

파이프라인에 포함될 Job 목록과 실행 순서, 의존 관계를 일괄 갱신한다. 기존 매핑은 교체된다.

**Path Variable:**

| 변수 | 타입 | 설명 |
|------|------|------|
| id | Long | 파이프라인 정의 ID |

**Request Body:** (배열)
```json
[
  {
    "jobId": 10,
    "executionOrder": 1,
    "dependsOnJobIds": []
  },
  {
    "jobId": 11,
    "executionOrder": 2,
    "dependsOnJobIds": [10]
  }
]
```

| 필드 | 타입 | 필수 | 설명 |
|------|------|------|------|
| jobId | Long | O | 매핑할 Job ID |
| executionOrder | Integer | O | 실행 순서 (낮을수록 먼저 실행) |
| dependsOnJobIds | Array\<Long\> | X | 이 Job이 완료를 기다려야 하는 선행 Job ID 목록 |

**Response:** `200 OK` — 갱신된 파이프라인 정의 전체 반환 (필드 구조는 [1번](#1-파이프라인-정의-생성) 참조)

---

## 5. 정의 삭제

**`DELETE /api/pipelines/{id}`**

파이프라인 정의를 삭제한다. 실행 중인 파이프라인이 있을 경우 409를 반환한다.

**Path Variable:**

| 변수 | 타입 | 설명 |
|------|------|------|
| id | Long | 파이프라인 정의 ID |

**Response:** `204 No Content`

---

## 6. 파이프라인 실행

**`POST /api/pipelines/{id}/execute`**

파이프라인 실행을 접수한다. 실행은 비동기로 처리되며, 응답에 포함된 `trackingUrl`로 SSE를 구독하면 진행 상황을 실시간으로 수신할 수 있다.

**Path Variable:**

| 변수 | 타입 | 설명 |
|------|------|------|
| id | Long | 파이프라인 정의 ID |

**Request Body:** (선택)
```json
{
  "params": {
    "GIT_URL": "http://gitlab/repo.git",
    "BRANCH": "develop"
  }
}
```

| 필드 | 타입 | 필수 | 설명 |
|------|------|------|------|
| params | Map\<String, String\> | X | 실행 시 주입할 파라미터. 프리셋 기본값보다 우선 적용된다. |

**Response:** `202 Accepted`
```json
{
  "executionId": "550e8400-e29b-41d4-a716-446655440000",
  "ticketId": null,
  "status": "PENDING",
  "jobExecutions": null,
  "startedAt": null,
  "completedAt": null,
  "errorMessage": null,
  "parameters": {
    "GIT_URL": "http://gitlab/repo.git",
    "BRANCH": "develop"
  },
  "context": null,
  "trackingUrl": "/api/pipelines/executions/550e8400-e29b-41d4-a716-446655440000/events"
}
```

| 필드 | 타입 | 설명 |
|------|------|------|
| executionId | UUID | 실행 ID |
| ticketId | Long | 티켓 기반 실행이면 티켓 ID, DAG 직접 실행이면 null |
| status | String | 실행 상태. `PENDING`, `RUNNING`, `SUCCESS`, `FAILED` |
| jobExecutions | Array | 접수 시점에는 null. 조회 API에서 채워진다. |
| startedAt | LocalDateTime | 실행 시작 시각 |
| completedAt | LocalDateTime | 실행 완료 시각 |
| errorMessage | String | 실패 원인 메시지 |
| parameters | Map\<String, String\> | 주입된 파라미터 (없으면 null) |
| context | Map\<String, String\> | Job 간 공유 컨텍스트 (빌드 결과물 경로 등, 없으면 null) |
| trackingUrl | String | SSE 구독 엔드포인트 URL |

---

## 7. 실패 실행 재시작

**`POST /api/pipelines/{id}/executions/{executionId}/restart`**

FAILED 상태인 실행을 재시작한다. 파라미터를 생략하면 원래 실행의 파라미터를 그대로 사용한다.

**Path Variables:**

| 변수 | 타입 | 설명 |
|------|------|------|
| id | Long | 파이프라인 정의 ID |
| executionId | UUID | 재시작할 실행 ID |

**Request Body:** (선택) — 6번 실행 요청과 동일한 형식

**Response:** `202 Accepted` — 6번 응답과 동일한 형식

---

## 8. 실행 이력 조회

**`GET /api/pipelines/{id}/executions`**

특정 파이프라인 정의의 모든 실행 이력을 반환한다.

**Path Variable:**

| 변수 | 타입 | 설명 |
|------|------|------|
| id | Long | 파이프라인 정의 ID |

**Response:** `200 OK`
```json
[
  {
    "executionId": "550e8400-e29b-41d4-a716-446655440000",
    "ticketId": null,
    "status": "SUCCESS",
    "jobExecutions": [ ... ],
    "startedAt": "2026-03-22T10:00:00",
    "completedAt": "2026-03-22T10:05:00",
    "errorMessage": null,
    "parameters": { "GIT_URL": "http://gitlab/repo.git" },
    "context": { "ARTIFACT_PATH": "/nexus/repo/app-1.0.0.jar" },
    "trackingUrl": "/api/pipelines/executions/550e8400-e29b-41d4-a716-446655440000/events"
  }
]
```

응답 필드 구조는 [6번 실행 응답](#6-파이프라인-실행)과 동일하다. `jobExecutions` 배열이 채워진 상태로 반환된다.

---

## 9. 실행 상세 조회

**`GET /api/pipelines/executions/{executionId}`**

단일 실행의 상태와 Job 실행 목록을 반환한다.

**Path Variable:**

| 변수 | 타입 | 설명 |
|------|------|------|
| executionId | UUID | 실행 ID |

**Response:** `200 OK`
```json
{
  "executionId": "550e8400-e29b-41d4-a716-446655440000",
  "ticketId": null,
  "status": "RUNNING",
  "jobExecutions": [
    {
      "id": 100,
      "jobOrder": 1,
      "jobType": "BUILD",
      "jobName": "Build Job",
      "status": "SUCCESS",
      "log": "BUILD SUCCESS",
      "retryCount": 0,
      "startedAt": "2026-03-22T10:00:00",
      "completedAt": "2026-03-22T10:02:00"
    },
    {
      "id": 101,
      "jobOrder": 2,
      "jobType": "DEPLOY",
      "jobName": "Deploy Job",
      "status": "RUNNING",
      "log": null,
      "retryCount": 0,
      "startedAt": "2026-03-22T10:02:00",
      "completedAt": null
    }
  ],
  "startedAt": "2026-03-22T10:00:00",
  "completedAt": null,
  "errorMessage": null,
  "parameters": { "BRANCH": "develop" },
  "context": { "ARTIFACT_PATH": "/nexus/repo/app-1.0.0.jar" },
  "trackingUrl": "/api/pipelines/executions/550e8400-e29b-41d4-a716-446655440000/events"
}
```

`jobExecutions` 배열 내 각 항목의 필드:

| 필드 | 타입 | 설명 |
|------|------|------|
| id | Long | Job 실행 ID |
| jobOrder | Integer | 실행 순서 |
| jobType | String | Job 타입. `BUILD`, `DEPLOY`, `ARTIFACT_DOWNLOAD` 등 |
| jobName | String | Job 이름 |
| status | String | Job 상태. `PENDING`, `RUNNING`, `SUCCESS`, `FAILED`, `WAITING_WEBHOOK` |
| log | String | Job 실행 로그 |
| retryCount | Integer | 재시도 횟수. 0이면 최초 실행만 수행된 것이다. |
| startedAt | LocalDateTime | Job 시작 시각 |
| completedAt | LocalDateTime | Job 완료 시각 |

---

## 10. DAG 그래프 전체 조회

**`GET /api/pipelines/executions/{executionId}/dag-graph`**

실행의 DAG 구조를 Grafana Node Graph 패널이 기대하는 형식으로 반환한다. `nodes`와 `edges` 두 배열로 구성된다.

**Path Variable:**

| 변수 | 타입 | 설명 |
|------|------|------|
| executionId | UUID | 실행 ID |

**Response:** `200 OK`
```json
{
  "nodes": [
    {
      "id": "10",
      "title": "Build Job",
      "subTitle": "BUILD",
      "mainStat": "SUCCESS",
      "color": "green"
    },
    {
      "id": "11",
      "title": "Deploy Job",
      "subTitle": "DEPLOY",
      "mainStat": "RUNNING",
      "color": "blue"
    }
  ],
  "edges": [
    {
      "id": "10-11",
      "source": "10",
      "target": "11"
    }
  ]
}
```

`nodes` 필드:

| 필드 | 타입 | 설명 |
|------|------|------|
| id | String | 노드 ID (Job ID 문자열) |
| title | String | Job 이름 |
| subTitle | String | Job 타입 |
| mainStat | String | 현재 실행 상태 |
| color | String | Grafana 패널에서 사용하는 노드 색상 |

`edges` 필드:

| 필드 | 타입 | 설명 |
|------|------|------|
| id | String | 엣지 ID (`{source}-{target}` 형식) |
| source | String | 선행 노드 ID |
| target | String | 후행 노드 ID |

---

## 11. DAG 노드 목록 조회

**`GET /api/pipelines/executions/{executionId}/dag-graph/nodes`**

DAG 그래프에서 노드 배열만 반환한다. Grafana Infinity 데이터소스가 nodes 프레임을 별도로 요청할 때 사용한다.

**Path Variable:**

| 변수 | 타입 | 설명 |
|------|------|------|
| executionId | UUID | 실행 ID |

**Response:** `200 OK` — [10번 응답](#10-dag-그래프-전체-조회)의 `nodes` 배열과 동일한 구조

---

## 12. DAG 엣지 목록 조회

**`GET /api/pipelines/executions/{executionId}/dag-graph/edges`**

DAG 그래프에서 엣지 배열만 반환한다. Grafana Infinity 데이터소스가 edges 프레임을 별도로 요청할 때 사용한다.

**Path Variable:**

| 변수 | 타입 | 설명 |
|------|------|------|
| executionId | UUID | 실행 ID |

**Response:** `200 OK` — [10번 응답](#10-dag-그래프-전체-조회)의 `edges` 배열과 동일한 구조

---

## 공통 에러 응답

| HTTP 상태 | 의미 | 예시 |
|-----------|------|------|
| 400 | 잘못된 요청 | 필수 파라미터 누락, DAG 순환 감지 |
| 404 | 리소스 없음 | 존재하지 않는 정의 ID 또는 실행 ID |
| 409 | 충돌 | 실행 중인 파이프라인 삭제 시도 |
| 500 | 서버 오류 | 예상치 못한 내부 오류 |

> 파라미터 시스템: `04-parameter-system.md`
> 시각화: `05-dag-visualization.md`
