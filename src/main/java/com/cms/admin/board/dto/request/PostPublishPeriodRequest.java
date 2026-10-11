package com.cms.admin.board.dto.request;

import com.cms.common.display.DisplayPeriod;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 게시글 노출 기간 입력(PLAN-post-publish-period.md 쟁점 1). 요청에 이 객체가 있으면 시작·종료를 <b>통째로 교체</b>한다 — {@code {}}나
 * {@code {start:null,end:null}}은 기간을 비우고, 한쪽만 주면 나머지는 열린다. 객체가 없으면(키 없음·명시 null) 기존 기간을 유지한다.
 *
 * <p>기간을 잘못 읽으면 글이 즉시 공개되므로 Jackson의 관대한 역직렬화에 기대지 않는다(리뷰 R1~R4가 재현한 우회):
 * 값은 {@code String}으로 받아 {@link DisplayPeriod#parseKstLocal}이 엄격하게 읽고(오프셋·빈 문자열·존재하지 않는 날짜·24시 거부),
 * 알 수 없는 키(오타)와 같은 키의 두 번째 등장은 세터에서 예외로 거부한다 — 전역 {@code fail-on-unknown-properties: false}는 다른 API 계약이라 바꾸지 않는다.
 * 세터가 던진 예외는 Jackson이 읽기 오류로 감싸 기존 핸들러가 400으로 응답한다.
 */
@Getter
@NoArgsConstructor
@Schema(description = "게시글 노출 기간. 있으면 시작·종료를 통째로 교체한다(비우면 제한 없음)")
public class PostPublishPeriodRequest {

    @Schema(description = "노출 시작(포함, 한국 시각, 분 단위로 절단). yyyy-MM-ddTHH:mm 형식, 오프셋 불가. null이면 시작 제한 없음",
            example = "2026-10-12T09:00", nullable = true)
    private String start;

    @Schema(description = "노출 종료(미포함, 한국 시각, 분 단위로 절단). null이면 종료 제한 없음", example = "2026-10-20T00:00", nullable = true)
    private String end;

    // 중복 키 검사용 내부 상태 — JSON 속성이면 안 된다. getter가 보이면 Jackson이 비공개 필드를 mutator로 추론해 요청이 {"startSet":false}로
    // 플래그를 되돌려 중복 검사를 우회할 수 있다(코드 리뷰 R1). getter를 없애고 @JsonIgnore로 입출력에서 제외한다.
    @JsonIgnore
    @Getter(AccessLevel.NONE)
    @Schema(hidden = true)
    private boolean startSet;
    @JsonIgnore
    @Getter(AccessLevel.NONE)
    @Schema(hidden = true)
    private boolean endSet;

    /**
     * 시험·서비스 코드용 생성자. {@code DISABLED}가 필수다 — Jackson 3는 매개변수 이름으로 생성자를 creator로 자동 인식하고, creator 경로에서는 같은 키의
     * 마지막 값이 이겨 세터의 중복 키 거부가 동작하지 않는다(실서버 스택 시험이 잡았다).
     */
    @JsonCreator(mode = JsonCreator.Mode.DISABLED)
    public PostPublishPeriodRequest(String start, String end) {
        this.start = start;
        this.end = end;
    }

    @JsonProperty("start")
    public void setStart(String start) {
        if (startSet) {
            throw new IllegalArgumentException("start가 중복되었습니다.");
        }
        startSet = true;
        this.start = start;
    }

    @JsonProperty("end")
    public void setEnd(String end) {
        if (endSet) {
            throw new IllegalArgumentException("end가 중복되었습니다.");
        }
        endSet = true;
        this.end = end;
    }

    /** 알 수 없는 키는 거부한다 — {@code publishStart} 같은 오타가 조용히 빈 객체(= 기간 비움)가 되는 것을 막는다. */
    @JsonAnySetter
    public void rejectUnknown(String key, Object value) {
        throw new IllegalArgumentException("알 수 없는 노출 기간 필드입니다.");
    }

    /** 검증된 기간 값. 순서: 문자열 엄격 파싱 → 범위 → 분 절단 → {@code start < end}. 위반은 {@code InvalidRequestException}(400). */
    public Period toPeriod() {
        LocalDateTime parsedStart = DisplayPeriod.parseKstLocal(start);
        LocalDateTime parsedEnd = DisplayPeriod.parseKstLocal(end);
        DisplayPeriod.requireInRange(parsedStart);
        DisplayPeriod.requireInRange(parsedEnd);
        LocalDateTime normalizedStart = DisplayPeriod.normalize(parsedStart);
        LocalDateTime normalizedEnd = DisplayPeriod.normalize(parsedEnd);
        DisplayPeriod.requireValid(normalizedStart, normalizedEnd);
        return new Period(normalizedStart, normalizedEnd);
    }

    /** 검증을 마친 기간. 어느 쪽이든 null이면 그쪽은 열려 있다. */
    public record Period(LocalDateTime start, LocalDateTime end) {
    }
}
