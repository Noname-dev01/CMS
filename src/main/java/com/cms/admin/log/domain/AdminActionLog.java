package com.cms.admin.log.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "admin_action_log", indexes = {
        @Index(name = "idx_log_create_at_id",    columnList = "create_at, id"),
        @Index(name = "idx_log_action_user_id",  columnList = "action_user_id"),
        @Index(name = "idx_log_action_type",     columnList = "action_type")
})
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AdminActionLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long actionId;

    private String actionUserId;

    @Column(nullable = false, length = 100)
    private String actionType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AdminActionResult actionResult;

    @Column(length = 100)
    private String targetType;

    private Long targetId;

    @Column(length = 45)
    private String requestIp;

    @Column(length = 255)
    private String requestUri;

    @Column(length = 10)
    private String requestMethod;

    @Column(length = 500)
    private String errorMessage;

    @Column(nullable = false)
    private LocalDateTime createAt;
}
