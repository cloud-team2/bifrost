package com.bifrost.ops.database.connection;

import com.bifrost.ops.global.common.datasource.DbType;

/**
 * 엔진별 JDBC URL 빌더(단일 출처). 연결 테스트(#26)·스키마 조회(#28)·CDC 준비도(#29)가 공유한다.
 *
 * <p>새 엔진 추가 시 {@link DbType}에 enum을 더하고 여기 switch에 URL 패턴을 추가한다
 * (database-registry.md §5).
 */
public final class JdbcUrls {

    private JdbcUrls() {
    }

    public static String build(DbType type, String host, int port, String dbName) {
        return switch (type) {
            case POSTGRESQL -> "jdbc:postgresql://" + host + ":" + port + "/" + dbName;
            case MARIADB -> "jdbc:mariadb://" + host + ":" + port + "/" + dbName;
        };
    }
}
