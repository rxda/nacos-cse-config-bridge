FROM eclipse-temurin:25-jre AS aot-training

WORKDIR /app
COPY ./target/srv-nacos-cse-config-bridge-0.1.0-SNAPSHOT.jar /app/app.jar

# JDK 25 records the classes loaded during this short startup run and creates
# a cache for the final image. The application only needs a syntactically valid
# KIE address during training; no external service is contacted.
ENV JAVA_TOOL_OPTIONS="-XX:+UseCompactObjectHeaders -Xms32m -Xmx256m"
ENV CSE_CONFIG_SERVER_ADDR="http://127.0.0.1:30110" \
    CSE_PROJECT="default" \
    CSE_SERVICE_CENTER_ENABLED="false" \
    NACOS_MIGRATION_ENABLED="false"
RUN set -eux; \
    timeout -s TERM 20s java \
      -XX:AOTMode=record \
      -XX:AOTCacheOutput=/app/app.aot \
      -jar /app/app.jar >/tmp/aot-training.log 2>&1 || true; \
    test -s /app/app.aot

FROM eclipse-temurin:25-jre

WORKDIR /app

# JDK 25 compact object headers reduce per-object memory. The AOT cache is
# generated from the exact application JAR and JDK base image above.
ENV JAVA_TOOL_OPTIONS="-XX:+UseCompactObjectHeaders -Xms32m -Xmx256m -XX:AOTMode=auto -XX:AOTCache=/app/app.aot"
COPY --from=aot-training /app/app.jar /app/app.jar
COPY --from=aot-training /app/app.aot /app/app.aot

EXPOSE 8080 9080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
