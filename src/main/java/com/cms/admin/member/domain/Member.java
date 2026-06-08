package com.cms.admin.member.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(
        name = "member",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_member_user_id", columnNames = "userId"),
                @UniqueConstraint(name = "uk_member_email", columnNames = "email")
        }
)
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Member {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, length = 50)
    private String userId;

    @Column(nullable = false, length = 255)
    private String pwd;

    @Column(nullable = false, length = 100)
    private String userName;

    @Column(nullable = false, length = 100)
    private String email;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private Role userType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private MemberStatus status;

    private LocalDateTime createDate;

    private LocalDateTime updateDate;

    @Column(length = 255)
    private String resetToken;

    private LocalDateTime resetTokenExpiryAt;

    @Lob
    @Column(name = "profile_image_url", columnDefinition = "LONGTEXT")
    private String profileImageUrl;

    /**
     * 내 정보(이름, 이메일) 수정. 수정 시각을 함께 갱신한다.
     */
    public void updateInfo(String userName, String email) {
        this.userName = userName;
        this.email = email;
        this.updateDate = LocalDateTime.now();
    }

    /**
     * 프로필 이미지 변경. null을 전달하면 이미지를 초기화한다.
     */
    public void changeProfileImage(String profileImageUrl) {
        this.profileImageUrl = profileImageUrl;
        this.updateDate = LocalDateTime.now();
    }

    /**
     * 비밀번호 변경. 이미 인코딩된 비밀번호를 전달해야 한다.
     */
    public void changePassword(String encodedPwd) {
        this.pwd = encodedPwd;
        this.updateDate = LocalDateTime.now();
    }

}
