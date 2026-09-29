FROM maven:3.9.9-eclipse-temurin-11 AS build
WORKDIR /build
COPY server/pom.xml ./pom.xml
RUN mvn -B dependency:go-offline
COPY server/src ./src
RUN mvn -B -DskipTests package
FROM eclipse-temurin:11-jre-jammy
WORKDIR /app
RUN useradd --system --uid 10001 --create-home app && mkdir /data && chown app:app /data
COPY --from=build /build/target/teplotrassa-server-1.9.5-contest-compliance.jar /app/server.jar
USER app
ENV DATA_DIR=/data
EXPOSE 8080
ENTRYPOINT ["java","-Xms256m","-Xmx6g","-jar","/app/server.jar"]
