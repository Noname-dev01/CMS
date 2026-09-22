package com.cms.admin.member;

import com.cms.admin.member.dto.request.validation.MaxUtf8Bytes;
import com.cms.admin.member.dto.request.validation.MinCodePoints;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * prod 부트스트랩 관리자 계정 전용 검증 계약(PLAN-prod-profile.md 결정 4,
 * PLAN-password-policy-unification.md로 정책 통일).
 *
 * <p>비밀번호 정책(최소 15코드포인트, 최대 72바이트 UTF-8)은 회원가입(
 * {@code AdminSignupRequest})·내 비밀번호 변경({@code AdminMyPasswordChangeRequest})·
 * 비밀번호 재설정({@code PasswordResetConfirmRequest})과 완전히 동일하다(
 * {@code @MinCodePoints}/{@code @MaxUtf8Bytes} 공유). 이메일 길이({@code @Size(max=100)})는
 * {@code AdminMyInfoUpdateRequest}·{@code AdminMemberUpdateRequest}와 동일하게 맞춘다.
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
    @MinCodePoints(value = 15, message = "비밀번호는 15자 이상이어야 합니다.")
    @MaxUtf8Bytes(value = 72, message = "비밀번호는 72바이트(UTF-8 기준)를 초과할 수 없습니다.")
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
