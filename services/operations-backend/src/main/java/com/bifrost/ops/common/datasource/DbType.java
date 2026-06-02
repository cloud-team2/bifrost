package com.bifrost.ops.common.datasource;

/**
 * 지원되는 DB 종류.
 * 새로운 DB 추가 시 여기에 enum 추가 → Inspector 구현체 + Debezium connector class 매핑 추가.
 */
public enum DbType {
    POSTGRESQL,
    MARIADB
}
