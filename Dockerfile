FROM eclipse-temurin:25-jre

WORKDIR /app

# JDK 25 的压缩对象头可减少每个对象的内存占用。AOT 缓存由 Maven 构建
# （package 阶段的 AOT 训练）生成，这里直接随镜像打包，不再需要多阶段构建。
# 注意：生成缓存的 JDK 必须与这个基础镜像的 JDK 版本严格一致，
# 否则运行时会静默丢弃缓存（-XX:AOTMode=auto）。
ENV JAVA_TOOL_OPTIONS="-XX:+UseCompactObjectHeaders -Xms32m -Xmx256m -XX:AOTMode=auto -XX:AOTCache=/app/app.aot"
COPY ./target/srv-nacos-cse-config-bridge-0.1.0-SNAPSHOT.jar /app/app.jar
COPY ./target/app.aot /app/app.aot
RUN test -s /app/app.aot

EXPOSE 8080 9080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
