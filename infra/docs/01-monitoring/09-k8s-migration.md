# LGMT 모니터링 스택 K8s 이관 가이드

Docker Compose(Server 3) 기반 모니터링을 K8s 클러스터의 `rp-mgm` 네임스페이스로 이관하는 절차를 다룬다.

## 배경

Server 3(dev-server-3, 10.178.0.4)에 집중 배치된 Loki/Tempo/Prometheus/Grafana/Alloy를 kubeadm 클러스터(3노드)로 옮겨 ArgoCD 기반 GitOps로 관리한다. 커뮤니티 Helm chart에 values.yaml 오버라이드 방식을 채택하여 유지보수 부담을 줄였다.

## 사전 조건

```bash
# Helm repo 등록
helm repo add grafana https://grafana.github.io/helm-charts
helm repo add prometheus-community https://prometheus-community.github.io/helm-charts
helm repo update

# rp-mgm 네임스페이스 확인 (없으면 생성)
kubectl get ns rp-mgm || kubectl create ns rp-mgm

# local-path-provisioner 동작 확인 (PVC 동적 프로비저닝)
kubectl get pods -n local-path-storage
```

## 설치 순서

Alloy가 Loki/Tempo/Prometheus에 의존하므로 순서가 중요하다.

```
Loki → Tempo → Prometheus → Alloy → Grafana
```

### 자동 설치

```bash
cd infra/k8s/monitoring
chmod +x install.sh
./install.sh
```

### 수동 설치

```bash
NS=rp-mgm

# 1. Loki
helm install loki grafana/loki -n $NS -f loki/values.yaml --wait

# 2. Tempo
helm install tempo grafana/tempo -n $NS -f tempo/values.yaml --wait

# 3. Prometheus
helm install prometheus prometheus-community/prometheus -n $NS -f prometheus/values.yaml --wait

# 4. Alloy
helm install alloy grafana/alloy -n $NS -f alloy/values.yaml --wait

# 5. Grafana 대시보드 ConfigMap
kubectl create configmap grafana-dashboards -n $NS \
  --from-file=grafana/dashboards/ \
  --dry-run=client -o yaml | kubectl apply -f -

# 6. Grafana
helm install grafana grafana/grafana -n $NS -f grafana/values.yaml --wait
```

## Docker → K8s 변환 포인트

이관 시 변경된 핵심 설정을 정리한다.

### 1. 로그 수집: Docker Socket → K8s Pod Discovery

Docker 환경에서 `/var/run/docker.sock`으로 컨테이너 로그를 수집하던 방식을 K8s Pod 자동 발견으로 변경했다.

| 항목 | Docker | K8s |
|------|--------|-----|
| 수집 방식 | `loki.source.docker` (docker.sock) | `loki.source.kubernetes` (kubelet API) |
| 대상 필터 | 컨테이너명 `/playground-*` | 네임스페이스 `rp-app`, `rp-oss`, `rp-jenkins` |
| 라벨 | `container` (컨테이너명) | `container`, `namespace`, `app` |

### 2. 서비스 디스커버리: 호스트명 → K8s Service DNS

| Docker 호스트명 | K8s Service DNS |
|----------------|----------------|
| `loki:3100` | `loki.rp-mgm.svc.cluster.local:3100` |
| `tempo:3200` | `tempo.rp-mgm.svc.cluster.local:3200` |
| `tempo:4318` | `tempo.rp-mgm.svc.cluster.local:4318` |
| `prometheus:9090` | `prometheus-server.rp-mgm.svc.cluster.local:80` |

### 3. 메트릭 스크래핑: 고정 IP → K8s Service Discovery

Docker에서 `host.docker.internal:8080`, `redpanda:9644` 같은 고정 주소를 사용하던 것을 K8s Service DNS로 변경했다.

| 타겟 | Docker | K8s |
|------|--------|-----|
| Spring Boot | `host.docker.internal:8080` | `prometheus.io/scrape` 어노테이션 기반 SD |
| Redpanda | `redpanda:9644` | `redpanda.rp-oss.svc.cluster.local:9644` |
| Connect command | `connect:4195` | `connect.rp-oss.svc.cluster.local:4195` |
| Connect webhook | `connect:4198` | `connect.rp-oss.svc.cluster.local:4198` |

### 4. 외부 접근: 포트 매핑 → NodePort

| 서비스 | Docker 포트 | K8s NodePort |
|--------|-----------|-------------|
| Grafana | 23000:3000 | 30000 |
| Alloy OTLP gRPC | 4317:4317 | 30317 |
| Alloy OTLP HTTP | 4318:4318 | 30318 |
| Alloy Loki push | — | 30100 |

로컬 Spring Boot에서 OTLP 전송 시 엔드포인트를 변경해야 한다.

```properties
# Docker 환경
otel.exporter.otlp.endpoint=http://34.22.78.240:4317

# K8s 환경
otel.exporter.otlp.endpoint=http://34.22.78.240:30317
```

### 5. 스토리지: Docker Volume → local-path PVC

| 서비스 | PVC 크기 | StorageClass |
|--------|---------|-------------|
| Loki | 5Gi | local-path |
| Tempo | 5Gi | local-path |
| Prometheus | 5Gi | local-path |
| Grafana | 1Gi | local-path |

### 6. Alert Rules: Docker 기준 → K8s 기준

`TempoHighMemory` alert의 메트릭을 Docker 컨테이너 메트릭에서 K8s 컨테이너 메트릭으로 변경했다.

```yaml
# Docker
container_memory_usage_bytes{name="playground-tempo"}

# K8s
container_memory_working_set_bytes{container="tempo"}
```

## 리소스 할당

3노드 클러스터(각 4vCPU/8GB)에 rp-app, rp-oss, rp-jenkins가 이미 배포되어 있으므로 모니터링 스택의 리소스를 최소화했다.

| 서비스 | Request | Limit |
|--------|---------|-------|
| Loki | 100m / 256Mi | — / 512Mi |
| Tempo | 100m / 512Mi | — / 1Gi |
| Prometheus | 100m / 256Mi | — / 512Mi |
| Grafana | 50m / 128Mi | — / 256Mi |
| Alloy | 50m / 128Mi | — / 256Mi |
| **합계** | **400m / 1.3Gi** | **— / 2.5Gi** |

## 검증

### Pod 상태 확인

```bash
kubectl get pods -n rp-mgm
# 기대: loki, tempo, prometheus-server, alloy, grafana 모두 Running
```

### Grafana 접속

```
http://34.22.78.240:30000
```

익명 인증(Admin 권한)으로 로그인 없이 접근 가능하다.

### 데이터소스 검증

Grafana > Configuration > Data Sources에서 3개 데이터소스가 정상 연결되는지 확인한다.

- Loki: `http://loki.rp-mgm.svc.cluster.local:3100`
- Tempo: `http://tempo.rp-mgm.svc.cluster.local:3200`
- Prometheus: `http://prometheus-server.rp-mgm.svc.cluster.local:80`

### 로그 수집 검증

```bash
# rp-app Pod 로그가 Loki에 도착하는지 확인
# Grafana > Explore > Loki > {namespace="rp-app"}
```

### 트레이스 검증

Spring Boot 앱에서 API 호출 후 Grafana > Explore > Tempo에서 트레이스를 조회한다.

### 메트릭/대시보드 검증

Grafana > Dashboards에서 기존 4개 대시보드가 로드되고 데이터가 표시되는지 확인한다.

- Spring Boot App
- Redpanda Overview
- Connect Pipelines
- System Health

### Alert Rules 검증

```bash
# Prometheus UI에서 rules 확인
kubectl port-forward svc/prometheus-server -n rp-mgm 9090:80
# http://localhost:9090/rules
```

## 트러블슈팅

### Pod이 Pending 상태

```bash
kubectl describe pod <pod-name> -n rp-mgm
# PVC가 Bound되지 않으면 local-path-provisioner 확인
kubectl get pods -n local-path-storage
kubectl logs -n local-path-storage -l app=local-path-provisioner
```

### Alloy가 Loki/Tempo에 연결 실패

```bash
# Alloy 로그 확인
kubectl logs -n rp-mgm -l app.kubernetes.io/name=alloy

# DNS 해석 확인
kubectl run tmp --rm -i --restart=Never --image=busybox -- \
  nslookup loki.rp-mgm.svc.cluster.local
```

### Grafana 대시보드가 비어 있음

```bash
# ConfigMap 확인
kubectl get configmap grafana-dashboards -n rp-mgm -o yaml | head -20

# Grafana Pod 재시작 (ConfigMap 변경 반영)
kubectl rollout restart deployment grafana -n rp-mgm
```

### 메트릭이 수집되지 않음

Spring Boot Service에 `prometheus.io/scrape: "true"` 어노테이션이 있는지 확인한다.

```bash
kubectl get svc -n rp-app -o yaml | grep -A2 "prometheus.io"
```

어노테이션이 없으면 Service에 추가한다.

```yaml
metadata:
  annotations:
    prometheus.io/scrape: "true"
    prometheus.io/path: "/actuator/prometheus"
    prometheus.io/port: "8080"
```

## Docker 환경 정리

K8s 이관 후 Docker 환경을 정리하려면 Server 3에서 모니터링 컨테이너를 중지한다.

```bash
ssh dev-server-3 "cd ~/deploy && docker compose down"
```

단, 다른 서버의 Alloy sidecar가 Server 3로 연결하고 있다면 먼저 sidecar 설정을 K8s Alloy NodePort(30317/30318)로 변경해야 한다.
