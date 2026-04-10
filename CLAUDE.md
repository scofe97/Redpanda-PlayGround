# Redpanda Playground

TPS CI/CD 이벤트 기반 아키텍처 PoC. GitLab/Jenkins 웹훅을 Redpanda Connect로 수집하고, Spring Boot가 SAGA Choreography로 파이프라인을 오케스트레이션한다.

## 기술 스택

| 계층 | 기술 | 버전 |
|------|------|------|
| Backend | Spring Boot | 3.4.0 |
| Language | Java | 21 (Temurin) |
| Frontend | React | 19 |
| Messaging | Redpanda (Kafka API) + Avro + Schema Registry | v24.3.1 |
| Integration | Redpanda Connect (streams) | 4.43.0 |
| DB | PostgreSQL + Flyway + MyBatis | - |
| CI/CD | Jenkins (K8s Helm) + GitLab | - |
| Observability | Grafana + Loki + Tempo + Prometheus + Alloy | - |

## 코딩 원칙 (최우선)

1. **코딩 전에 생각하기** -- 구현 전 접근 방식을 완전히 설명할 수 있어야 한다. 불확실하면 멈추고 질문.
2. **단순함 우선** -- 요청된 것만 구현, 그 이상 금지. 미래 요구사항을 위한 설계 금지.
3. **외과적 변경** -- 요청 범위의 코드만 수정. diff 최소화가 목표.

## 프로필

| 프로필 | 용도 | 실행 |
|--------|------|------|
| `local` | 로컬 Docker Compose | `make backend-local` |
| `gcp` | GCP K8s (NodePort) | `make backend` (기본) |

## Makefile 핵심 명령어

| 명령어 | 설명 |
|--------|------|
| `make backend` | Spring Boot 실행 (GCP 프로필) |
| `make executor` | Executor 서비스 실행 (GCP 프로필) |
| `make operator` | Operator 실행 (GCP 프로필) |
| `make build` | 빌드 (테스트 제외) |
| `make test` | 테스트 실행 |
| `make test-e2e tc01` | E2E 개별 TC 실행 |
| `make frontend` | React dev server |

## 모듈 구조

```
common → common-kafka → operator / executor
```

| 모듈 | 역할 |
|------|------|
| `common` | 공통 DTO, 예외, MyBatis 설정, 멱등성 |
| `common-kafka` | Kafka 설정, Avro 직렬화, Outbox, DLQ, 트레이싱 |
| `operator` | 파이프라인 정의/실행, REST API, 도메인 오케스트레이션 |
| `executor` | Job 실행 제어, Jenkins 연동, Hexagonal Architecture |

## 문서 참조

| 영역 | 위치 |
|------|------|
| 도메인 스펙 (무엇을, 왜) | `specs/domains/` |
| 아키텍처 스펙 | `specs/architecture/` |
| Phase 실행 계획 | `specs/phases/` |
| ADR | `specs/decisions/` |
| 패턴 카탈로그 (어떻게) | `docs/patterns/` |
| 인프라 상세 | `.claude/skills/infra/SKILL.md` |
| 파이프라인 상세 | `.claude/skills/pipeline/SKILL.md` |

## 핵심 개념 -- 3단 연계

```
티켓(업무코드) → 워크플로우(컴포넌트 조합) → 파이프라인(Job 실행 체인)
```

- 파이프라인은 독립 엔티티 (티켓 없이 단독 실행 가능)
- Job은 독립 엔티티 -- 단독 실행 가능, DAG에서 비로소 순서 결정
- 구현 전 해당 도메인 스펙의 **수용 기준**을 확인한다

## Skill routing

When the user's request matches an available skill, ALWAYS invoke it using the Skill
tool as your FIRST action. Do NOT answer directly, do NOT use other tools first.
The skill has specialized workflows that produce better results than ad-hoc answers.

Key routing rules:
- Product ideas, "is this worth building", brainstorming → invoke gstack-office-hours
- Bugs, errors, "why is this broken", 500 errors → invoke gstack-investigate
- Ship, deploy, push, create PR → invoke gstack-ship
- QA, test the site, find bugs → invoke gstack-qa
- Code review, check my diff → invoke gstack-review
- Update docs after shipping → invoke gstack-document-release
- Weekly retro → invoke gstack-retro
- Design system, brand → invoke gstack-design-consultation
- Visual audit, design polish → invoke gstack-design-review
- Architecture review → invoke gstack-plan-eng-review
- Save progress, checkpoint, resume → invoke gstack-checkpoint
- Code quality, health check → invoke gstack-health

## 실수 개선 프로세스 (필수)

1. **원인 분석**: 왜 실수가 발생했는지 분석
2. **즉시 수정**: 잘못된 부분 바로 수정
3. **재발 방지**: `.claude/rules/`에 규칙 추가 또는 기존 규칙 보완
