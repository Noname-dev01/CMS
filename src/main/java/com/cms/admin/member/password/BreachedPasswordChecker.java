package com.cms.admin.member.password;

/**
 * 후보 비밀번호가 알려진 유출 목록에 있는지 판정한다(PLAN-password-expiry-retirement-breach-check.md D1). 시험이 네트워크 없이 대체하기 위한 최소 이음매다.
 *
 * <p><b>Spring Security의 {@code CompromisedPasswordChecker}를 구현하지 않는다</b> — 그 타입이 빈이 되면 {@code DaoAuthenticationProvider}가
 * 로그인 때마다 자동으로 검사한다. 이 검사는 비밀번호를 정할 때만 한다(D2).
 *
 * <p>외부 호출이므로 <b>DB 트랜잭션과 행 잠금 밖에서</b> 부른다(D4).
 */
public interface BreachedPasswordChecker {

    /**
     * @return 유출 목록에 있으면 {@code true}. 판정할 수 없거나 검사가 꺼져 있으면 {@code false}(fail-open, D3) — 호출자에게 예외를 던지지 않는다.
     */
    boolean isBreached(String rawPassword);
}
