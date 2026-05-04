package com.cms.admin.member.service;

import com.cms.admin.member.domain.Member;
import com.cms.admin.member.domain.Role;
import com.cms.admin.member.repository.MemberRepository;
import com.cms.common.exception.InvalidRequestException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class MemberService {

    private static final Pattern PASSWORD_PATTERN =
            Pattern.compile("^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[!@#$%^&*()_+\\-=\\[\\]{}|;:,.<>?]).{8,}$");

    private final MemberRepository memberRepository;
    private final PasswordEncoder passwordEncoder;
    private final JavaMailSender mailSender;
    private final String baseUrl;

    public MemberService(MemberRepository memberRepository,
                         PasswordEncoder passwordEncoder,
                         JavaMailSender mailSender,
                         @Value("${app.base-url}") String baseUrl) {
        this.memberRepository = memberRepository;
        this.passwordEncoder = passwordEncoder;
        this.mailSender = mailSender;
        this.baseUrl = baseUrl;
    }

    public void registerMember(Member member) {
        member.setUserType(Role.ROLE_ADMIN);
        member.setPwd(passwordEncoder.encode(member.getPwd()));
        memberRepository.save(member);
    }

    public boolean isUserIdTaken(String userId) {
        return memberRepository.findByUserId(userId).isPresent();
    }

    public void sendPasswordResetEmail(String userId, String email) {
        memberRepository.findByUserId(userId)
                .filter(member -> member.getEmail() != null && member.getEmail().equals(email))
                .ifPresent(member -> {
                    String resetToken = UUID.randomUUID().toString();
                    member.setResetToken(resetToken);
                    member.setResetTokenExpiryAt(LocalDateTime.now().plusMinutes(30));
                    memberRepository.save(member);

                    /*SimpleMailMessage message = new SimpleMailMessage();
                    message.setTo(email);
                    message.setSubject("비밀번호 재설정 요청");
                    message.setText("비밀번호를 재설정하려면 다음 링크를 클릭하세요: "
                            + baseUrl + "/member/reset-password?token=" + resetToken);
                    mailSender.send(message);*/
                });
    }

    public boolean resetPassword(String token, String newPassword) {
        if (newPassword == null || !PASSWORD_PATTERN.matcher(newPassword).matches()) {
            throw new InvalidRequestException("비밀번호는 8자 이상이며 대/소문자, 숫자, 특수문자를 포함해야 합니다.");
        }

        return memberRepository.findByResetToken(token)
                .filter(member -> member.getResetTokenExpiryAt() != null
                        && member.getResetTokenExpiryAt().isAfter(LocalDateTime.now()))
                .map(member -> {
                    member.setPwd(passwordEncoder.encode(newPassword));
                    member.setResetToken(null);
                    member.setResetTokenExpiryAt(null);
                    memberRepository.save(member);
                    return true;
                })
                .orElse(false);
    }
}
