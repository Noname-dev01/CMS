package com.cms.admin.notification.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 읽음 처리 요청 본문 — {@code {"read": true}}만 허용한다(읽음 취소는 지원하지 않는다). */
@Getter
@Setter
@NoArgsConstructor
public class NotificationReadRequest {

    @NotNull
    private Boolean read;
}
