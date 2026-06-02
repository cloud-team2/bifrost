package com.bifrost.ops.secret;

/**
 * SecretStore 처리 실패. 주로 secret_ref로 자격증명을 찾지 못한 경우(provisioning 시점)에 던진다.
 *
 * <p>메시지·로그에 password 등 비밀값을 넣지 않는다. secret_ref는 비밀값이 아니므로 포함 가능.
 */
public class SecretStoreException extends RuntimeException {

    public SecretStoreException(String message) {
        super(message);
    }

    public SecretStoreException(String message, Throwable cause) {
        super(message, cause);
    }

    /** secret_ref에 해당하는 자격증명이 없을 때. */
    public static SecretStoreException notFound(String secretRef) {
        return new SecretStoreException("secret not found for ref: " + secretRef);
    }
}
