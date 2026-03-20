JAVA_HOME_21 := $(shell /usr/libexec/java_home -v 21 2>/dev/null || echo "")
export JAVA_HOME := $(JAVA_HOME_21)

.PHONY: help infra db nexus infra-all infra-down infra-logs backend build test frontend frontend-build dev clean console asyncapi app \
       setup-gitlab setup-nexus setup-registry setup-jenkins setup-all demo-deploy demo-full \
       monitoring monitoring-down grafana

help: ## 사용 가능한 명령어 목록
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) | sort | awk 'BEGIN {FS = ":.*?## "}; {printf "\033[36m%-20s\033[0m %s\n", $$1, $$2}'

# === Infrastructure ===

infra: ## Core 인프라 시작 (Redpanda, Console, Connect — 네트워크 생성)
	cd infra/docker/local && docker compose up -d

db: ## PostgreSQL 시작
	cd infra/docker/local && docker compose -f docker-compose.db.yml up -d

nexus: ## Nexus + Registry 시작 (로컬)
	cd infra/docker/local && docker compose -f docker-compose.nexus.yml up -d

infra-all: infra db nexus ## 로컬 인프라 전체 시작 (Jenkins/GitLab은 GCP)

infra-down: ## 로컬 인프라 전체 중지 (Jenkins/GitLab은 GCP에서 직접 관리)
	cd infra/docker/local && docker compose down \
		&& docker compose -f docker-compose.db.yml down 2>/dev/null || true \
		&& docker compose -f docker-compose.nexus.yml down 2>/dev/null || true \
		&& docker compose -f docker-compose.monitoring.yml down 2>/dev/null || true

infra-logs: ## 인프라 로그 확인 (실시간)
	cd infra/docker/local && docker compose logs -f

# === Backend ===

backend: ## Spring Boot 백엔드 실행 (GCP 프로필 기본 — 로컬은 make backend-local)
	SPRING_PROFILES_ACTIVE=gcp ./gradlew :app:bootRun

backend-local: ## Spring Boot 백엔드 실행 (로컬 DB/Redpanda)
	./gradlew :app:bootRun

build: ## 백엔드 빌드 (테스트 제외)
	./gradlew clean build -x test

test: ## 백엔드 테스트 실행
	./gradlew test

# === Frontend ===

frontend: ## React 프론트엔드 개발 서버 실행
	cd frontend && yarn dev

frontend-build: ## 프론트엔드 프로덕션 빌드
	cd frontend && yarn build

# === Combined ===

dev: ## 개발 환경 실행 안내
	@echo "=== Redpanda Playground 개발 환경 ==="
	@echo ""
	@echo "  Terminal 1: make infra     (인프라 시작)"
	@echo "  Terminal 2: make backend   (백엔드 시작)"
	@echo "  Terminal 3: make frontend  (프론트엔드 시작)"
	@echo ""
	@echo "=== 접속 URL ==="
	@echo "  App:          http://localhost:5170"
	@echo "  API:          http://localhost:8080/api/tickets"
	@echo "  Console:      http://localhost:28080"
	@echo "  AsyncAPI:     http://localhost:8080/springwolf/asyncapi-ui.html"
	@echo "  Health:       http://localhost:8080/actuator/health"
	@echo "  Jenkins:      http://34.47.83.38:29080 (GCP, admin/admin)"
	@echo "  Grafana:      http://localhost:23000"
	@echo "  Prometheus:   http://localhost:29090"

# === Utilities ===

clean: ## 빌드 아티팩트 정리
	./gradlew clean
	rm -rf frontend/dist

console: ## Redpanda Console 열기
	open http://localhost:28080

asyncapi: ## Springwolf AsyncAPI UI 열기
	open http://localhost:8080/springwolf/asyncapi-ui.html

app: ## 프론트엔드 앱 열기
	open http://localhost:5170

# === Monitoring ===

monitoring: ## 모니터링 스택 시작 (Grafana, Loki, Tempo, Alloy, Prometheus)
	cd infra/docker/local && docker compose -f docker-compose.monitoring.yml up -d

monitoring-down: ## 모니터링 스택 중지
	cd infra/docker/local && docker compose -f docker-compose.monitoring.yml down

grafana: ## Grafana 열기
	open http://localhost:23000

# === Middleware Setup ===

setup-gitlab: ## GitLab에 egov-sample + portal-app 프로젝트 등록
	bash infra/docker/shared/scripts/setup-gitlab.sh

setup-nexus: ## Nexus에 egov-sample WAR 업로드
	bash infra/docker/shared/scripts/setup-nexus.sh

setup-registry: ## Registry에 egov-sample 이미지 push
	bash infra/docker/shared/scripts/setup-registry.sh

setup-jenkins: ## Jenkins에 배포 Job 생성
	bash infra/docker/shared/scripts/setup-jenkins.sh

setup-all: setup-gitlab setup-nexus setup-registry setup-jenkins ## 전체 미들웨어 셋업
	@echo "✅ All middleware setup complete"

# === Demo ===

demo-deploy: ## 복합 소스 티켓 생성 + 파이프라인 실행 + 결과 확인
	bash infra/docker/shared/scripts/demo-deploy.sh

demo-full: infra-all setup-all backend ## 풀 데모 (인프라+셋업+백엔드)
	@echo "Frontend: make frontend (별도 터미널)"
	@echo "Deploy: make demo-deploy"
