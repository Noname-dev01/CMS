package com.cms.publicweb.inquiry;

import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

import java.util.regex.Pattern;

/**
 * {@link InquiryTurnstileProperties}의 기동 시점 교차 검증(PLAN-inquiry.md R1-3). Bean으로 등록돼야 실행된다({@link InquiryConfig}).
 * {@code enabled=false}이면 검증하지 않는다 — 그 상태는 "문의 기능 닫힘"이라 캡차 없이 제출을 받지 않는다.
 *
 * <ul>
 *   <li>{@code enabled=true}인데 사이트 키·시크릿이 비어 있으면 기동 실패</li>
 *   <li>prod 프로파일에서 Cloudflare <b>공개 테스트 키</b>(항상 통과/실패/상호작용 — 사이트 키 {@code 1x0…·2x0…·3x0…}, 시크릿 같은 접두)이면 기동 실패 —
 *       테스트 키를 복사해 넣으면 캡차가 무력화되는 운영 실수를 막는다. 실제 키는 {@code 0x4…}로 시작한다</li>
 * </ul>
 */
public class InquiryTurnstileConfigValidator {

    /** Cloudflare 문서의 테스트 키 접두 — 1x(항상 통과)·2x(항상 실패)·3x(상호작용 강제) + 0 반복. */
    private static final Pattern TEST_KEY = Pattern.compile("^[123]x0{5,}.*");

    public InquiryTurnstileConfigValidator(InquiryTurnstileProperties properties, Environment environment) {
        if (!properties.isEnabled()) {
            return;
        }
        if (properties.getSiteKey().isBlank() || properties.getSecretKey().isBlank()) {
            throw new IllegalStateException("cms.inquiry.turnstile.enabled=true인데 site-key 또는 secret-key가 비어 있습니다. "
                    + "문의 기능을 쓰지 않으려면 enabled=false(기본값)로 두세요.");
        }
        if (environment.acceptsProfiles(Profiles.of("prod"))
                && (TEST_KEY.matcher(properties.getSiteKey()).matches() || TEST_KEY.matcher(properties.getSecretKey()).matches())) {
            throw new IllegalStateException("prod 프로파일에서는 Cloudflare Turnstile 공개 테스트 키를 쓸 수 없습니다(캡차가 무력화됩니다). "
                    + "실제 사이트 키와 시크릿 키를 설정하세요.");
        }
    }
}
