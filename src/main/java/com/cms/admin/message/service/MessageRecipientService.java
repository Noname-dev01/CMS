package com.cms.admin.message.service;

import com.cms.admin.message.dto.response.MessageRecipientResponse;
import com.cms.admin.message.repository.MessageRecipientQuery;
import com.cms.common.exception.InvalidRequestException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 쪽지 수신자 검색(PLAN-admin-message.md D4·§5-E, R1-8·R5-2·R6-1). 검색은 요청당 한 번 {@link MessageRateLimiter}가 소비한다 —
 * 이 서비스는 소비하지 않는다(정확 일치·부분 검색 두 조회가 각각 소비하지 않는다).
 *
 * <p><b>검색 원문을 보존한다</b>: 회원 생성 API는 아이디 앞뒤 공백을 막지도 정규화하지도 않으므로(앞뒤 공백이 든 아이디가 정상 데이터일 수 있다)
 * <ol>
 *   <li>① <b>원문 그대로의 {@code userId} 정확 일치</b>를 먼저 조회한다(원문 코드포인트가 아이디 길이 상한 50 이하일 때). 원문이 공백만이면 빈 결과다</li>
 *   <li>② 부분 검색은 원문을 {@code strip}한 뒤 <b>2~50코드포인트</b>일 때만 한다. 1코드포인트는 ①의 정확 일치만 한다 —
 *       한 글자 아이디·이름도 정상 계정이라, 저장된 아이디를 <b>원문 그대로</b> 입력하면 수신 가능한 어떤 계정도 항상 선택할 수 있다</li>
 * </ol>
 * 결과는 정확 일치를 앞에 두고 회원 {@code id}로 중복을 제거해 10건까지 돌려주며, 더 있으면 {@code truncated=true}다.
 * 1코드포인트 정확 조회는 수신 가능 계정의 존재 확인 경로다 — 요청 횟수 제한(D17)은 속도만 제한한다(D4 수용 범위).
 */
@Service
@RequiredArgsConstructor
public class MessageRecipientService {

    static final int RESULT_LIMIT = 10;
    static final int KEYWORD_MAX_CODE_POINTS = 50;
    /** 원문 UTF-16 상한 — 자원 방어(공백 패딩 등). 정상 아이디(≤50)는 이 상한에 걸리지 않는다. */
    static final int RAW_KEYWORD_MAX = 200;

    static final String INVALID_KEYWORD_MESSAGE = "검색어가 너무 깁니다.";

    private final MessageRecipientQuery recipientQuery;

    @Transactional(readOnly = true)
    public MessageRecipientResponse search(Long senderId, String rawKeyword) {
        if (rawKeyword == null || rawKeyword.isBlank()) {
            return new MessageRecipientResponse(List.of(), false);
        }
        if (rawKeyword.length() > RAW_KEYWORD_MAX) {
            throw new InvalidRequestException(INVALID_KEYWORD_MESSAGE);
        }
        String trimmed = rawKeyword.strip();
        int trimmedCodePoints = trimmed.codePointCount(0, trimmed.length());
        if (trimmedCodePoints > KEYWORD_MAX_CODE_POINTS) {
            throw new InvalidRequestException(INVALID_KEYWORD_MESSAGE);
        }

        List<MessageRecipientResponse.Item> merged = new ArrayList<>();

        // ① 원문 정확 일치 — userId 최대 길이(50)를 넘는 원문은 어떤 아이디와도 같을 수 없어 조회하지 않는다
        if (rawKeyword.codePointCount(0, rawKeyword.length()) <= KEYWORD_MAX_CODE_POINTS) {
            merged.addAll(recipientQuery.findExactUserId(senderId, rawKeyword));
        }

        // ② 부분 검색은 trim 후 2코드포인트부터(1코드포인트는 정확 일치만)
        if (trimmedCodePoints >= 2) {
            for (MessageRecipientResponse.Item item : recipientQuery.findPartial(senderId, trimmed, RESULT_LIMIT + 1)) {
                boolean duplicate = merged.stream().anyMatch(existing -> existing.id().equals(item.id()));
                if (!duplicate) {
                    merged.add(item);
                }
            }
        }

        boolean truncated = merged.size() > RESULT_LIMIT;
        return new MessageRecipientResponse(truncated ? merged.subList(0, RESULT_LIMIT) : merged, truncated);
    }
}
