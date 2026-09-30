package com.cms.admin.menu.controller;

import com.cms.admin.menu.dto.request.MenuCreateRequest;
import com.cms.admin.menu.dto.request.MenuMoveRequest;
import com.cms.admin.menu.dto.request.MenuOrderRequest;
import com.cms.admin.menu.dto.request.MenuUpdateRequest;
import com.cms.admin.menu.dto.response.MenuMoveResponse;
import com.cms.admin.menu.dto.response.MenuOrderResponse;
import com.cms.admin.menu.dto.response.MenuResponse;
import com.cms.admin.menu.dto.response.MenuTreeResponse;
import com.cms.admin.menu.service.MenuService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/admin/api/menus")
@Tag(name = "Admin Menu", description = "메뉴 관리 API")
public class MenuController {

    private final MenuService menuService;

    @Operation(summary = "메뉴 트리 조회", description = "useYn=true(기본, 활성만) 또는 useYn=all(비활성 포함 전체)")
    @ApiResponse(responseCode = "200", description = "조회 성공")
    @ApiResponse(responseCode = "400", description = "허용되지 않은 useYn 값")
    @GetMapping("/tree")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<MenuTreeResponse>> getMenuTree(
            @RequestParam(defaultValue = "true") String useYn
    ) {
        return ResponseEntity.ok(menuService.getMenuTree(useYn));
    }

    @Operation(summary = "메뉴 단건 조회")
    @ApiResponse(responseCode = "200", description = "조회 성공")
    @ApiResponse(responseCode = "404", description = "메뉴 없음")
    @GetMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<MenuResponse> getMenu(@PathVariable Long id) {
        return ResponseEntity.ok(menuService.getMenu(id));
    }

    @Operation(summary = "메뉴 생성")
    @ApiResponse(responseCode = "201", description = "생성 성공")
    @ApiResponse(responseCode = "400", description = "요청값 검증 실패 또는 비활성 부모 아래 활성 메뉴 생성 시도")
    @ApiResponse(responseCode = "404", description = "부모 메뉴 없음")
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<MenuResponse> createMenu(@Valid @RequestBody MenuCreateRequest request) {
        MenuResponse response = menuService.createMenu(request);
        return ResponseEntity.created(
                ServletUriComponentsBuilder.fromCurrentRequest()
                        .path("/{id}")
                        .buildAndExpand(response.getMenuNo())
                        .toUri()
        ).body(response);
    }

    @Operation(summary = "메뉴 수정", description = "부모(upMenuNo)는 변경할 수 없다. null 필드는 기존값을 유지한다.")
    @ApiResponse(responseCode = "200", description = "수정 성공")
    @ApiResponse(responseCode = "400", description = "요청값 검증 실패 또는 비활성 부모 아래로 재활성화 시도")
    @ApiResponse(responseCode = "404", description = "메뉴 없음")
    @PatchMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<MenuResponse> updateMenu(
            @PathVariable Long id,
            @Valid @RequestBody MenuUpdateRequest request
    ) {
        return ResponseEntity.ok(menuService.updateMenu(id, request));
    }

    @Operation(summary = "메뉴 부모(상위 메뉴) 변경",
            description = "menuNo를 유지한 채 부모를 바꾼다. 본문의 upMenuNo는 필수이며 정수 또는 null(최상위 승격)만 허용한다. "
                    + "결과는 항상 2단 이하 — 하위 메뉴가 있는 메뉴(비활성 포함)는 이동할 수 없고 새 부모는 최상위 메뉴여야 한다. "
                    + "새 부모 아래(또는 최상위) 맨 끝에 배치된다. 이미 그 부모 아래면 변경 없이 200. "
                    + "공용 메뉴를 관리자 전용 부모 아래로 옮기면 허용하되 응답 warnings에 알린다.")
    @ApiResponse(responseCode = "200", description = "이동 성공 또는 무변경(warnings 포함)")
    @ApiResponse(responseCode = "400", description = "요청값 검증 실패(upMenuNo 누락·정수/null 외의 값) 또는 이동 불가(자기 자신·하위 메뉴 아래·하위 메뉴가 있는 메뉴·활성 메뉴를 비활성 부모 아래로)")
    @ApiResponse(responseCode = "404", description = "메뉴 또는 부모 메뉴 없음")
    @ApiResponse(responseCode = "409", description = "동시 변경과 충돌(잠금 대기 타임아웃·교착) — 다시 시도")
    @PatchMapping("/{id}/parent")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<MenuMoveResponse> moveMenu(
            @PathVariable Long id,
            @Valid @RequestBody MenuMoveRequest request
    ) {
        return ResponseEntity.ok(menuService.moveMenu(id, request));
    }

    @Operation(summary = "형제 메뉴 순서 재조정",
            description = "같은 부모 아래 형제들의 순서를 요청 순서대로 확정하고 ord를 0..n-1로 다시 매긴다(멱등). "
                    + "scope=ALL은 형제 전체, scope=ACTIVE는 활성 형제만 담아야 하며 ACTIVE에서는 비활성 형제가 표시 순서상 제자리를 유지한다. "
                    + "요청이 scope가 가리키는 형제 집합과 다르면 409. 동시에 생성된 형제의 위치는 보장하지 않는다.")
    @ApiResponse(responseCode = "200", description = "재조정 성공(적용된 순서 반환)")
    @ApiResponse(responseCode = "400", description = "요청값 검증 실패(빈 목록·중복·null 원소·1000개 초과·scope 누락)")
    @ApiResponse(responseCode = "404", description = "부모 메뉴 없음")
    @ApiResponse(responseCode = "409", description = "형제 구성이 요청과 다름(추가·누락·활성 상태 변경) — 화면을 새로고침 후 재시도")
    @PutMapping("/order")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<MenuOrderResponse> reorderMenus(@Valid @RequestBody MenuOrderRequest request) {
        return ResponseEntity.ok(menuService.reorderMenus(request));
    }

    @Operation(summary = "메뉴 비활성화", description = "하드 삭제가 아닌 useYn=false 처리. 활성 하위 메뉴가 있으면 거부된다.")
    @ApiResponse(responseCode = "204", description = "비활성화 성공")
    @ApiResponse(responseCode = "404", description = "메뉴 없음")
    @ApiResponse(responseCode = "409", description = "활성 하위 메뉴 존재로 비활성화 거부")
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> deactivateMenu(@PathVariable Long id) {
        menuService.deactivateMenu(id);
        return ResponseEntity.noContent().build();
    }
}
