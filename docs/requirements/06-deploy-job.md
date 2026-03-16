# 배포 Job

## TPS 원본 — 마인드맵에서 무엇을 하는가

TPS에서 배포 Job은 빌드 산출물을 대상 환경에 배포하는 작업이다. 배포 목적지는 크게 VM(가상 머신)과 K8S(쿠버네티스 클러스터)로 나뉜다.

- **VM 관리**: 원격지(SSH) 접속 + 원격 스크립트 실행으로 배포
- **K8S 관리**: 매니페스트 적용 또는 ArgoCD Sync로 배포

배포 대상 서버/클러스터 정보는 배포 환경(09) 도메인에서 관리하며, 배포 Job은 이를 참조한다. 실제 배포 실행은 CD 미들웨어(ArgoCD 등)에 위임할 수 있다.

---

## PoC 목표 — Playground에서 무엇을 검증하는가

1. **VM/K8S 배포 분기**: 배포 대상 유형에 따라 다른 배포 전략을 실행하는 구조를 구현한다
2. **CD 추상화**: ArgoCD에 한정하지 않고, CdAdapter 인터페이스를 통해 CD 도구를 교체할 수 있는 구조를 만든다
3. **배포 환경 참조**: 배포 Job이 환경(09) 도메인의 서버/클러스터 정보를 참조하여 목적지를 결정하는 연계를 구현한다

---

## 현재 구현 상태

| 항목 | 상태 | 비고 |
|------|------|------|
| Deploy 스텝 실행 | 구현됨 | PipelineEngine 내 고정 스텝 |
| Jenkins 배포 트리거 | 구현됨 | Connect 경유 |
| VM 배포 | 미구현 | K8S 경로 없음, VM도 직접 SSH 없음 |
| K8S 배포 | 미구현 | Jenkins에 위임하는 구조만 |
| CD 추상화 | 미구현 | Jenkins 하드코딩 |

---

## 요구사항

### REQ-06-001: VM/K8S 배포 분기 [P0]

배포 Job 설정에 배포 대상 유형(VM/K8S)을 지정하고, 유형에 따라 다른 배포 전략을 실행한다.

- `deployTarget: VM | K8S` 필드
- VM: SSH 접속 정보 + 스크립트 경로
- K8S: 클러스터 정보 + 매니페스트 또는 ArgoCD Application 이름
- 배포 환경(09) 엔티티의 ID로 대상 참조

### REQ-06-002: CD 어댑터 인터페이스 [P0]

배포 실행을 CD 미들웨어에 위임하는 어댑터 인터페이스를 정의한다.

```
CdAdapter
├── deploy(config) → deploymentId
├── getDeployStatus(deploymentId) → status
├── rollback(deploymentId) → void
└── cancelDeploy(deploymentId) → void
```

- K8S 배포: ArgoCdAdapter (ArgoCD Sync API 호출)
- VM 배포: SshDeployAdapter (SSH 원격 스크립트 실행) 또는 Jenkins Job 위임
- 프리셋에서 CD 도구를 해석하여 적절한 어댑터 선택

### REQ-06-003: 배포 설정 구조 [P1]

배포 Job의 config JSON 스키마를 정의한다.

**VM 배포:**
```json
{
  "deployTarget": "VM",
  "environmentId": "env-prod-vm-01",
  "deploy": {
    "script": "/opt/deploy/run.sh",
    "artifactPath": "/opt/deploy/artifacts/",
    "healthCheckUrl": "http://localhost:8080/actuator/health",
    "healthCheckTimeout": 60
  }
}
```

**K8S 배포:**
```json
{
  "deployTarget": "K8S",
  "environmentId": "env-prod-k8s-01",
  "deploy": {
    "method": "ARGOCD_SYNC",
    "applicationName": "team-a-app",
    "namespace": "production",
    "imageOverride": "${BUILD_OUTPUT_IMAGE}"
  }
}
```

- `${BUILD_OUTPUT_IMAGE}`: 빌드 Job 결과의 이미지 URL 자동 주입
- K8S method: `ARGOCD_SYNC` (ArgoCD) / `KUBECTL_APPLY` (직접 매니페스트 적용)

### REQ-06-004: 배포 롤백 [P1]

배포 실패 또는 수동 롤백 시 이전 버전으로 되돌리는 기능을 제공한다.

- CdAdapter.rollback()으로 이전 배포 상태 복원
- ArgoCD: 이전 Sync 리비전으로 Sync
- VM: 이전 배포 스냅샷의 스크립트 재실행
- 파이프라인 SAGA 보상 작업에 롤백 연계

### REQ-06-005: 배포 상태 모니터링 [P1]

배포 진행 중 상태를 추적하고 SSE로 전달한다.

- CdAdapter.getDeployStatus() 주기적 폴링 (또는 이벤트 기반)
- 배포 상태: PENDING → DEPLOYING → HEALTH_CHECK → DEPLOYED/FAILED
- 헬스 체크 통과 여부 포함 (URL 기반 HTTP 200 확인)
- SSE로 배포 진행률 전달

### REQ-06-006: 배포 이력 [P2]

배포 실행 이력을 관리한다.

- 배포 대상 환경, 배포 시간, 소요 시간, 결과
- 배포된 산출물 정보 (이미지 태그, JAR 버전)
- 롤백 이력 포함

---

## 설계 고민 포인트

### VM 배포의 SSH 실행 vs Jenkins 위임

VM 배포를 Spring Boot가 직접 SSH 실행할지, Jenkins Job에 위임할지는 트레이드오프가 있다.

- **SSH 직접**: Spring Boot → JSch/SSHJ로 원격 스크립트 실행. 단순하지만, SSH 키 관리/접속 풀링/타임아웃 처리를 직접 구현해야 한다
- **Jenkins 위임**: Jenkins가 SSH Agent를 통해 실행. Jenkins의 인프라를 활용하지만, 모든 VM 배포가 Jenkins를 거쳐야 한다

PoC에서는 Jenkins 위임으로 시작하되(기존 Connect 경유 구조 활용), CdAdapter 인터페이스 뒤에 숨겨서 추후 SSH 직접 실행으로 교체할 수 있게 한다.

### K8S 배포에서 ArgoCD vs kubectl 직접 적용

ArgoCD가 있으면 GitOps 방식으로 ArgoCD Sync를 호출하는 것이 깔끔하다. 하지만 ArgoCD가 없는 환경에서는 kubectl apply를 직접 실행해야 한다. 두 경로를 모두 지원하되, K8S method 설정으로 분기한다. ArgoCD Sync는 REST API 호출이므로 CdAdapter에 자연스럽게 들어가고, kubectl apply는 Spring Boot에서 ProcessBuilder로 실행하거나 Kubernetes Java Client를 사용한다.

### 빌드 결과물의 배포 Job 자동 주입

빌드 Job이 완료되면 buildOutput(이미지 URL, 다이제스트)이 파이프라인 실행 컨텍스트에 저장된다. 배포 Job의 config에서 `${BUILD_OUTPUT_IMAGE}` 같은 변수를 사용하면, 파이프라인 엔진이 실행 시점에 buildOutput 값으로 치환한다. 이 메커니즘은 03-pipeline의 REQ-03-006(실행 파라미터)과 동일한 변수 치환 엔진으로 구현할 수 있다.
