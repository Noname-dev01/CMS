package com.cms.member;

import com.cms.member.domain.Member;
import com.cms.member.domain.MemberStatus;
import com.cms.member.domain.Role;
import com.cms.member.repository.MemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class TestMemberLoader implements CommandLineRunner {

    private final MemberRepository memberRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    public void run(String... args) throws Exception {
        if (memberRepository.count() == 0) {
            memberRepository.save(Member.builder()
                            .userName("admin")
                            .pwd(passwordEncoder.encode("1234"))
                            .userId("admin")
                            .userType(Role.ROLE_ADMIN)
                            .status(MemberStatus.ACTIVE)
                    .build());
        }
    }
}
