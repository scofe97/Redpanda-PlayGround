# Redpanda Playground - 요구사항 명세서

## 목적

TPS 트럼본(배포 플랫폼)의 간소화 버전으로, Redpanda 도입에 대한 구조와 주요 정책을 백엔드 팀에게 코드로 설명한다.

## 핵심 도메인

### Ticket (배포 티켓)
- 배포 대상을 정의하는 단위
- 이름, 설명, 상태, 소스 목록을 가짐
- 상태: DRAFT → READY → DEPLOYING → DEPLOYED / FAILED

### TicketSource (배포 소스)
- 티켓에 연결된 배포 소스 (1:N)
- 유형: GIT (소스 빌드), NEXUS (war/jar), HARBOR (Docker 이미지)
- 복합 소스 허용 (GIT + NEXUS 동시 선택 가능)

### Pipeline (배포 파이프라인)
- 티켓의 소스를 기반으로 자동 생성되는 배포 실행 단위
- 소스 유형별 단계(Step) 자동 구성
- 실시간 상태 추적 (SSE)

## 기술 정책

### 이벤트 기반 아키텍처
- 도메인 간 직접 의존 금지 (ticket ↔ pipeline)
- Outbox 패턴으로 메시지 발행 보장
- Consumer 멱등성 (correlationId + eventType 복합 키)

### 토픽 설계
| 토픽 | 용도 |
|------|------|
| playground.ticket | 티켓 생성/변경 이벤트 |
| playground.pipeline | 파이프라인 실행/단계 변경 이벤트 |
| playground.webhook.inbound | 외부 webhook 수신 |
| playground.audit | 감사 로그 |
| playground.dlq | Dead Letter Queue |

### SupportTool (개발지원도구)
- 외부 도구(Jenkins, GitLab, Nexus, Registry) 연결 정보를 DB에 중앙 관리
- 어댑터는 `ToolRegistry`를 통해 런타임에 URL/인증 정보를 동적 조회
- 인증 정보는 Base64 인코딩, API 응답 시 마스킹(`****`)
- 연결 테스트: ToolType별 health check 엔드포인트 호출
- 프론트엔드: `/tools` 페이지에서 CRUD + 연결 테스트

### AsyncAPI
- Springwolf로 이벤트 명세 자동 생성
- UI: http://localhost:8080/springwolf/asyncapi-ui.html
