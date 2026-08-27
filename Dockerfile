# ===== 第一阶段：Maven构建 =====
FROM maven:3.9-eclipse-temurin-17 AS builder
WORKDIR /app

# 复制pom.xml先下载依赖（缓存层）
COPY pom.xml .
RUN mvn dependency:go-offline -B

# 复制源码并打包
COPY src ./src
RUN mvn package -DskipTests -B

# ===== 第二阶段：运行环境 =====
FROM eclipse-temurin:17-jre
WORKDIR /app

# 设置时区
ENV TZ=Asia/Shanghai
RUN ln -snf /usr/share/zoneinfo/$TZ /etc/localtime && echo $TZ > /etc/timezone

# 复制jar包
COPY --from=builder /app/target/skt-server-1.0.0.jar app.jar

# 创建上传目录
RUN mkdir -p /app/upload/courseFile

# Zeabur会自动注入PORT环境变量，默认8081
ENV PORT=8081
EXPOSE 8081

# 启动应用，支持Zeabur的PORT环境变量
ENTRYPOINT ["sh", "-c", "java -jar -Dserver.port=${PORT:-8081} app.jar"]