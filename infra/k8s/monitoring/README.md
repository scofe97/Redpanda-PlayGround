# LGMT 모니터링 스택 (K8s Helm)

Docker Compose 기반 모니터링을 K8s(kubeadm) + Helm chart로 이관한 구성이다.
상세 이관 가이드는 `infra/docs/01-monitoring/09-k8s-migration.md`를 참조한다.

## 구성

| 서비스 | Chart | 네임스페이스 | 용도 |
|--------|-------|-------------|------|
| Loki | `grafana/loki` | rp-mgm | 로그 저장/쿼리 |
| Tempo | `grafana/tempo` | rp-mgm | 트레이스 저장/쿼리 |
| Prometheus | `prometheus-community/prometheus` | rp-mgm | 메트릭 저장/쿼리/알림 |
| Alloy | `grafana/alloy` | rp-mgm | 통합 수집기 (로그/트레이스/메트릭) |
| Grafana | `grafana/grafana` | rp-mgm | 시각화 대시보드 |

## 빠른 설치

```bash
chmod +x install.sh
./install.sh
```

## ArgoCD 연동

모니터링 스택 전체가 ArgoCD Application으로 등록되어 있다. Git의 values 파일을 수정하고 push하면 ArgoCD에서 Sync할 수 있다.

```bash
# ArgoCD에 등록 (전체 인프라 일괄)
cd infra/argocd && ./install.sh

# 개별 확인
argocd app get loki
argocd app get grafana
```

values 파일 위치:

| 서비스 | Values 경로 |
|--------|------------|
| Loki | `infra/k8s/monitoring/loki/values.yaml` |
| Tempo | `infra/k8s/monitoring/tempo/values.yaml` |
| Prometheus | `infra/k8s/monitoring/prometheus/values.yaml` |
| Alloy | `infra/k8s/monitoring/alloy/values.yaml` |
| Grafana | `infra/k8s/monitoring/grafana/values.yaml` |

## 대시보드 추가

`grafana/dashboards/`에 JSON 파일을 넣고 ConfigMap을 재생성한다.

```bash
kubectl create configmap grafana-dashboards -n rp-mgm \
  --from-file=grafana/dashboards/ \
  --dry-run=client -o yaml | kubectl apply -f -
kubectl rollout restart deployment grafana -n rp-mgm
```
