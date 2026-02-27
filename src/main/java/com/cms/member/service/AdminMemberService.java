package com.cms.member.service;

import com.cms.member.domain.Member;
import com.cms.member.domain.MemberStatus;
import com.cms.member.domain.Role;
import com.cms.member.dto.request.AdminSignupRequest;
import com.cms.member.dto.response.AdminSignupResponse;
import com.cms.member.repository.MemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;

@Service
@RequiredArgsConstructor
public class AdminMemberService {

    private final MemberRepository memberRepository;
    private final PasswordEncoder passwordEncoder;

    @Transactional
    public AdminSignupResponse createAdmin(AdminSignupRequest req) {
        if (req.getUserType() != Role.ROLE_ADMIN) {
            throw new IllegalArgumentException("허용되지 않은 권한입니다.");
        }

        if (memberRepository.existsByUserId(req.getUserId())){
            throw new IllegalStateException("이미 사용중인 아이디입니다.");
        }

        if (req.getEmail() != null && !req.getEmail().isBlank()
                && memberRepository.existsByEmail(req.getEmail())) {
            throw new IllegalStateException("이미 사용중인 이메일입니다.");
        }

        Date now = new Date();

        Member saved = memberRepository.save(
                Member.builder()
                        .userId(req.getUserId())
                        .pwd(passwordEncoder.encode(req.getPwd()))
                        .userName(req.getUserName())
                        .email(req.getEmail())
                        .userType(req.getUserType()) // MANAGER or ADMIN
                        .status(MemberStatus.ACTIVE)
                        .createDate(now)
                        .updateDate(now)
                        .resetToken(null)
                        .build()
        );

        return AdminSignupResponse.builder()
                .id(saved.getId())
                .userId(saved.getUserId())
                .userName(saved.getUserName())
                .email(saved.getEmail())
                .userType(saved.getUserType())
                .status(saved.getStatus())
                .createDate(saved.getCreateDate())
                .build();
    }

}
