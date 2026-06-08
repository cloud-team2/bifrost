package com.bifrost.ops.global.common.log;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 비즈니스 이벤트 가독 로그(#93).
 *
 * <p>Hibernate SQL 등 인프라 로그와 섞이지 않게, 각 작업의 성공/실패를
 * {@code [도메인] 메시지 (key=value, ...)} 형식으로 남겨 사람이 터미널에서 흐름을 바로 확인하게 한다.
 * 성공은 INFO, 실패는 WARN. 전용 logger {@code bifrost.ops}로 필터링/레벨 조정이 가능하다.
 *
 * <p>DB에 적재되는 event/audit({@code event}/{@code audit_events}, #70)와는 별개의 stdout 가독용이다.
 * 비밀값(비밀번호·토큰·secret)은 절대 남기지 않는다.
 */
public final class OpsLog {

    private static final Logger log = LoggerFactory.getLogger("bifrost.ops");

    private OpsLog() {
    }

    /** 성공 이벤트. */
    public static void ok(String domain, String message) {
        log.info("[{}] {}", domain, message);
    }

    /** 성공 이벤트 + 컨텍스트(key=value). */
    public static void ok(String domain, String message, String detail) {
        log.info("[{}] {} ({})", domain, message, detail);
    }

    /** 실패 이벤트 + 사유. */
    public static void fail(String domain, String message, String detail) {
        log.warn("[{}] {} ({})", domain, message, detail);
    }
}
