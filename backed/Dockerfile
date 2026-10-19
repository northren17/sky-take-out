FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml .
COPY sky-common/pom.xml sky-common/pom.xml
COPY sky-pojo/pom.xml sky-pojo/pom.xml
COPY sky-server/pom.xml sky-server/pom.xml
COPY sky-common/src sky-common/src
COPY sky-pojo/src sky-pojo/src
COPY sky-server/src sky-server/src
RUN mvn -B -pl sky-server -am -DskipTests package

FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
RUN groupadd --system --gid 10001 sky \
    && useradd --system --uid 10001 --gid sky --home-dir /app sky
COPY --from=build --chown=sky:sky /workspace/sky-server/target/sky-server-1.0-SNAPSHOT.jar /app/sky-server.jar
USER sky
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/sky-server.jar"]
