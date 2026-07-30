package com.cms.admin.member;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * prod 부트스트랩 관리자 계정 전용 검증 계약(PLAN-prod-profile.md 결정 4).
 *
 * <p>회원가입 API의 {@code AdminSignupRequest}는 비밀번호에 길이 정책이 없어 재사용하지 않는다.
 * 대신 앱 전역에서 실제로 쓰이는 정책(비밀번호: {@code AdminMyPasswordChangeRequest}·
 * {@code PasswordResetConfirmRequest}의 {@code @Size(min=4, max=100)}, 이메일:
 * {@code AdminMyInfoUpdateRequest}·{@code AdminMemberUpdateRequest}의 {@code @Size(max=100)})과
 * 일치시킨다.
 *
 * <p>record가 아니라 일반 클래스다 — record의 기본 {@code toString()}은 모든 컴포넌트(비밀번호
 * 포함)를 그대로 노출해 "비밀번호 값은 어떤 경우에도 로그에 출력하지 않는다"는 원칙과
 * 충돌한다({@code PasswordResetService.IssueResult}와 동일한 이유).
 */
public final class AdminBootstrapCredentials {

    @NotBlank
    @Size(max = 50)
    private final String userId;

    @NotBlank
    @Size(min = 4, max = 100)
    private final String password;

    @NotBlank
    @Email
    @Size(max = 100)
    private final String email;

    public AdminBootstrapCredentials(String userId, String password, String email) {
        this.userId = userId;
        this.password = password;
        this.email = email;
    }

    public String getUserId() {
        return userId;
    }

    public String getPassword() {
        return password;
    }

    public String getEmail() {
        return email;
    }

    @Override
    public String toString() {
        return "AdminBootstrapCredentials{userId=" + userId + "}";
    }
}
