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

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String userId) throws UsernameNotFoundException {
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
