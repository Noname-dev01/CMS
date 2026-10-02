package com.cms.admin.menu.controller;

import com.cms.admin.menu.dto.request.MenuCreateRequest;
import com.cms.admin.menu.dto.request.MenuStructureRequest;
import com.cms.admin.menu.dto.request.MenuUpdateRequest;
import com.cms.admin.menu.dto.response.MenuResponse;
import com.cms.admin.menu.dto.response.MenuStructureResponse;
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

    @Operation(summary = "메뉴 구조 일괄 반영",
            description = "화면이 드래그로 만든 초안(전체 메뉴의 최종 부모·순서)을 한 번에 반영한다. menus는 비활성 포함 전체 메뉴이며 "
                    + "같은 upMenuNo 안의 배열 순서가 새 순서가 된다. 각 항목의 baseUpMenuNo·baseOrd는 초안을 만든 시점의 값으로, "
                    + "현재 값과 다르거나 메뉴 집합이 달라졌으면 409(낡은 초안). 최대 3단이고 하위 메뉴는 서브트리째 이동한다. "
                    + "부모가 바뀐 서브트리에 한해 깊이·비활성 부모·권한 불일치(공용 메뉴를 관리자 전용 조상 아래로)를 검사한다. 무변경이면 changed=0으로 200.")
    @ApiResponse(responseCode = "200", description = "반영 성공(changed = 실제로 바뀐 메뉴 수)")
    @ApiResponse(responseCode = "400", description = "요청값 검증 실패(빈 목록·중복·null 원소·토큰 타입) 또는 구조 위반(순환·3단 초과·비활성 부모 아래 활성 메뉴·관리자 전용 아래 공용 메뉴·요청에 없는 부모)")
    @ApiResponse(responseCode = "409", description = "낡은 초안(메뉴 집합 또는 기준 부모·순서가 현재와 다름) 또는 동시 변경과 충돌 — 화면을 새로고침 후 재시도")
    @PutMapping("/structure")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<MenuStructureResponse> applyStructure(@Valid @RequestBody MenuStructureRequest request) {
        return ResponseEntity.ok(menuService.applyStructure(request));
    }

    @Operation(summary = "메뉴 영구삭제", description = "하드 삭제(복구 불가). 비활성 상태이고 하위 메뉴(활성·비활성 무관)가 없는 메뉴만 삭제된다. 비활성화는 PATCH /{id} 의 useYn=false 로 한다.")
    @ApiResponse(responseCode = "204", description = "영구삭제 성공")
    @ApiResponse(responseCode = "404", description = "메뉴 없음")
    @ApiResponse(responseCode = "409", description = "활성 메뉴이거나 하위 메뉴가 있어 영구삭제 거부")
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> deleteMenu(@PathVariable Long id) {
        menuService.deleteMenu(id);
        return ResponseEntity.noContent().build();
    }
}
