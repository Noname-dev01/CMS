package com.cms.admin.menu.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 메뉴 수정 요청 (부분 수정 시맨틱). 필드가 null이면 기존값을 유지하고,
 * upMenuNo(부모)는 포함하지 않는다(부모 변경 불가 정책). null이 아닌 값은
 * 생성 시와 동일한 길이 제약을 적용해 빈 문자열·길이 초과를 거부한다.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "메뉴 수정 요청 (null 필드는 기존값 유지, upMenuNo는 변경 불가)")
public class MenuUpdateRequest {

    @Size(max = 100)
    @Pattern(regexp = ".*\\S.*", message = "메뉴명은 공백일 수 없습니다.")
    @Schema(description = "메뉴명. null이면 기존값 유지", example = "회원 관리")
    private String menuName;

    @Size(max = 255)
    @Schema(description = "메뉴 URL. null이면 기존값 유지", example = "/admin/member/manage")
    private String menuUrl;

    @Size(max = 100)
    @Schema(description = "아이콘 클래스명. null이면 기존값 유지", example = "fas fa-users")
    private String menuIcon;

    @Size(max = 500)
    @Schema(description = "메뉴 설명. null이면 기존값 유지", example = "회원 목록 조회 및 관리")
    private String menuDesc;

    @Schema(description = "사용 여부. null이면 기존값 유지. false→true 재활성화 시 활성 부모 검증 적용", example = "true")
    private Boolean useYn;

    @Min(0)
    @Schema(description = "정렬 순서. null이면 기존값 유지", example = "1")
    private Integer ord;
}
