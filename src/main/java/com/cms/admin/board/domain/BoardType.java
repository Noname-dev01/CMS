package com.cms.admin.board.domain;

import com.cms.common.exception.InvalidRequestException;

/**
 * 게시판 유형 — 공개 목록의 모양만 바꾼다(PLAN-board-definition.md PR 6). 공개 경로·공개 조건은 유형과 무관하다.
 *
 * <p>읽기와 쓰기의 관대함이 다르다. {@link #fromStored}는 DB의 알 수 없는 값을 {@code LIST}로 본다(이상한 값 하나가 그 게시판을 읽는 모든
 * 화면을 500으로 만들지 않게). {@link #parse}는 요청 값을 정확히 일치할 때만 받는다. 알 수 없는 저장 원본은 유형을 언급하지 않은 수정에서도
 * 그대로 보존돼야 하므로 {@code BoardDisplay}는 해석값이 아니라 원본 문자열을 들고 다닌다.
 */
public enum BoardType {

    LIST, GALLERY, WEBZINE;

    private static final String INVALID_MESSAGE = "게시판 유형은 LIST·GALLERY·WEBZINE 중 하나여야 합니다.";

    /** 저장된 값 해석 — null·알 수 없는 값은 {@code LIST}. 읽기 전용 소비자(공개 화면·응답)만 쓴다. */
    public static BoardType fromStored(String raw) {
        if (raw != null) {
            for (BoardType type : values()) {
                if (type.name().equals(raw)) {
                    return type;
                }
            }
        }
        return LIST;
    }

    /**
     * 요청 값 엄격 파싱 — 이름과 정확히 같은 문자열만 허용한다(대소문자 구분, 공백 불허, 빈 문자열 400). 입력 원문은 문구에 싣지 않는다.
     * 호출자는 null(누락)을 따로 처리한다.
     */
    public static BoardType parse(String raw) {
        for (BoardType type : values()) {
            if (type.name().equals(raw)) {
                return type;
            }
        }
        throw new InvalidRequestException(INVALID_MESSAGE);
    }
}
