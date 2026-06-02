package com.bifrost.ops.secret;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SecretRefNamingTest {

    // RFC 1123 label: 소문자 영숫자/하이픈, 시작·끝은 영숫자, ≤63자
    private static final String RFC1123 = "^[a-z0-9]([-a-z0-9]*[a-z0-9])?$";

    @Test
    void slug_normalizes_to_dns_safe() {
        assertEquals("orders-db", SecretRefNaming.slug("Orders DB"));
        assertEquals("my-pg", SecretRefNaming.slug("  my__pg!! "));
        assertEquals("a1-b2", SecretRefNaming.slug("a1_b2"));
    }

    @Test
    void slug_truncates_and_trims_trailing_hyphen() {
        String slug = SecretRefNaming.slug("a".repeat(30));
        assertTrue(slug.length() <= 20);
        assertTrue(slug.matches(RFC1123), slug);
    }

    @Test
    void slug_falls_back_when_empty() {
        assertEquals("ds", SecretRefNaming.slug("***"));
        assertEquals("ds", SecretRefNaming.slug("---"));
    }

    @Test
    void build_produces_rfc1123_ref_within_63_chars() {
        SecretContext ctx = new SecretContext(
                UUID.fromString("11111111-2222-3333-4444-555555555555"), "Orders DB");
        String ref = SecretRefNaming.build(ctx);

        assertTrue(ref.startsWith("bifrost-ds-11111111-orders-db-"), ref);
        assertTrue(ref.length() <= 63, "len=" + ref.length());
        assertTrue(ref.matches(RFC1123), ref);
    }

    @Test
    void build_is_unique_across_calls() {
        SecretContext ctx = new SecretContext(UUID.randomUUID(), "same-name");
        assertNotEquals(SecretRefNaming.build(ctx), SecretRefNaming.build(ctx));
    }
}
