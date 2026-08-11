package com.cms.config.auth;

import com.cms.admin.member.domain.Member;
import com.cms.admin.member.domain.ProfileImageUrls;
import com.cms.admin.member.domain.Role;
import com.cms.admin.member.repository.MemberRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Slf4j
@Component
@RequiredArgsConstructor
public class AdminSecurityService {

    private final MemberRepository memberRepository;

    public boolean hasAdminAuthority() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();

        if (auth == null || !(auth.getPrincipal() instanceof CustomUserDetails userDetails)) {
            return false;
        }

        return userDetails.getMember().getUserType() == Role.ROLE_ADMIN;
    }

    public Long getCurrentAdminId(){
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();

        if (auth == null || !(auth.getPrincipal() instanceof CustomUserDetails userDetails)) {
            return null;
        }

        return userDetails.getMember().getId();
    }

    public String getCurrentAdminUserId(){
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();

        if (auth == null || !(auth.getPrincipal() instanceof CustomUserDetails userDetails)) {
            return null;
        }

        return userDetails.getMember().getUserId();
    }

    public String getCurrentAdminName() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();

        if (auth == null || !(auth.getPrincipal() instanceof CustomUserDetails userDetails)) {
            return null;
        }

        return userDetails.getMember().getUserName();
    }

    public String getCurrentAdminProfileImageUrl() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();

        if (auth == null || !(auth.getPrincipal() instanceof CustomUserDetails userDetails)) {
            return null;
        }

        // 탑바는 항상 "본인" 컨텍스트다 — kind=UPLOADED면 /admin/api/members/me/profile-image
        // 다운로드 라우트로, 그 외(프리셋·미이관 레거시)는 원값 그대로 pass-through.
        return ProfileImageUrls.resolveSelfUrl(userDetails.getMember());
    }

    /**
     * 자기수정 완료 후 SecurityContext의 principal을 최신 DB 상태로 갱신한다.
     * AdminViewAdvice가 SecurityContext에서 이름·프로필 이미지를 읽으므로, 갱신하지 않으면
     * 수정 직후 화면에 이전 값이 남는다.
     *
     * <p>이 메서드는 서비스 트랜잭션 커밋 후 컨트롤러에서 호출된다.
     * 내부 예외는 격리하여 이미 성공한 원 요청을 500으로 뒤집지 않는다.
     *
     * <p>동시 삭제 등으로 회원을 찾지 못하면 SecurityContext를 비워
     * 다음 요청에서 재인증되도록 한다(의도된 동작).
     */
    public void refreshAuthentication(Long adminId) {
        if (adminId == null) {
            return;
        }

        Authentication currentAuth = SecurityContextHolder.getContext().getAuthentication();
        if (currentAuth == null || !(currentAuth.getPrincipal() instanceof CustomUserDetails)) {
            return;
        }

        try {
            Optional<Member> memberOpt = memberRepository.findById(adminId);

            if (memberOpt.isEmpty()) {
                // 동시 삭제 등 — 이미 성공한 응답은 유지되며, 다음 요청에서 재인증된다.
                log.warn("refreshAuthentication: 회원을 찾을 수 없어 SecurityContext를 초기화합니다. adminId={}", adminId);
                SecurityContextHolder.clearContext();
                return;
            }

            CustomUserDetails refreshedUser = new CustomUserDetails(memberOpt.get());
            UsernamePasswordAuthenticationToken newAuth = new UsernamePasswordAuthenticationToken(
                    refreshedUser,
                    currentAuth.getCredentials(),
                    refreshedUser.getAuthorities()
            );
            newAuth.setDetails(currentAuth.getDetails());
            SecurityContextHolder.getContext().setAuthentication(newAuth);

        } catch (Exception e) {
            log.warn("refreshAuthentication: principal 갱신 중 오류 발생. adminId={}", adminId, e);
        }
    }
}
