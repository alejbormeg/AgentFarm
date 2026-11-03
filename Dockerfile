FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml ./
COPY src ./src
COPY examples ./examples
ARG AGENTFARM_COMMIT=unknown
RUN printf %s "$AGENTFARM_COMMIT" > src/main/resources/build-commit.txt
RUN mvn -B -ntp verify
RUN mkdir /native && cd /native     && jar xf /root/.m2/repository/org/xerial/sqlite-jdbc/3.51.1.0/sqlite-jdbc-3.51.1.0.jar     && cp "org/sqlite/native/Linux/$(uname -m)/libsqlitejdbc.so" /native/libsqlitejdbc.so

FROM eclipse-temurin:21-jre-jammy
RUN apt-get update && apt-get install -y --no-install-recommends git curl ca-certificates \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 agentfarm && useradd --uid 10001 --gid agentfarm --no-create-home agentfarm \
    && mkdir /data && chown 10001:10001 /data
WORKDIR /app
COPY LICENSE NOTICE /app/
COPY --from=build /build/target/agentfarm.jar /app/agentfarm.jar
COPY --from=build /native/libsqlitejdbc.so /app/native/libsqlitejdbc.so
USER 10001:10001
ENV AGENTFARM_BIND=0.0.0.0 AGENTFARM_DB=/data/agentfarm.db
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=3s --start-period=40s --retries=6 CMD curl -fsS http://127.0.0.1:8080/api/health >/dev/null || exit 1
ENTRYPOINT ["java","-Dorg.sqlite.lib.path=/app/native","-jar","/app/agentfarm.jar"]
