package com.cms.admin.board.dto.request;

import com.cms.admin.member.dto.request.validation.MaxUtf8Bytes;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 부분 수정 요청. null 필드는 기존값 유지 시맨틱이다. 값이 채워진 title/content의 공백 거부, 전 필드가 null인 경우의 거부는
 * 서비스 계층에서 처리한다({@code Post.update()}는 도메인 규칙만 담당). {@code publishPeriod}는 객체가 있을 때만 시작·종료를 통째로
 * 교체한다(없으면 유지) — {@link PostPublishPeriodRequest}.
 */
@Getter
@Setter
@NoArgsConstructor
@Schema(description = "게시글 부분 수정 요청")
public class PostUpdateRequest {

    @Size(max = 200)
    @Schema(description = "제목 (null이면 기존값 유지)", example = "2026년 상반기 자료집(수정)")
    private String title;

    @MaxUtf8Bytes(value = 200_000, message = "본문이 너무 깁니다.")
    @Schema(description = "본문 HTML (null이면 기존값 유지)")
    private String content;

    @Schema(description = "본문 형식 표식. content가 있으면 \"HTML\" 필수", example = "HTML")
    private String contentFormat;

    @Schema(description = "노출 여부 (null이면 기존값 유지)", example = "false")
    private Boolean useYn;

    @Valid
    @Schema(description = "노출 기간(예약 게시). 없으면(키 없음·null) 기존 기간 유지, 있으면 시작·종료를 통째로 교체({}면 제한 없음으로 비움)")
    private PostPublishPeriodRequest publishPeriod;

    /**
     * 세터 호출 여부 — 같은 키가 두 번 오면 뒤 객체가 앞 객체를 덮어써 잘못된 값이 기간 비움으로 바뀌므로 두 번째 호출을 거부한다.
     * JSON 속성이면 안 된다: getter가 보이면 Jackson이 비공개 필드를 mutator로 추론해 {"publishPeriodSet":false}로 플래그를 되돌려
     * 우회할 수 있다(코드 리뷰 R1) — getter·setter를 없애고 {@code @JsonIgnore}로 제외한다.
     */
    @JsonIgnore
    @Getter(lombok.AccessLevel.NONE)
    @Setter(lombok.AccessLevel.NONE)
    @Schema(hidden = true)
    private boolean publishPeriodSet;

    /** {@code DISABLED} 필수 — creator로 인식되면 같은 키가 중복될 때 마지막 값이 이겨 {@code publishPeriod} 세터의 중복 거부가 동작하지 않는다. */
    @JsonCreator(mode = JsonCreator.Mode.DISABLED)
    @Builder
    public PostUpdateRequest(String title, String content, String contentFormat, Boolean useYn, PostPublishPeriodRequest publishPeriod) {
        this.title = title;
        this.content = content;
        this.contentFormat = contentFormat;
        this.useYn = useYn;
        this.publishPeriod = publishPeriod;
    }

    @JsonProperty("publishPeriod")
    public void setPublishPeriod(PostPublishPeriodRequest publishPeriod) {
        if (publishPeriodSet) {
            throw new IllegalArgumentException("publishPeriod가 중복되었습니다.");
        }
        publishPeriodSet = true;
        this.publishPeriod = publishPeriod;
    }
}
