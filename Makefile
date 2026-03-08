JAVA_HOME_17 := $(shell /usr/libexec/java_home -v 17 2>/dev/null || echo "")
export JAVA_HOME := $(JAVA_HOME_17)

.PHONY: help infra infra-all infra-down infra-logs backend build test frontend frontend-build dev clean console asyncapi app \
       setup-gitlab setup-nexus setup-registry setup-jenkins setup-all demo-deploy demo-full

help: ## 사용 가능한 명령어 목록
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) | sort | awk 'BEGIN {FS = ":.*?## "}; {printf "\033[36m%-20s\033[0m %s\n", $$1, $$2}'

# === Infrastructure ===

infra: ## Core 인프라 시작 (Redpanda, PostgreSQL, Console, Connect)
	cd docker && docker compose up -d

infra-all: infra ## 전체 인프라 시작 (Core + Jenkins, GitLab, Nexus, Registry)
	cd docker && docker compose -f docker-compose.infra.yml up -d --build

infra-down: ## 전체 인프라 중지
	cd docker && docker compose down && docker compose -f docker-compose.infra.yml down 2>/dev/null || true

infra-logs: ## 인프라 로그 확인 (실시간)
	cd docker && docker compose logs -f

# === Backend ===

backend: ## Spring Boot 백엔드 실행
	./gradlew bootRun

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
	@echo "  App:          http://localhost:5173"
	@echo "  API:          http://localhost:8080/api/tickets"
	@echo "  Console:      http://localhost:28080"
	@echo "  AsyncAPI:     http://localhost:8080/springwolf/asyncapi-ui.html"
	@echo "  Health:       http://localhost:8080/actuator/health"
	@echo "  Jenkins:      http://localhost:29080 (admin/9615)"

# === Utilities ===

clean: ## 빌드 아티팩트 정리
	./gradlew clean
	rm -rf frontend/dist

console: ## Redpanda Console 열기
	open http://localhost:28080

asyncapi: ## Springwolf AsyncAPI UI 열기
	open http://localhost:8080/springwolf/asyncapi-ui.html

app: ## 프론트엔드 앱 열기
	open http://localhost:5173

# === Middleware Setup ===

setup-gitlab: ## GitLab에 egov-sample + portal-app 프로젝트 등록
	bash docker/scripts/setup-gitlab.sh

setup-nexus: ## Nexus에 egov-sample WAR 업로드
	bash docker/scripts/setup-nexus.sh

setup-registry: ## Registry에 egov-sample 이미지 push
	bash docker/scripts/setup-registry.sh

setup-jenkins: ## Jenkins에 배포 Job 생성
	bash docker/scripts/setup-jenkins.sh

setup-all: setup-gitlab setup-nexus setup-registry setup-jenkins ## 전체 미들웨어 셋업
	@echo "✅ All middleware setup complete"

# === Demo ===

demo-deploy: ## 복합 소스 티켓 생성 + 파이프라인 실행 + 결과 확인
	bash docker/scripts/demo-deploy.sh

demo-full: infra-all setup-all backend ## 풀 데모 (인프라+셋업+백엔드)
	@echo "Frontend: make frontend (별도 터미널)"
	@echo "Deploy: make demo-deploy"
