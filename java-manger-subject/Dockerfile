# 管理后端镜像：多阶段构建，宿主机无需安装 JDK / Maven
# 由根目录 docker-compose.yml 与 deploy.sh / deploy.ps1 调用
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /src

# 先只拷 pom，利用 Docker 层缓存复用依赖下载
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

COPY src ./src
RUN mvn -B -q -DskipTests package

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /src/target/yolo-manager.jar app.jar

EXPOSE 8080
ENV JAVA_OPTS="" \
    TZ=Asia/Shanghai

# 连接与业务配置全部由 compose 通过环境变量注入（Spring Boot relaxed binding）：
#   SPRING_DATASOURCE_URL / SPRING_DATA_REDIS_HOST / YOLO_STORAGE_S3_ENDPOINT / YOLO_TRAIN_SERVICE_BASE_URL ...
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
