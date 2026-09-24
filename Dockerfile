FROM eclipse-temurin:25-jre

WORKDIR /app
COPY ./target/srv-nacos-cse-config-bridge-0.1.0-SNAPSHOT.jar /app/app.jar

EXPOSE 8080 9080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
