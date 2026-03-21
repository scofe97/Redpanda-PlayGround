FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

COPY build/libs/redpanda-playground-*.jar app.jar

EXPOSE 8070

ENTRYPOINT ["java", "-jar", "app.jar"]
