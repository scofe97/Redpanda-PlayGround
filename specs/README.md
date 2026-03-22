# Redpanda Playground — Specs 인덱스

Jenkins/GitLab/Harbor/ArgoCD를 Redpanda(Kafka 호환) 이벤트 버스로 연결하는 CI/CD 플랫폼 PoC의 스펙 문서 모음이다.

---

## 도메인 현황 대시보드 (2026-03-22 기준)

| # | 도메인 | 구현 상태 | Phase | 스펙 문서 |
|---|--------|----------|-------|----------|
| 01 | 티켓 관리 | 부분구현 (기본 CRUD만) | Phase 5 예정 | [domains/01-ticket.md](domains/01-ticket.md) |
| 02 | 워크플로우 | 미구현 | Phase 5 예정 | [domains/02-workflow.md](domains/02-workflow.md) |
| 03 | 파이프라인 | **구현완료** (DAG, SAGA, 재시도, 실패정책, 파라미터) | Phase 1+3 | [domains/03-pipeline.md](domains/03-pipeline.md) |
| 04 | 빌드 Job | 부분구현 (Jenkins BUILD, Nexus 업로드) | Phase 3 | [domains/04-build-job.md](domains/04-build-job.md) |
| 05 | 테스트 Job | PoC 생략 | - | [domains/05-test-job.md](domains/05-test-job.md) |
| 06 | 배포 Job | 부분구현 (Jenkins DEPLOY) | Phase 3 | [domains/06-deploy-job.md](domains/06-deploy-job.md) |
| 07 | 결과물 관리 | 부분구현 (소스 브라우징만) | Phase 4 예정 | [domains/07-artifact.md](domains/07-artifact.md) |
| 08 | 미들웨어 | **구현완료** (프리셋 CRUD, UI) | Phase 0+3 | [domains/08-middleware.md](domains/08-middleware.md) |
| 09 | 배포 환경 | 미구현 | Phase 4 예정 | [domains/09-deploy-env.md](domains/09-deploy-env.md) |
| 10 | 사용자/알림 | 부분구현 (SSE 알림, 감사만) | Phase 5 예정 | [domains/10-user-notification.md](domains/10-user-notification.md) |

---

## Phase 로드맵

| Phase | 내용 | 상태 | 스펙 |
|-------|------|------|------|
| 0 | Middleware Preset — 역할 기반 도구 추상화, 프리셋 CRUD | 완료 | [phases/phase-0.md](phases/phase-0.md) |
| 1 | Job Independence + DAG — 파이프라인 독립성, DAG 실행 엔진 | 완료 | [phases/phase-1.md](phases/phase-1.md) |
| 2 | K8s Migration — Docker → K8s 전환, 인프라 안정화 | 완료 | [phases/phase-2.md](phases/phase-2.md) |
| 3 | DAG Hardening — 파라미터 주입, 빌드/배포 고도화, 실행 컨텍스트, 프리셋 UI | 완료 | [phases/phase-3.md](phases/phase-3.md) |
| 4 | Artifact + Deploy Env — 결과물 CRUD, 배포 환경 관리 | 향후 | [phases/phase-4.md](phases/phase-4.md) |
| 5 | Ticket → Workflow → Pipeline — 업무코드, 워크플로우, 결재 연계 | 향후 | [phases/phase-5.md](phases/phase-5.md) |

---

## 3단 연계 핵심 개념

```
[티켓]  ──→  [워크플로우]  ──→  [파이프라인]
업무코드       컴포넌트 조합        Job 실행 체인
(팀 코드)   (빌드/배포/결재)     (CI/CD 어댑터)
```

- **티켓**: 배포 요청의 단위. 업무코드를 부여하면 워크플로우가 자동으로 결정된다.
- **워크플로우**: 업무코드별 프로세스 템플릿. 빌드/배포/결재 컴포넌트의 조합을 정의한다.
- **파이프라인**: 독립적인 Job 실행 체인. 워크플로우가 참조할 수도 있고, 단독 실행도 가능하다.

> 현재 PoC 상태: 티켓 → 바로 파이프라인 (업무코드/워크플로우 미구현, Phase 5에서 연계 예정)

---

## 문서 탐색 가이드

| 찾는 것 | 위치 | 설명 |
|---------|------|------|
| 도메인 요구사항, 수용 기준 | `specs/domains/` | 무엇을, 왜 구현하는지 |
| Phase별 구현 범위 | `specs/phases/` | 언제, 어떤 순서로 |
| 아키텍처 결정 근거 | `specs/decisions/` | ADR (Architecture Decision Records) |
| 시스템 구조 다이어그램 | `specs/architecture/` | 컴포넌트, 시퀀스, ERD |
| 코드 패턴 상세 설명 | `docs/patterns/` | 어떻게 구현했는지 |
| 코드 리뷰 기록 | `docs/*/review/` | Phase별 리뷰 노트 |
| 프론트엔드 가이드 | `docs/frontend/` | React 컴포넌트, 상태 관리 |

---

## `docs/`와 `specs/`의 역할 구분

| 디렉토리 | 질문 | 내용 |
|----------|------|------|
| `specs/` | **무엇을, 왜** | 요구사항, 수용 기준, 설계 결정, Phase 범위 |
| `docs/patterns/` | **어떻게** | 구현 패턴 상세, 코드 예시 |
| `docs/*/review/` | **무엇을 배웠나** | 코드 리뷰 노트, 개선 아이디어 |
| `docs/frontend/` | **UI는 어떻게** | 컴포넌트 구조, 상태 관리 가이드 |

---

## 새 스펙 문서 추가 방법

1. `specs/domains/` 또는 `specs/phases/`에 파일 생성
2. `specs/_template.md`를 복사하여 시작
3. 이 README의 해당 테이블 행을 업데이트
