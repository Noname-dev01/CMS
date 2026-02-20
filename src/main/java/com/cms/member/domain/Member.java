package com.cms.member.domain;

import jakarta.persistence.*;
import lombok.*;

import java.util.Date;

@Entity
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Member {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String userId;

    private String pwd;

    private String userName;

    private String email;

    @Enumerated(EnumType.STRING)
    private Role userType;

    private String useYN;

    private Date createDate;

    private Date updateDate;

    private String resetToken;

}
