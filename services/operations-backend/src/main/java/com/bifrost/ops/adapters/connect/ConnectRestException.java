package com.bifrost.ops.adapters.connect;

/** Kafka Connect REST mutation 호출 실패. 응답 body는 자격증명 노출 방지를 위해 보관하지 않는다. */
public class ConnectRestException extends RuntimeException {

    private final boolean timeout;
    private final Integer statusCode;

    private ConnectRestException(String message, boolean timeout, Integer statusCode, Throwable cause) {
        super(message, cause);
        this.timeout = timeout;
        this.statusCode = statusCode;
    }

    public static ConnectRestException timeout(String operation, Throwable cause) {
        return new ConnectRestException("Kafka Connect REST timed out during " + operation,
                true, null, cause);
    }

    public static ConnectRestException upstream(String operation, Integer statusCode, Throwable cause) {
        String suffix = statusCode != null ? " (HTTP " + statusCode + ")" : "";
        return new ConnectRestException("Kafka Connect REST failed during " + operation + suffix,
                false, statusCode, cause);
    }

    public boolean isTimeout() {
        return timeout;
    }

    public Integer statusCode() {
        return statusCode;
    }
}
