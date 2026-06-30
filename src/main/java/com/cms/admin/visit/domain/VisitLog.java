package com.cms.admin.visit.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "visit_log", indexes = {
        @Index(name = "idx_visit_at", columnList = "visit_at")
})
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class VisitLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 100)
    private String visitorUserId;

    @Column(length = 45)
    private String requestIp;

    @Column(nullable = false)
    private LocalDateTime visitAt;
}
