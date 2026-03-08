# Frontend Deep Dive

이 문서는 Redpanda Playground 프론트엔드의 구조, 핵심 훅, SSE 연동 패턴을 정리한다. 전체 프로젝트 개요는 [project-deep-dive.md](./project-deep-dive.md)를 참조한다.

---

## 1. 기술 스택

React 19 + Vite 6 + TypeScript 5.6 + TanStack Query 5 + Tailwind CSS 4 + React Router 7. UI 라이브러리나 폼 라이브러리 없이 최소 의존성으로 구성했다. Tailwind 4는 Vite 플러그인 방식이라 PostCSS 설정이 필요 없다.

---

## 2. 페이지 구성 (4 pages)

| 페이지 | 컴포넌트 | 역할 |
|--------|----------|------|
| 도구 관리 | `ToolListPage` | 외부 도구(Jenkins/GitLab/Nexus/Registry) CRUD, 연결 테스트 |
| 티켓 목록 | `TicketListPage` | 배포 티켓 목록 조회 |
| 티켓 생성 | `TicketCreatePage` | 소스 선택(SourceSelector) + 티켓 생성 |
| 티켓 상세 | `TicketDetailPage` | 파이프라인 실행/모니터링, SSE 실시간 로그 |

---

## 3. 핵심 훅

| 훅 | 역할 |
|----|------|
| `useTickets` | 티켓 CRUD (TanStack Query) |
| `useTools` | 도구 CRUD + 연결 테스트 (TanStack Query) |
| `usePipeline*` | 4개 개별 훅(usePipelineLatest, usePipelineHistory, useStartPipeline, useStartPipelineWithFailure)으로 분리. 조회 + 실행 + 실패 시뮬레이션 |
| `useSources` | 소스 브라우징 — GitLab 프로젝트/브랜치, Nexus 아티팩트, Registry 이미지 조회 |
| `useSSE` | SSE 연결 관리, 지수 백오프 재연결(1s→30s), 이벤트 수신 시 Query 캐시 무효화 |

---

## 4. SSE + TanStack Query 연동 패턴

`useSSE`는 이벤트 데이터를 자체 저장하지 않는다. 대신 이벤트를 수신하면 `queryClient.invalidateQueries()`를 호출해서 관련 쿼리를 다시 fetch하게 만든다. 이렇게 하면 SSE와 REST 데이터의 일관성이 자동으로 유지된다.

```
SSE 이벤트 수신
  → "status" 이벤트: pipeline 쿼리 무효화 → UI 갱신
  → "completed" 이벤트: pipeline + ticket 쿼리 무효화 → EventSource 종료
```

`TicketDetailPage`에서 `isRunning` 상태(`RUNNING`, `PENDING`, 또는 스텝이 `WAITING_WEBHOOK`)일 때만 SSE를 활성화한다.

### 왜 이 패턴인가

SSE 이벤트에 전체 데이터를 담아 로컬 상태로 관리하는 방식도 가능하다. 하지만 그렇게 하면 SSE 연결이 끊겼다 재연결될 때 누락된 이벤트를 따로 처리해야 하고, REST API 응답과 SSE 데이터 간 불일치가 발생할 수 있다.

캐시 무효화 방식은 이 문제를 구조적으로 피한다. SSE는 "변경이 있었다"는 신호만 전달하고, 실제 데이터는 항상 REST API에서 가져온다. 재연결 시에도 `invalidateQueries()`만 호출하면 최신 상태로 자동 동기화된다.

### 지수 백오프 재연결

`useSSE`는 연결 실패 시 지수 백오프로 재연결을 시도한다:

```
1차 실패: 1초 후 재시도
2차 실패: 2초 후 재시도
3차 실패: 4초 후 재시도
...
최대 30초 간격으로 수렴
```

SSE 연결이 정상 수립되면 재시도 간격이 초기값(1초)으로 리셋된다. 서버 재시작이나 네트워크 일시 단절에 자동 복구되는 구조다.
