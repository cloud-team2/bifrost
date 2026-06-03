package com.bifrost.ops.database.connection;

/**
 * 연결 테스트 실패 분류(단일 출처). database-registry.md §2의 {@code classify(e)} 5종이다.
 *
 * <p>프론트의 "연결 테스트 실패 안내"가 이 코드를 그대로 사용한다. HTTP 에러코드
 * ({@link com.bifrost.ops.global.common.error.ErrorCode})와는 별개다 — 연결 테스트는
 * 실패도 200으로 응답하고 본문에 이 분류를 담는다(연결 가능 여부는 정상 응답의 일부다).
 */
public enum DbConnectionFailureReason {

    /** TCP 연결 거부(호스트 도달 가능하나 포트가 닫힘) 또는 호스트 해석 실패. */
    CONNECTION_REFUSED("호스트에 연결할 수 없습니다. host·port와 네트워크를 확인하세요."),

    /** 사용자/비밀번호 인증 실패. */
    AUTH_FAILED("인증에 실패했습니다. 사용자 또는 비밀번호를 확인하세요."),

    /** 대상 데이터베이스(catalog)가 존재하지 않음. */
    DB_NOT_FOUND("대상 데이터베이스를 찾을 수 없습니다. dbName을 확인하세요."),

    /** 5초 안에 연결이 완료되지 않음(필터링·무응답 호스트 등). */
    TIMEOUT("연결이 시간 초과되었습니다(5초). host·port·방화벽을 확인하세요."),

    /** 위 어디에도 해당하지 않는 실패. */
    UNKNOWN("알 수 없는 오류로 연결에 실패했습니다.");

    private final String defaultMessage;

    DbConnectionFailureReason(String defaultMessage) {
        this.defaultMessage = defaultMessage;
    }

    /** 분류별 기본 안내 문구. 프론트가 자체 문구로 대체할 수 있다. */
    public String defaultMessage() {
        return defaultMessage;
    }
}
