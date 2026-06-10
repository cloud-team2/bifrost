# 커스텀 Kafka Connect 이미지 (#425)
#
# Strimzi KafkaConnect 의 spec.image 가 참조하는 단일 "권위" 이미지.
# (과거 spec.build/Kaniko in-cluster 빌드는 plugin artifact를 URL로만 받을 수 있어
#  private repo의 timestamptz 커스텀 컨버터 JAR을 넣을 수 없었다 → spec.image로 일원화, #425)
#
#   base    : quay.io/strimzi/kafka:1.0.0-kafka-4.2.0  (Strimzi 1.0.0 / Kafka 4.2.0)
#   plugins : debezium-postgres, debezium-mariadb, Confluent JDBC sink + JDBC 드라이버
#   converter: timestamptz → Connect Timestamp  (connect-plugins/timestamptz-converter, #425)
#
# 빌드 컨텍스트 = 레포 루트 (멀티모듈 Gradle로 컨버터 JAR을 함께 빌드):
#   docker build -f infra/docker/kafka-connect/Dockerfile \
#     -t harbor.harbor.svc.cluster.local/library/bifrost-kafka-connect:1.0.0-converter .
# CI(Jenkinsfile)는 Kaniko로 동일 컨텍스트/Dockerfile을 빌드해 Harbor에 push한다.
#
# 플러그인 버전은 infra/k8s/kafka/kafka-connect.yaml(과거 spec.build)과 정합을 맞춘다.

# ── Stage 1: 플러그인 수집 + 커스텀 컨버터 JAR 빌드 ─────────────────────────────
# alpine 빌더에만 curl/unzip/tar를 두고, 런타임(strimzi) 스테이지는 산출물만 COPY 한다.
FROM eclipse-temurin:21-jdk-alpine AS builder
RUN apk add --no-cache curl tar unzip
WORKDIR /app

ARG DEBEZIUM_VERSION=3.5.1.Final
ARG JDBC_SINK_VERSION=10.9.4
# postgres 드라이버는 Confluent JDBC sink zip에 번들됨(42.7.x) → 별도로 받지 않는다(중복 시 classpath 충돌).
# mariadb 드라이버는 zip에 미번들 → 직접 추가한다.
ARG MARIADB_DRIVER_VERSION=3.4.1

# 1) 커스텀 컨버터 JAR 빌드 (#425). 멀티모듈이라 settings.gradle이 참조하는 모든 모듈 디렉토리가 필요.
COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle
COPY connect-plugins connect-plugins
RUN ./gradlew :connect-plugins:timestamptz-converter:jar --no-daemon

# 2) 플러그인 수집 → /plugins/<name>/ (Connect plugin.path 레이아웃)
RUN set -eux; \
    mkdir -p /plugins/debezium-postgres /plugins/debezium-mariadb /plugins/jdbc-sink; \
    # Debezium PostgreSQL / MariaDB 커넥터
    curl -sL "https://repo1.maven.org/maven2/io/debezium/debezium-connector-postgres/${DEBEZIUM_VERSION}/debezium-connector-postgres-${DEBEZIUM_VERSION}-plugin.tar.gz" \
      | tar -xz -C /plugins/debezium-postgres; \
    curl -sL "https://repo1.maven.org/maven2/io/debezium/debezium-connector-mariadb/${DEBEZIUM_VERSION}/debezium-connector-mariadb-${DEBEZIUM_VERSION}-plugin.tar.gz" \
      | tar -xz -C /plugins/debezium-mariadb; \
    # Confluent JDBC Sink (CDC direct). 10.7.4는 Kafka 4.x에서 제거된 Utils.mkSet 호출로 실패(#419) → 10.9.4
    curl -sL -o /tmp/jdbc-sink.zip "https://hub-downloads.confluent.io/api/plugins/confluentinc/kafka-connect-jdbc/versions/${JDBC_SINK_VERSION}/confluentinc-kafka-connect-jdbc-${JDBC_SINK_VERSION}.zip"; \
    unzip -q /tmp/jdbc-sink.zip -d /plugins/jdbc-sink; \
    rm /tmp/jdbc-sink.zip; \
    # mariadb 드라이버(zip 미번들)는 connector와 같은 plugin sandbox(lib 디렉토리)에 둬야 classpath에 잡힌다.
    JDBC_LIB="$(dirname "$(find /plugins/jdbc-sink -name 'kafka-connect-jdbc-*.jar' | head -1)")"; \
    curl -sL -o "${JDBC_LIB}/mariadb-java-client-${MARIADB_DRIVER_VERSION}.jar" \
      "https://repo1.maven.org/maven2/org/mariadb/jdbc/mariadb-java-client/${MARIADB_DRIVER_VERSION}/mariadb-java-client-${MARIADB_DRIVER_VERSION}.jar"; \
    # timestamptz 커스텀 컨버터(#425)는 Debezium Postgres 커넥터와 같은 plugin 클래스로더에서
    # 보여야 하므로 debezium-postgres plugin 디렉토리에 동봉한다.
    cp connect-plugins/timestamptz-converter/build/libs/bifrost-timestamptz-converter-*.jar /plugins/debezium-postgres/

# ── Stage 2: Connect 런타임 ───────────────────────────────────────────────────
FROM quay.io/strimzi/kafka:1.0.0-kafka-4.2.0

USER root:root
COPY --from=builder /plugins/ /opt/kafka/plugins/
USER 1001
