JAVA_HOME_21 := $(shell /usr/libexec/java_home -v 21 2>/dev/null || echo "")
export JAVA_HOME := $(JAVA_HOME_21)

.PHONY: help backend executor operator \
       build test test-e2e clean frontend frontend-build app \
       check tc01 tc02 tc03 tc05 tc06 tc07 tc08 tc09 tc10 auto pipeline all

help: ## 사용 가능한 명령어 목록
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) | sort | awk 'BEGIN {FS = ":.*?## "}; {printf "\033[36m%-20s\033[0m %s\n", $$1, $$2}'

# === Backend ===

backend: ## Spring Boot 백엔드 실행 (GCP 프로필)
	SPRING_PROFILES_ACTIVE=gcp ./gradlew :operator:bootRun

executor: ## Executor 서비스 실행 (GCP 프로필)
	SPRING_PROFILES_ACTIVE=gcp ./gradlew :executor:bootRun

operator: ## Operator 실행 (GCP 프로필)
	SPRING_PROFILES_ACTIVE=gcp ./gradlew :operator:bootRun

# === Build & Test ===

build: ## 백엔드 빌드 (테스트 제외)
	./gradlew clean build -x test

test: ## 백엔드 테스트 실행
	./gradlew test

test-e2e: ## E2E 테스트 (auto: tc01,tc06,tc07 / pipeline: tc08,tc09 / all: 전체 / tc01~tc10: 개별)
	@bash scripts/e2e/run.sh $(filter-out $@,$(MAKECMDGOALS))

# make test-e2e check 형태를 지원하기 위한 더미 타깃
check tc01 tc02 tc03 tc05 tc06 tc07 tc08 tc09 tc10 auto pipeline all:
	@:

clean: ## 빌드 아티팩트 정리
	./gradlew clean
	rm -rf frontend/dist

# === Frontend ===

frontend: ## React 프론트엔드 개발 서버 실행
	cd frontend && yarn dev

frontend-build: ## 프론트엔드 프로덕션 빌드
	cd frontend && yarn build

app: ## 프론트엔드 앱 열기
	open http://localhost:5170
