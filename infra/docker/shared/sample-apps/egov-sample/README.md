# egov-sample

eGov 스타일 WAR 애플리케이션 샘플. Redpanda Playground 데모용.

## 빌드

mvn clean package

## 실행

docker build -t egov-sample:1.0.0 .
docker run -p 8080:8080 egov-sample:1.0.0

## 등록 대상

- GitLab: egov-sample 프로젝트 (main)
- Nexus: com.example:egov-sample:1.0.0:war
- Registry: egov-sample:1.0.0
