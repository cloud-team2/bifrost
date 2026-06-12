package com.bifrost.ops.pipeline.status;

/**
 * 커넥터 raw 에러(Strimzi 예외·스택·엔드포인트·UUID)를 사용자용 한 줄 요약으로 정제한다(#596).
 *
 * <p>이벤트/인시던트 메시지에 raw 스택이 그대로 노출되던 문제를 막기 위해, 흔한 실패 원인은 사람이
 * 읽는 문구로 매핑하고 매칭되지 않으면 첫 줄만 남기고 예외 prefix·스택·길이를 정리한다.
 * {@link PipelineStatusServiceImpl}(connector lastError)과 {@code ConnectRestPoller}(task trace)에서 공용.
 */
public final class ConnectorErrorMessages {

    private ConnectorErrorMessages() {
    }

    /** raw 커넥터 에러 → 사용자용 요약. null/빈 값이면 일반 문구. */
    public static String summarize(String raw) {
        if (raw == null || raw.isBlank()) {
            return "커넥터가 비정상(FAILED) 상태입니다";
        }
        String low = raw.toLowerCase();
        if (low.contains("authentication failed") || low.contains("password authentication failed")
                || low.contains("access denied") || low.contains("login failed")) {
            return "DB 인증 실패 (사용자·비밀번호 확인)";
        }
        // 연결 실패는 설정오류(config validate)보다 먼저 — 보통 검증 실패의 실제 원인이 연결이다.
        if (low.contains("connection refused") || low.contains("connection attempt failed")
                || low.contains("communications link failure") || low.contains("could not connect")
                || low.contains("connection timed out") || low.contains("connect timed out")
                || low.contains("unknownhost") || low.contains("no route to host")) {
            return "DB 연결 실패 (호스트·포트·네트워크 확인)";
        }
        if (low.contains("does not exist") || low.contains("unknown database")) {
            return "DB 또는 스키마를 찾을 수 없음";
        }
        if (low.contains("customconverterregistry") || low.contains("converter")) {
            return "커넥터 컨버터 로드 실패 (Connect 이미지 플러그인 확인)";
        }
        if (low.contains("replication slot") || low.contains("publication") || low.contains("wal_level")
                || low.contains("must be superuser") || low.contains("permission denied") || low.contains("replication")) {
            return "CDC 권한·설정 오류 (REPLICATION·슬롯·publication 확인)";
        }
        if (low.contains("configuration is invalid") || low.contains("connectrestexception")
                || low.contains("config/validate") || low.contains("returned 400")) {
            return "커넥터 설정 오류 (연결 정보 확인)";
        }
        return cleanFirstLine(raw);
    }

    /** 매핑 안 된 오류: 첫 줄만 + 'pkg.Xxx Exception:' prefix·스택 제거 + 길이 컷. */
    private static String cleanFirstLine(String raw) {
        String first = raw.split("\\R", 2)[0].trim();                       // 첫 줄만
        first = first.replaceFirst("^[\\w.$]+(Exception|Error):\\s*", "");  // 'io.x.Y Exception: ' 제거
        int at = first.indexOf("\tat ");
        if (at >= 0) {
            first = first.substring(0, at).trim();
        }
        if (first.length() > 160) {
            first = first.substring(0, 157) + "…";
        }
        return first.isBlank() ? "커넥터 오류" : first;
    }
}
