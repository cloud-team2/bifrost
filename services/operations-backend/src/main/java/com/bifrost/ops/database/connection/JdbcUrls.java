package com.bifrost.ops.database.connection;

import com.bifrost.ops.global.common.datasource.DbType;

/**
 * 엔진별 JDBC URL 빌더(단일 출처). 연결 테스트(#26)·스키마 조회(#28)·CDC 준비도(#29)가 공유한다.
 *
 * <p>새 엔진 추가 시 {@link DbType}에 enum을 더하고 여기 switch에 URL 패턴을 추가한다
 * (database-registry.md §5).
 */
public final class JdbcUrls {

    /**
     * 소켓 connect 단계 상한(#560). 도달 불가 host:port에서 OS 기본 SYN 재전송(~11s) 대기를 막아
     * 연결 테스트가 설정된 컷(5초) 안에 실패하도록 한다. HikariCP {@code connectionTimeout}은 풀 대기
     * 시간일 뿐 물리 connect를 제한하지 못하므로 드라이버 파라미터로 직접 건다. 단일 read를 끊는
     * {@code socketTimeout}은 스키마 조회·CDC 준비도 등 공유 경로의 정상 쿼리를 깰 수 있어 제외한다.
     *
     * <p><b>단위 주의</b>: PostgreSQL JDBC {@code connectTimeout}은 <b>초</b>, MariaDB는 <b>밀리초</b>.
     */
    private static final int CONNECT_TIMEOUT_SEC = 5;

    private JdbcUrls() {
    }

    public static String build(DbType type, String host, int port, String dbName) {
        return switch (type) {
            case POSTGRESQL -> "jdbc:postgresql://" + host + ":" + port + "/" + dbName
                    + "?connectTimeout=" + CONNECT_TIMEOUT_SEC;
            case MARIADB -> "jdbc:mariadb://" + host + ":" + port + "/" + dbName
                    + "?connectTimeout=" + (CONNECT_TIMEOUT_SEC * 1000);
        };
    }
}
