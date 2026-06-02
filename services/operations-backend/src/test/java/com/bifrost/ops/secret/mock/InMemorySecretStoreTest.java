package com.bifrost.ops.secret.mock;

import com.bifrost.ops.secret.DbCredential;
import com.bifrost.ops.secret.SecretContext;
import com.bifrost.ops.secret.SecretStoreException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class InMemorySecretStoreTest {

    private InMemorySecretStore store;
    private SecretContext ctx;

    @BeforeEach
    void setUp() {
        store = new InMemorySecretStore();
        ctx = new SecretContext(UUID.randomUUID(), "orders-db");
    }

    @Test
    void put_then_resolve_roundtrips_credential() {
        DbCredential cred = new DbCredential("alice", "s3cr3t");

        String ref = store.put(ctx, cred);

        assertNotNull(ref);
        DbCredential resolved = store.resolve(ref);
        assertEquals("alice", resolved.user());
        assertEquals("s3cr3t", resolved.password());
    }

    @Test
    void resolve_unknown_ref_throws() {
        assertThrows(SecretStoreException.class, () -> store.resolve("bifrost-ds-deadbeef-x-abc123"));
    }

    @Test
    void delete_removes_credential_and_is_idempotent() {
        String ref = store.put(ctx, new DbCredential("bob", "pw"));

        store.delete(ref);
        assertThrows(SecretStoreException.class, () -> store.resolve(ref));

        // 없는 ref 재삭제는 예외 없이 무시
        store.delete(ref);
    }

    @Test
    void credential_toString_masks_password() {
        String s = new DbCredential("alice", "s3cr3t").toString();
        org.junit.jupiter.api.Assertions.assertTrue(s.contains("alice"));
        org.junit.jupiter.api.Assertions.assertTrue(s.contains("****"));
        org.junit.jupiter.api.Assertions.assertFalse(s.contains("s3cr3t"));
    }
}
