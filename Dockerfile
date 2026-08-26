# ============================================================
# 上课通教学管理系统 - Dockerfile
# 用于免费平台部署（Zeabur / Render / Fly.io / 本地Docker）
# ============================================================
FROM eclipse-temurin:17-jdk-alpine

WORKDIR /app

# 设置中国时区
RUN apk add --no-cache tzdata && \
    cp /usr/share/zoneinfo/Asia/Shanghai /etc/localtime && \
    echo "Asia/Shanghai" > /etc/timezone && \
    apk del tzdata

# 复制JAR包（构建前请先执行 mvn package -DskipTests）
COPY target/skt-server-1.0.0.jar app.jar

# 暴露端口
EXPOSE 8081

# JVM参数（适配免费平台低内存环境）
ENV JAVA_OPTS="-Xms128m -Xmx384m -XX:+UseSerialGC"

# 启动入口
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
