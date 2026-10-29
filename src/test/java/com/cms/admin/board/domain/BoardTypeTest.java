package com.cms.admin.board.domain;

import com.cms.common.exception.InvalidRequestException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 게시판 유형의 읽기(관대)·쓰기(엄격) 해석 시험(PLAN-board-definition.md PR 6 D2·D3). */
class BoardTypeTest {

    @Test
    @DisplayName("parse: 이름과 정확히 같은 문자열만 받는다")
    void parseAcceptsExactNames() {
        assertThat(BoardType.parse("LIST")).isEqualTo(BoardType.LIST);
        assertThat(BoardType.parse("GALLERY")).isEqualTo(BoardType.GALLERY);
        assertThat(BoardType.parse("WEBZINE")).isEqualTo(BoardType.WEBZINE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "gallery", "Gallery", " LIST", "LIST ", "GALLERY\n", "CARD", "LIST,GALLERY", "ＬＩＳＴ"})
    @DisplayName("parse: 빈 문자열·소문자·공백 포함·알 수 없는 값은 400이고 문구에 입력 원문이 없다")
    void parseRejectsEverythingElse(String raw) {
        assertThatThrownBy(() -> BoardType.parse(raw))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessage("게시판 유형은 LIST·GALLERY·WEBZINE 중 하나여야 합니다.");
    }

    @Test
    @DisplayName("parse: null은 400이다(누락은 호출자가 먼저 걸러야 한다)")
    void parseRejectsNull() {
        assertThatThrownBy(() -> BoardType.parse(null)).isInstanceOf(InvalidRequestException.class);
    }

    @Test
    @DisplayName("fromStored: 정확한 이름은 그대로 읽는다")
    void fromStoredReadsExactNames() {
        assertThat(BoardType.fromStored("GALLERY")).isEqualTo(BoardType.GALLERY);
        assertThat(BoardType.fromStored("WEBZINE")).isEqualTo(BoardType.WEBZINE);
        assertThat(BoardType.fromStored("LIST")).isEqualTo(BoardType.LIST);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "FUTURE", "gallery", " GALLERY"})
    @DisplayName("fromStored: null·알 수 없는 저장값은 LIST로 읽는다 — 읽기 화면이 500이 되지 않게")
    void fromStoredFallsBackToList(String raw) {
        assertThat(BoardType.fromStored(raw)).isEqualTo(BoardType.LIST);
    }

    @Test
    @DisplayName("엔티티: 기본값은 LIST, type()은 해석값이고 getBoardType()은 알 수 없는 원본을 그대로 돌려준다")
    void entityKeepsRawValue() {
        Board fresh = Board.builder().build();
        assertThat(fresh.getBoardType()).isEqualTo("LIST");
        assertThat(fresh.type()).isEqualTo(BoardType.LIST);

        Board future = Board.builder().boardType("FUTURE").build();
        assertThat(future.getBoardType()).isEqualTo("FUTURE");
        assertThat(future.type()).isEqualTo(BoardType.LIST);
        assertThat(future.display().boardType()).as("표시 설정 묶음도 원본을 들고 다닌다").isEqualTo("FUTURE");
    }
}
