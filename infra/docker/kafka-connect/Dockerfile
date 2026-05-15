FROM confluentinc/cp-kafka-connect:7.7.1

USER root

# Debezium plugins 디렉토리
RUN mkdir -p /opt/kafka/plugins

# Debezium PostgreSQL Connector
ARG DEBEZIUM_VERSION=2.7.3.Final
RUN curl -sL \
    https://repo1.maven.org/maven2/io/debezium/debezium-connector-postgres/${DEBEZIUM_VERSION}/debezium-connector-postgres-${DEBEZIUM_VERSION}-plugin.tar.gz \
    | tar -xz -C /opt/kafka/plugins

# Debezium MariaDB Connector (2.7+에서 별도 모듈)
RUN curl -sL \
    https://repo1.maven.org/maven2/io/debezium/debezium-connector-mariadb/${DEBEZIUM_VERSION}/debezium-connector-mariadb-${DEBEZIUM_VERSION}-plugin.tar.gz \
    | tar -xz -C /opt/kafka/plugins

# Debezium JDBC Sink Connector (Phase 2: SYNC 용도)
RUN curl -sL \
    https://repo1.maven.org/maven2/io/debezium/debezium-connector-jdbc/${DEBEZIUM_VERSION}/debezium-connector-jdbc-${DEBEZIUM_VERSION}-plugin.tar.gz \
    | tar -xz -C /opt/kafka/plugins

USER appuser
