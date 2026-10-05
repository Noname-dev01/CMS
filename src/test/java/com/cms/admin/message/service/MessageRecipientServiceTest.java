package com.cms.admin.message.service;

import com.cms.admin.message.dto.response.MessageRecipientResponse;
import com.cms.admin.message.dto.response.MessageRecipientResponse.Item;
import com.cms.admin.message.repository.MessageRecipientQuery;
import com.cms.common.exception.InvalidRequestException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/** 수신자 검색의 원문 보존·정확 일치 우선·1코드포인트 규칙·잘림(PLAN-admin-message.md §5-E, R1-8·R5-2·R6-1). */
class MessageRecipientServiceTest {

    private static final long ME = 1L;

    private final MessageRecipientQuery query = mock(MessageRecipientQuery.class);
    private final MessageRecipientService service = new MessageRecipientService(query);

    private static Item item(long id, String userId) {
        return new Item(id, userId, "이름" + id);
    }

    @Test
    @DisplayName("null·빈 값·공백만은 조회 없이 빈 결과다")
    void blankInputReturnsEmptyWithoutQuery() {
        for (String blank : new String[]{null, "", "   ", "\t"}) {
            MessageRecipientResponse response = service.search(ME, blank);

            assertThat(response.getContent()).isEmpty();
            assertThat(response.isTruncated()).isFalse();
        }
        verifyNoInteractions(query);
    }

    @Test
    @DisplayName("원문 보존(R6-1): 앞 공백이 든 1코드포인트 입력 \" a\"는 trim하지 않은 원문으로 정확 일치만 조회하고 부분 검색은 하지 않는다")
    void leadingSpaceUserIdIsLookedUpVerbatim() {
        given(query.findExactUserId(ME, " a")).willReturn(List.of(item(7, " a")));

        MessageRecipientResponse response = service.search(ME, " a");

        assertThat(response.getContent()).extracting(Item::userId).containsExactly(" a");
        verify(query).findExactUserId(ME, " a"); // trim("a")이 아니라 원문
        verify(query, never()).findPartial(anyLong(), anyString(), anyInt());
    }

    @Test
    @DisplayName("1코드포인트 입력(R5-2): 한 글자 아이디는 정확 일치로만 찾고, 부분 검색은 하지 않는다")
    void singleCodePointUsesExactMatchOnly() {
        given(query.findExactUserId(ME, "김")).willReturn(List.of(item(9, "김")));

        MessageRecipientResponse response = service.search(ME, "김");

        assertThat(response.getContent()).hasSize(1);
        verify(query, never()).findPartial(anyLong(), anyString(), anyInt());
    }

    @Test
    @DisplayName("2코드포인트부터 부분 검색도 한다 — trim 후 검색어로, 정확 일치가 앞에 오고 같은 id는 중복 제거한다")
    void partialSearchAfterTrimWithExactFirstAndDedup() {
        given(query.findExactUserId(ME, " ab ")).willReturn(List.of());
        given(query.findPartial(eq(ME), eq("ab"), eq(11))).willReturn(List.of(item(2, "xab"), item(3, "ab")));
        // 정확 일치(원문 "ab")는 trim 전 원문 " ab "가 아니라 "ab"를 입력한 경우로 따로 확인
        given(query.findExactUserId(ME, "ab")).willReturn(List.of(item(3, "ab")));

        MessageRecipientResponse trimmedCase = service.search(ME, " ab ");
        MessageRecipientResponse exactCase = service.search(ME, "ab");

        assertThat(trimmedCase.getContent()).extracting(Item::id).containsExactly(2L, 3L);
        assertThat(exactCase.getContent()).extracting(Item::id).containsExactly(3L, 2L); // 정확 일치 3이 먼저, 부분 결과의 3은 중복 제거
    }

    @Test
    @DisplayName("10건을 넘으면 10건만 돌려주고 truncated=true다(정확 일치는 항상 앞에 남는다)")
    void truncatesToTenWithFlag() {
        List<Item> partial = new ArrayList<>();
        for (long id = 100; id < 111; id++) { // 11건
            partial.add(item(id, "aa" + id));
        }
        given(query.findExactUserId(ME, "aa")).willReturn(List.of(item(1, "aa")));
        given(query.findPartial(eq(ME), eq("aa"), eq(11))).willReturn(partial);

        MessageRecipientResponse response = service.search(ME, "aa");

        assertThat(response.isTruncated()).isTrue();
        assertThat(response.getContent()).hasSize(10);
        assertThat(response.getContent().get(0).userId()).isEqualTo("aa"); // 정확 일치가 밀리지 않는다
    }

    @Test
    @DisplayName("정확히 10건이면 truncated=false다")
    void exactlyTenIsNotTruncated() {
        List<Item> partial = new ArrayList<>();
        for (long id = 100; id < 110; id++) {
            partial.add(item(id, "bb" + id));
        }
        given(query.findExactUserId(ME, "bb")).willReturn(List.of());
        given(query.findPartial(eq(ME), eq("bb"), eq(11))).willReturn(partial);

        MessageRecipientResponse response = service.search(ME, "bb");

        assertThat(response.isTruncated()).isFalse();
        assertThat(response.getContent()).hasSize(10);
    }

    @Test
    @DisplayName("길이: 원문이 200 UTF-16 단위를 넘거나 trim 후 50코드포인트를 넘으면 400이다(고정 문구)")
    void tooLongKeywordRejected() {
        assertThatThrownBy(() -> service.search(ME, "a".repeat(201)))
                .isInstanceOf(InvalidRequestException.class).hasMessage(MessageRecipientService.INVALID_KEYWORD_MESSAGE);
        assertThatThrownBy(() -> service.search(ME, "가".repeat(51)))
                .isInstanceOf(InvalidRequestException.class);
        verifyNoInteractions(query);
    }

    @Test
    @DisplayName("원문이 50코드포인트를 넘지만 trim 후 50 이하면 정확 일치는 건너뛰고(어떤 아이디도 같을 수 없다) 부분 검색만 한다")
    void rawOverFiftyButTrimmedWithinSkipsExactLookup() {
        String raw = "  " + "가".repeat(49); // 원문 51코드포인트, trim 후 49
        given(query.findPartial(eq(ME), eq("가".repeat(49)), eq(11))).willReturn(List.of(item(5, "가가")));

        MessageRecipientResponse response = service.search(ME, raw);

        assertThat(response.getContent()).hasSize(1);
        verify(query, never()).findExactUserId(anyLong(), anyString());
    }
}
