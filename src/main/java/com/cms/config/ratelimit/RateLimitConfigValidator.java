package com.cms.config.ratelimit;

import org.springframework.http.HttpMethod;

import java.util.HashSet;
import java.util.Set;

/**
 * {@link RateLimitProperties}의 교차 검증(Bean Validation으로 표현하기 어려운 규칙)을 기동
 * 시점에 수행한다. Bean으로 등록돼야 실제로 실행된다 — Bean이 아니면 검증 로직 자체가 존재하지
 * 않는 것과 같다({@link RateLimitFilterConfig}가 명시 등록, PLAN-public-endpoint-rate-limit.md
 * 쟁점 11·12).
 *
 * <p>위반 시 {@link IllegalStateException}을 던져 컨텍스트 기동을 실패시킨다(fail-fast).
 */
public class RateLimitConfigValidator {

    private static final Set<String> ALLOWED_METHODS = Set.of(
            HttpMethod.GET.name(), HttpMethod.HEAD.name(), HttpMethod.POST.name(),
            HttpMethod.PUT.name(), HttpMethod.PATCH.name(), HttpMethod.DELETE.name());

    public RateLimitConfigValidator(RateLimitProperties properties) {
        validate(properties);
    }

    private void validate(RateLimitProperties properties) {
        if (!properties.isEnabled()) {
            return; // 비활성화 상태는 빈 규칙도 허용 — 명시적 enabled=false가 의도된 비활성화 스위치.
        }

        if (properties.getRules().isEmpty()) {
            // enabled=true인데 규칙이 없으면 모든 방어가 무력화된다 — methods=[]가 규칙 하나만
            // 무력화해도 기동 실패시키면서 이쪽을 경고로만 남기면 일관성이 없다(설정 실수로 간주).
            throw new IllegalStateException(
                    "cms.rate-limit.enabled=true인데 rules가 비어 있습니다. "
                            + "레이트리밋을 비활성화하려면 enabled=false를 사용하세요.");
        }

        Set<String> seenIds = new HashSet<>();
        for (RateLimitProperties.RuleConfig rule : properties.getRules()) {
            if (!seenIds.add(rule.getId())) {
                throw new IllegalStateException("cms.rate-limit.rules에 중복된 id가 있습니다: " + rule.getId());
            }
            for (String method : rule.getMethods()) {
                if (!ALLOWED_METHODS.contains(method)) {
                    throw new IllegalStateException(
                            "cms.rate-limit.rules[" + rule.getId() + "]에 허용되지 않는 method입니다: " + method);
                }
            }
        }
    }
}
