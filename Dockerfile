FROM maven:3.9.11-eclipse-temurin-21@sha256:6fdc855a6ed81d288ca7ca37ac6ff5e9308b612485c0801d70b25a858c83d237 AS build
WORKDIR /build
COPY pom.xml ./
COPY src ./src
COPY examples ./examples
ARG AGENTFARM_COMMIT=unknown
RUN printf %s "$AGENTFARM_COMMIT" > src/main/resources/build-commit.txt
COPY runtime/mvn-retry.sh /usr/local/bin/mvn-retry
RUN bash /usr/local/bin/mvn-retry -B -ntp -Dspotless.check.skip=true -Dspotbugs.skip=true -Djacoco.skip=true verify
RUN mkdir /native && cd /native     && jar xf /root/.m2/repository/org/xerial/sqlite-jdbc/*/sqlite-jdbc-*.jar     && cp "org/sqlite/native/Linux/$(uname -m)/libsqlitejdbc.so" /native/libsqlitejdbc.so

FROM eclipse-temurin:21-jre-jammy@sha256:1eac8b8fee11ca7d6725a48bc5d41992ed1561504ebae25490d2d062847e8269
# The base image is pinned; its packages get the fixes published since, or the image scan fails.
RUN apt-get update && apt-get upgrade -y --no-install-recommends \
    && apt-get install -y --no-install-recommends git curl ca-certificates \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 agentfarm && useradd --uid 10001 --gid agentfarm --no-create-home agentfarm \
    && install -d -m 700 -o 10001 -g 10001 /data
WORKDIR /app
COPY LICENSE NOTICE /app/
COPY --from=build /build/target/agentfarm.jar /app/agentfarm.jar
COPY --from=build /native/libsqlitejdbc.so /app/native/libsqlitejdbc.so
USER 10001:10001
ENV AGENTFARM_BIND=0.0.0.0 AGENTFARM_DB=/data/agentfarm.db
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=3s --start-period=40s --retries=6 CMD curl -fsS http://127.0.0.1:8080/api/health >/dev/null || exit 1
ENTRYPOINT ["java","-Dorg.sqlite.lib.path=/app/native","-jar","/app/agentfarm.jar"]
