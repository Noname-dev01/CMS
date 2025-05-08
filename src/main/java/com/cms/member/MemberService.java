package com.cms.member;

import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class MemberService {

    private final MemberRepository memberRepository;
    private final PasswordEncoder passwordEncoder;
    private final JavaMailSender mailSender;

    public MemberService(MemberRepository memberRepository, JavaMailSender mailSender) {
        this.memberRepository = memberRepository;
        this.passwordEncoder = new BCryptPasswordEncoder();
        this.mailSender = mailSender;
    }

    public void registerMember(Member member) {
        member.setUserType("ROLE_ADMIN");
        member.setPwd(passwordEncoder.encode(member.getPwd()));
        memberRepository.save(member);
    }

    public boolean isUserIdTaken(String userId) {
        return memberRepository.findByUserId(userId).isPresent();
    }

    public void sendPasswordResetEmail(String userId, String email) {
        memberRepository.findByUserId(userId)
                .filter(member -> member.getEmail() != null && member.getEmail().equals(email))
                .ifPresentOrElse(member -> {
                    String resetToken = UUID.randomUUID().toString();
                    member.setResetToken(resetToken);
                    memberRepository.save(member);

                    /*SimpleMailMessage message = new SimpleMailMessage();
                    message.setTo(email);
                    message.setSubject("비밀번호 재설정 요청");
                    message.setText("비밀번호를 재설정하려면 다음 링크를 클릭하세요: "
                            + "http://localhost:8080/member/reset-password?token=" + resetToken);
                    mailSender.send(message);*/
                }, () -> {
                    throw new RuntimeException("입력하신 정보와 일치하는 회원이 없습니다.");
                });
    }

    public boolean resetPassword(String token, String newPassword) {
        return memberRepository.findByResetToken(token)
                .map(member -> {
                    member.setPwd(passwordEncoder.encode(newPassword));
                    member.setResetToken(null);
                    memberRepository.save(member);
                    return true;
                })
                .orElse(false);
    }
}
