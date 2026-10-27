package com.cms.admin.board.dto.request;

import com.cms.admin.site.SiteScope;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 부분 수정 요청. null 필드는 기존값 유지({@code version}만 필수). 이름 공백 거부·전체 null 거부·범위 검증은 서비스가 한다. */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "게시판 부분 수정 요청")
public class BoardUpdateRequest {

    @Size(max = 100)
    @Schema(description = "게시판 이름 (null이면 기존값 유지)", example = "보도자료")
    private String name;

    @Schema(description = "공개 게시판 여부 (null이면 기존값 유지)", example = "false")
    private Boolean publicYn;

    @Schema(description = "첨부 허용 여부 (null이면 기존값 유지)", example = "false")
    private Boolean attachmentYn;

    @Pattern(regexp = SiteScope.PATTERN)
    @Schema(description = "표시 대상 — \"COMMON\" 또는 사이트 ID 문자열 (null이면 기존값 유지, 공지 게시판은 변경 불가)", example = "COMMON")
    private String siteScope;

    // 지울 수 있는 문자열(설명·작성자 표시명): 누락·null = 유지, "" 또는 공백만 = 삭제. 길이는 정리 후 서비스가 검사한다(@Size 없음 — 정리 전 길이로 막지 않게).
    @Schema(description = "공개 목록 머리의 설명 (null이면 유지, 빈 문자열이면 삭제, 정리 후 500자 이하)", example = "보도자료를 올립니다.")
    private String description;

    @Schema(description = "공개 목록 한 페이지 글 수 — 10·20·30·50 (null이면 유지)", example = "20")
    private Integer listPageSize;

    @Schema(description = "공개 화면의 작성자 표시명 (null이면 유지, 빈 문자열이면 삭제 = 표시 안 함, 정리 후 50자 이하)", example = "홍보팀")
    private String authorLabel;

    @Schema(description = "공개 화면에 조회수 표시 여부 (null이면 유지)", example = "true")
    private Boolean showViewCount;

    @Schema(description = "새 글 배지 기간(일, 0..30, 0 = 끔) (null이면 유지)", example = "3")
    private Integer newBadgeDays;

    @Schema(description = "쓰기 허용 — false면 쓰기 중지(보관). 공통 공지 게시판은 끌 수 없다 (null이면 유지)", example = "false")
    private Boolean writableYn;

    @NotNull
    @Min(1)
    @Schema(description = "화면이 가진 게시판 변경 버전. 서버 값과 다르면 409(그 사이 다른 관리자가 저장함)", example = "1")
    private Integer version;
}
