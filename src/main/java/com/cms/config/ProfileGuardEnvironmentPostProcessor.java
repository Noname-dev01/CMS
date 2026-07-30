package com.cms.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * {@code ApplicationContext} 생성 전(빈 등록 전)에 활성 프로파일 조합을 검증한다.
 *
 * <p>{@code dev}+{@code prod} 동시 활성화를 막는다 — 둘 다 활성화되면 {@code @Profile("dev")}인
 * {@code TestMemberLoader}와 {@code @Profile("prod")}인 {@code AdminBootstrapLoader}가 모두
 * 실행 후보가 되고 실행 순서가 보장되지 않아, dev 로더가 먼저 실행되면 약한 고정 자격증명
 * (admin/1234)이 prod급 환경에 생길 수 있다.
 *
 * <p>활성 프로파일이 0개인 경우도 거부한다 — {@code SPRING_PROFILES_ACTIVE}가 빈 문자열로
 * 정의되면 placeholder 해석은 성공하지만 활성 프로파일이 없어 프로파일 누락을 막으려는
 * fail-fast 의도가 조용히 우회된다(PLAN-prod-profile.md 결정 1).
 */
public class ProfileGuardEnvironmentPostProcessor implements EnvironmentPostProcessor {

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String[] activeProfiles = environment.getActiveProfiles();

        if (activeProfiles.length == 0) {
            throw new IllegalStateException(
                    "활성 프로파일이 없습니다. SPRING_PROFILES_ACTIVE 환경변수를 dev 또는 prod로 지정하세요.");
        }

        boolean hasDev = false;
        boolean hasProd = false;
        for (String profile : activeProfiles) {
            if ("dev".equals(profile)) {
                hasDev = true;
            } else if ("prod".equals(profile)) {
                hasProd = true;
            }
        }

        if (hasDev && hasProd) {
            throw new IllegalStateException(
                    "dev와 prod 프로파일을 동시에 활성화할 수 없습니다. SPRING_PROFILES_ACTIVE 값을 확인하세요.");
        }
    }
}
