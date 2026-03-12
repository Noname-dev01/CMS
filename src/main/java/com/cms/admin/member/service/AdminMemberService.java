package com.cms.admin.member.service;

import com.cms.admin.log.annotation.AdminActionLogged;
import com.cms.admin.member.domain.Member;
import com.cms.admin.member.domain.MemberStatus;
import com.cms.admin.member.domain.Role;
import com.cms.admin.member.dto.request.AdminSignupRequest;
import com.cms.admin.member.dto.response.AdminSignupResponse;
import com.cms.admin.member.repository.MemberRepository;
import com.cms.common.exception.DuplicateResourceException;
import com.cms.common.exception.InvalidRequestException;
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
    @AdminActionLogged(actionType = "ADMIN_CREATE", targetType = "MEMBER", targetIdExpression = "id")
    public AdminSignupResponse createAdmin(AdminSignupRequest req) {
        if (req.getUserType() != Role.ROLE_ADMIN) {
            throw new InvalidRequestException("관리자 권한이 없습니다.");
        }

        if (memberRepository.existsByUserId(req.getUserId())){
            throw new DuplicateResourceException("이미 사용 중인 아이디입니다.");
        }

        if (req.getEmail() != null && !req.getEmail().isBlank()
                && memberRepository.existsByEmail(req.getEmail())) {
            throw new DuplicateResourceException("이미 사용 중인 이메일입니다.");
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
