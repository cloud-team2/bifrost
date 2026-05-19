# K8s / Strimzi KafkaConnect CRD 용 커스텀 이미지
# Strimzi 0.40.0 + Kafka 3.7.1 base
#
# 빌드 후 ECR에 push:
#   make build-connect  (Makefile 참고)
#
# 로컬 개발 환경은 docker-compose.yml의 kafka-connect 서비스를 사용할 것
# (Confluent 이미지 기반, 별도 Dockerfile.local)

FROM quay.io/strimzi/kafka:1.0.0-kafka-4.2.0

USER root:root

ARG DEBEZIUM_VERSION=3.5.1.Final

# Debezium PostgreSQL Connector
RUN mkdir -p /opt/kafka/plugins/debezium-postgres && \
    curl -sL \
      "https://repo1.maven.org/maven2/io/debezium/debezium-connector-postgres/${DEBEZIUM_VERSION}/debezium-connector-postgres-${DEBEZIUM_VERSION}-plugin.tar.gz" \
    | tar -xz -C /opt/kafka/plugins/debezium-postgres

# Debezium MariaDB Connector (2.5+에서 별도 모듈)
RUN mkdir -p /opt/kafka/plugins/debezium-mariadb && \
    curl -sL \
      "https://repo1.maven.org/maven2/io/debezium/debezium-connector-mariadb/${DEBEZIUM_VERSION}/debezium-connector-mariadb-${DEBEZIUM_VERSION}-plugin.tar.gz" \
    | tar -xz -C /opt/kafka/plugins/debezium-mariadb

USER 1001
