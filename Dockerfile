FROM eclipse-temurin:25-jre AS aot-training

WORKDIR /app
COPY ./target/srv-nacos-cse-config-bridge-0.1.0-SNAPSHOT.jar /app/app.jar

# JDK 25 会记录这次短暂启动运行中加载的类，并为最终镜像生成缓存。
# 训练期间应用只需要一个语法合法的 KIE 地址，不会连接任何外部服务。
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

# JDK 25 的压缩对象头可减少每个对象的内存占用。AOT 缓存
# 由上面的应用 JAR 和 JDK 基础镜像精确生成。
ENV JAVA_TOOL_OPTIONS="-XX:+UseCompactObjectHeaders -Xms32m -Xmx256m -XX:AOTMode=auto -XX:AOTCache=/app/app.aot"
COPY --from=aot-training /app/app.jar /app/app.jar
COPY --from=aot-training /app/app.aot /app/app.aot

EXPOSE 8080 9080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
