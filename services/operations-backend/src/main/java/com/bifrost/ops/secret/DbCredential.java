package com.bifrost.ops.secret;

/**
 * DB 접속 자격증명. {@link SecretStore}가 다루는 비밀값의 단위다.
 *
 * <p>설계 §3(Database Registry)·§2 Provisioning(4.1)의 호출부 계약과 동일하게
 * {@code cred.user()} / {@code cred.password()} 접근자를 노출한다. 이 값은
 * <b>State·로그·API 응답에 남기지 않는다</b>. 그래서 {@link #toString()}을
 * 재정의해 password를 마스킹한다(record 기본 toString은 비밀값을 노출하므로 금지).
 *
 * @param user     DB username
 * @param password DB password (마스킹 대상)
 */
public record DbCredential(String user, String password) {

    public DbCredential {
        if (user == null || user.isBlank()) {
            throw new IllegalArgumentException("user must not be blank");
        }
        if (password == null) {
            throw new IllegalArgumentException("password must not be null");
        }
    }

    @Override
    public String toString() {
        return "DbCredential[user=" + user + ", password=****]";
    }
}
