package com.bifrost.ops.workspace;

import java.util.function.Predicate;

/**
 * 워크스페이스 이름 → namespace/projectKey 슬러그 변환(#72).
 *
 * <p>결과는 K8s namespace/Kafka 토픽 prefix에 쓰이므로 반드시 다음을 만족한다:
 * 소문자/숫자/하이픈만, 처음·끝은 영숫자, 길이 3~63
 * (정규식 {@code ^[a-z0-9]([-a-z0-9]*[a-z0-9])?$}, RegisterRequest.namespace와 동일 규칙).
 */
public final class NamespaceSlug {

    private static final int MAX_LEN = 63;
    private static final int MIN_LEN = 3;

    private NamespaceSlug() {}

    /**
     * name을 슬러그로 바꾸고, {@code exists}가 true를 돌려주면 {@code -2, -3, ...} 접미사를 붙여
     * 충돌하지 않는 값을 반환한다.
     */
    public static String generate(String name, Predicate<String> exists) {
        String base = slugify(name);
        if (!exists.test(base)) {
            return base;
        }
        for (int i = 2; i < 1000; i++) {
            String suffix = "-" + i;
            String candidate = trimTo(base, MAX_LEN - suffix.length()) + suffix;
            if (!exists.test(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("namespace 슬러그 후보 고갈: " + base);
    }

    /** 충돌 검사 없이 순수 변환만(테스트·내부용). */
    public static String slugify(String name) {
        String s = name == null ? "" : name.toLowerCase();
        s = s.replaceAll("[^a-z0-9]+", "-"); // 영숫자 외 → 하이픈
        s = s.replaceAll("-{2,}", "-");      // 연속 하이픈 축약
        s = s.replaceAll("^-+|-+$", "");     // 양끝 하이픈 제거
        s = trimTo(s, MAX_LEN);
        s = s.replaceAll("-+$", "");         // trim 후 끝 하이픈 재정리
        if (s.length() < MIN_LEN) {
            s = padTo(s);
        }
        return s;
    }

    private static String trimTo(String s, int max) {
        return s.length() > max ? s.substring(0, max) : s;
    }

    /** 3자 미만이면 안정적으로 채운다(슬러그가 비거나 너무 짧은 이름 대비). */
    private static String padTo(String s) {
        StringBuilder sb = new StringBuilder(s.isEmpty() ? "ws" : s);
        while (sb.length() < MIN_LEN) {
            sb.append('0');
        }
        return sb.toString();
    }
}
