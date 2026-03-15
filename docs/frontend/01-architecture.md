# 프론트엔드 아키텍처

이 문서는 Redpanda Playground 프론트엔드의 구조, 핵심 훅, SSE 연동 패턴을 정리한다. 전체 프로젝트 개요는 [README](../README.md)를 참조한다.

---

## 1. 기술 스택

React 19 + Vite 6 + TypeScript 5.6 + TanStack Query 5 + Tailwind CSS 4 + React Router 7. UI 라이브러리나 폼 라이브러리 없이 최소 의존성으로 구성했다. Tailwind 4는 Vite 플러그인 방식이라 PostCSS 설정이 필요 없다.

각 기술의 선택 이유는 다음과 같다:

- **React 19**: 최신 Concurrent 기능과 안정적인 생태계. 서버 컴포넌트는 사용하지 않고 SPA로 구성한다.
- **Vite 6**: HMR이 빠르고 Tailwind v4 플러그인을 네이티브로 지원한다. CRA나 webpack 대비 설정이 단순하다.
- **TanStack Query 5**: 서버 상태를 캐싱하고, SSE 이벤트 수신 시 캐시 무효화로 UI를 자동 갱신하는 패턴에 적합하다.
- **Tailwind CSS 4**: `@tailwindcss/vite` 플러그인 하나로 동작하며, PostCSS 설정 파일이 필요 없다.
- **React Router 7**: 클라이언트 사이드 라우팅. 4개 페이지 간 전환을 처리한다.

---

## 2. 페이지 구성 (4 pages)

| 페이지 | 컴포넌트 | 역할 |
|--------|----------|------|
| 도구 관리 | `ToolListPage` | 외부 도구(Jenkins/GitLab/Nexus/Registry) CRUD, 연결 테스트 |
| 티켓 목록 | `TicketListPage` | 배포 티켓 목록 조회 |
| 티켓 생성 | `TicketCreatePage` | 소스 선택(SourceSelector) + 티켓 생성 |
| 티켓 상세 | `TicketDetailPage` | 파이프라인 실행/모니터링, SSE 실시간 로그 |

`TicketDetailPage`가 가장 복잡한 페이지다. 파이프라인 실행 버튼, 단계별 타임라인, SSE 실시간 로그 스트리밍을 모두 포함한다. `TicketCreatePage`는 `SourceSelector` 컴포넌트를 통해 GIT/NEXUS/HARBOR 소스를 복수 선택할 수 있으며, 각 소스 타입에 따라 하위 폼이 조건부 렌더링된다.

---

## 3. 핵심 훅

| 훅 | 역할 |
|----|------|
| `useTickets` | 티켓 CRUD (TanStack Query) |
| `useTools` | 도구 CRUD + 연결 테스트 (TanStack Query) |
| `usePipeline*` | 4개 개별 훅으로 분리. 조회 + 실행 + 실패 시뮬레이션 |
| `useSources` | 소스 브라우징 — GitLab 프로젝트/브랜치, Nexus 아티팩트, Registry 이미지 조회 |
| `useSSE` | SSE 연결 관리, 지수 백오프 재연결(1s→30s), 이벤트 수신 시 Query 캐시 무효화 |

### useTickets

TanStack Query의 `useQuery`와 `useMutation`을 조합하여 티켓 목록 조회, 단건 조회, 생성, 삭제를 처리한다. 생성/삭제 mutation이 성공하면 티켓 목록 쿼리를 자동으로 무효화하여 UI를 갱신한다.

### useTools

도구 CRUD에 더해 연결 테스트(`POST /tools/{id}/test`) 기능을 제공한다. 연결 테스트 결과는 로컬 상태로 관리하며, 테스트 중/성공/실패 상태를 도구별로 추적한다.

### usePipeline*

파이프라인 관련 로직은 단일 훅이 아닌 4개 개별 훅으로 분리했다:

- **usePipelineLatest**: 특정 티켓의 최신 파이프라인 조회. SSE 이벤트에 의해 캐시가 무효화되면 자동으로 refetch한다.
- **usePipelineHistory**: 파이프라인 실행 이력 조회.
- **useStartPipeline**: 파이프라인 실행 mutation. 실행 후 latest 쿼리를 무효화한다.
- **useStartPipelineWithFailure**: 실패 시뮬레이션용 mutation. 테스트 목적으로 의도적 실패를 트리거한다.

### useSources

소스 브라우징 훅으로, 등록된 도구(GitLab/Nexus/Harbor)에서 배포 가능한 소스를 조회한다. GitLab은 프로젝트 목록과 브랜치 목록, Nexus는 아티팩트 좌표, Registry는 이미지 태그를 조회한다. `TicketCreatePage`의 `SourceSelector` 컴포넌트에서 사용한다.

### useSSE

SSE(Server-Sent Events) 연결의 전체 생명주기를 관리하는 훅이다. `EventSource` 인스턴스를 생성하고, 이벤트 리스너를 등록하며, 연결 실패 시 지수 백오프 재연결을 수행한다. 이벤트 데이터를 자체적으로 저장하지 않고 TanStack Query 캐시 무효화만 트리거하는 것이 핵심 설계다.

---

## 4. SSE + TanStack Query 연동 패턴

`useSSE`는 이벤트 데이터를 자체 저장하지 않는다. 대신 이벤트를 수신하면 `queryClient.invalidateQueries()`를 호출해서 관련 쿼리를 다시 fetch하게 만든다. 이렇게 하면 SSE와 REST 데이터의 일관성이 자동으로 유지된다.

```
SSE 이벤트 수신
  → "status" 이벤트: pipeline 쿼리 무효화 → UI 갱신
  → "completed" 이벤트: pipeline + ticket 쿼리 무효화 → EventSource 종료
```

`TicketDetailPage`에서 `isRunning` 상태(`RUNNING`, `PENDING`, 또는 스텝이 `WAITING_WEBHOOK`)일 때만 SSE를 활성화한다. 파이프라인이 완료되거나 아직 실행되지 않은 상태에서는 SSE 연결을 맺지 않아 불필요한 서버 리소스 소비를 방지한다.

### 왜 이 패턴인가

SSE 이벤트에 전체 데이터를 담아 로컬 상태로 관리하는 방식도 가능하다. 하지만 그렇게 하면 SSE 연결이 끊겼다 재연결될 때 누락된 이벤트를 따로 처리해야 하고, REST API 응답과 SSE 데이터 간 불일치가 발생할 수 있다.

캐시 무효화 방식은 이 문제를 구조적으로 피한다. SSE는 "변경이 있었다"는 신호만 전달하고, 실제 데이터는 항상 REST API에서 가져온다. 재연결 시에도 `invalidateQueries()`만 호출하면 최신 상태로 자동 동기화된다.

이 패턴의 장점을 정리하면:

- **단일 진실 원천(Single Source of Truth)**: 데이터는 항상 REST API 응답이 기준이다. SSE가 별도 데이터 저장소를 가지지 않으므로 동기화 문제가 원천 차단된다.
- **재연결 단순화**: SSE 연결이 끊겼다 복구되면 `invalidateQueries()`만 호출하면 된다. 누락 이벤트 추적이나 시퀀스 번호 관리가 필요 없다.
- **코드 단순성**: SSE 훅은 연결 관리와 캐시 무효화만 담당하고, 데이터 페칭과 캐싱은 TanStack Query가 전담한다. 관심사가 깔끔하게 분리된다.

### 지수 백오프 재연결

`useSSE`는 연결 실패 시 지수 백오프로 재연결을 시도한다:

```
1차 실패: 1초 후 재시도
2차 실패: 2초 후 재시도
3차 실패: 4초 후 재시도
...
최대 30초 간격으로 수렴
```

SSE 연결이 정상 수립되면 재시도 간격이 초기값(1초)으로 리셋된다. 서버 재시작이나 네트워크 일시 단절에 자동 복구되는 구조다. 최대 간격을 30초로 제한한 이유는 파이프라인 실행 중 사용자가 실시간 피드백을 기대하는 상황이므로 너무 긴 대기는 적절하지 않기 때문이다.

컴포넌트 언마운트 시(페이지 이탈) `EventSource`를 정리하고 재연결 타이머도 취소하여 메모리 누수를 방지한다.

---

## 5. UI 스타일링

> UI 스타일링 가이드: [02-ui-styling-guide.md](./02-ui-styling-guide.md)
