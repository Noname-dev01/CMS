package com.cms.admin.member;

import com.cms.admin.member.domain.Member;
import com.cms.admin.member.domain.MemberStatus;
import com.cms.admin.member.domain.Role;
import com.cms.admin.member.repository.MemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.Date;

@Component
@Profile("dev")
@RequiredArgsConstructor
public class TestMemberLoader implements CommandLineRunner {

    private final MemberRepository memberRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    public void run(String... args) throws Exception {
        if (memberRepository.count() == 0) {
            memberRepository.save(Member.builder()
                            .userId("admin")
                            .userName("admin")
                            .email("admin@test.com")
                            .pwd(passwordEncoder.encode("1234"))
                            .userType(Role.ROLE_ADMIN)
                            .status(MemberStatus.ACTIVE)
                            .createDate(new Date())
                            .status(MemberStatus.ACTIVE)
                    .build());
        }
    }
}
