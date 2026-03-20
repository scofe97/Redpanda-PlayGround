# portal-app

Spring Boot JAR 애플리케이션 샘플. Redpanda Playground 데모용.

## 빌드

mvn clean package

## 실행

docker build -t portal-app:1.0.0 .
docker run -p 8080:8080 portal-app:1.0.0

## 등록 대상

- GitLab: portal-app 프로젝트 (main + develop)
