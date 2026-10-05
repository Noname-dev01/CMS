package com.cms.admin.message.domain;

import com.cms.common.exception.InvalidRequestException;

import java.util.Locale;

/** 쪽지함 — 받은 쪽지({@link #INBOX})와 보낸 쪽지({@link #SENT}). 요청 파라미터 {@code box}의 허용 값이다. */
public enum MessageBox {
    INBOX,
    SENT;

    /** {@code inbox}·{@code sent}(대소문자 무시)만 허용한다. 누락·그 밖의 값은 400(고정 문구 — 입력을 되돌려주지 않는다). */
    public static MessageBox parse(String value) {
        if (value != null) {
            String normalized = value.trim().toUpperCase(Locale.ROOT);
            for (MessageBox box : values()) {
                if (box.name().equals(normalized)) {
                    return box;
                }
            }
        }
        throw new InvalidRequestException("box는 inbox 또는 sent여야 합니다.");
    }
}
