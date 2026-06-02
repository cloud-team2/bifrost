package com.bifrost.ops.secret;

import java.util.UUID;

/**
 * secret_ref 이름 생성 규칙(단일 출처).
 *
 * <p><b>형식</b>: {@code bifrost-ds-{tenant8}-{slug}-{rand6}}
 * <ul>
 *   <li>{@code bifrost-ds-} : 소유(bifrost) + datasource 자격증명 구분 prefix</li>
 *   <li>{@code tenant8}     : tenantId(UUID)에서 하이픈 제거 후 앞 8 hex (테넌트 추적/격리)</li>
 *   <li>{@code slug}        : datasource 이름 → 소문자 [a-z0-9], 그 외 문자는 '-', 연속 '-' 축약,
 *                            양끝 '-' 제거, 최대 20자</li>
 *   <li>{@code rand6}       : 소문자 영숫자 6자. 재등록·로테이션 시 stale secret과 충돌 방지</li>
 * </ul>
 *
 * <p>결과는 RFC 1123 label 규칙(소문자 영숫자/하이픈, 시작·끝은 영숫자, ≤63자)을 만족하므로
 * 추후 K8s Secret 이름으로 그대로 사용할 수 있다. mock 저장소도 같은 규칙으로 ref를 만든다.
 */
public final class SecretRefNaming {

    private static final String PREFIX = "bifrost-ds-";
    private static final int SLUG_MAX = 20;
    private static final int RAND_LEN = 6;

    private SecretRefNaming() {
    }

    /** 컨텍스트로 새 secret_ref를 만든다(rand6 포함 — 호출마다 달라진다). */
    public static String build(SecretContext context) {
        String tenant8 = context.tenantId().toString().replace("-", "").substring(0, 8);
        String slug = slug(context.datasourceName());
        String rand = randomSuffix();
        return PREFIX + tenant8 + "-" + slug + "-" + rand;
    }

    /** datasource 이름을 DNS-safe 슬러그로 변환한다(결정적, 단위 테스트 대상). */
    public static String slug(String name) {
        String s = name.toLowerCase()
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-+)|(-+$)", "");
        if (s.length() > SLUG_MAX) {
            s = s.substring(0, SLUG_MAX).replaceAll("-+$", "");
        }
        return s.isBlank() ? "ds" : s;
    }

    private static String randomSuffix() {
        // UUID 기반 소문자 영숫자(16진수) 6자. App 런타임 난수로 충분.
        return UUID.randomUUID().toString().replace("-", "").substring(0, RAND_LEN);
    }
}
