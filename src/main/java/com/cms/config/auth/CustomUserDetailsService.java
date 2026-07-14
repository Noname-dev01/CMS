package com.cms.config.auth;

import com.cms.admin.member.domain.MemberStatus;
import com.cms.admin.member.repository.MemberRepository;
import com.cms.admin.member.domain.Member;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.CredentialsExpiredException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;

@Service
@RequiredArgsConstructor
public class CustomUserDetailsService implements UserDetailsService {

    private final MemberRepository memberRepository;
    private final LoginFailureService loginFailureService;

    /**
     * 쓰기 가능 트랜잭션인 이유: 진입부의 만료 자동 잠금 해제(벌크 UPDATE)가 같은 트랜잭션에
     * 참여한다 — REQUIRES_NEW 분리는 요청당 커넥션 2개를 잡아 병렬 로그인 폭주 시 풀 고갈 위험.
     * 이 메서드는 비밀번호 검증 전에 커밋되므로, 틀린 비밀번호여도 해제는 유지된다.
     */
    @Override
    @Transactional
    public UserDetails loadUserByUsername(String userId) throws UsernameNotFoundException {
        // 만료된 자동 잠금(30분 경과)을 회원 조회 전에 해제 — 같은 트랜잭션에서 해제 후 fresh 조회
        loginFailureService.unlockIfLockExpired(userId);

        Member member = memberRepository.findByUserId(userId)
                .orElseThrow(() -> new UsernameNotFoundException("존재하지 않는 사용자입니다."));

        validateMemberStatus(member);

        return new CustomUserDetails(member);
    }

    private void validateMemberStatus(Member member) {
        MemberStatus status = member.getStatus();

        //비활성화 또는 삭제된 계정
        if (status == MemberStatus.DISABLED || status == MemberStatus.DELETED){
            throw new DisabledException("비활성화된 계정입니다.");
        }

        //잠긴 계정
        if (status == MemberStatus.LOCKED){
            throw new LockedException("잠긴 계정입니다.");
        }

        //비밀번호 만료 계정
        if (status == MemberStatus.PASSWORD_EXPIRED) {
            throw new CredentialsExpiredException("비밀번호가 만료된 계정입니다.");
        }

        if (status != MemberStatus.ACTIVE){
            throw new DisabledException("로그인할 수 없는 계정 상태입니다.");
        }
    }


}
