package com.bifrost.ops.workspace;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class NamespaceSlugTest {

    @Test
    void lowercasesAndHyphenates() {
        assertThat(NamespaceSlug.slugify("Team A")).isEqualTo("team-a");
        assertThat(NamespaceSlug.slugify("Orders Pipeline!")).isEqualTo("orders-pipeline");
    }

    @Test
    void collapsesAndTrimsHyphens() {
        assertThat(NamespaceSlug.slugify("  --Team__A--  ")).isEqualTo("team-a");
        assertThat(NamespaceSlug.slugify("a   b   c")).isEqualTo("a-b-c");
    }

    @Test
    void resultMatchesK8sNamespacePattern() {
        String slug = NamespaceSlug.slugify("Team A");
        assertThat(slug).matches("^[a-z0-9]([-a-z0-9]*[a-z0-9])?$");
    }

    @Test
    void padsTooShortNames() {
        assertThat(NamespaceSlug.slugify("a")).hasSizeGreaterThanOrEqualTo(3);
        assertThat(NamespaceSlug.slugify("!!")).matches("^[a-z0-9]([-a-z0-9]*[a-z0-9])?$");
    }

    @Test
    void appendsSuffixOnCollision() {
        Set<String> taken = Set.of("team-a", "team-a-2");
        String result = NamespaceSlug.generate("Team A", taken::contains);
        assertThat(result).isEqualTo("team-a-3");
    }

    @Test
    void returnsBaseWhenNoCollision() {
        String result = NamespaceSlug.generate("Team A", s -> false);
        assertThat(result).isEqualTo("team-a");
    }
}
