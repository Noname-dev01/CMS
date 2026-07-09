package com.cms.admin.menu;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * dev 프로파일에서 menu 테이블이 비어 있으면 기본 사이드바 구조를 시드한다.
 * (TestMemberLoader와 동일한 패턴) 시드 구조는 동적 사이드바 전환 이전의
 * 정적 sidebar.html 구성을 그대로 옮긴 것이다.
 */
@Component
@Profile("dev")
@RequiredArgsConstructor
public class MenuDataLoader implements CommandLineRunner {

    private final MenuRepository menuRepository;

    @Override
    public void run(String... args) {
        if (menuRepository.count() > 0) {
            return;
        }

        save("대시보드", "/admin", "fas fa-fw fa-tachometer-alt", MenuAccessRole.ALL, 0, null);
        save("메뉴 관리", "/admin/menu/manage", "fas fa-fw fa-bars", MenuAccessRole.ADMIN, 1, null);

        Menu memberGroup = save("회원 관리", null, "fas fa-fw fa-user-shield", MenuAccessRole.ALL, 2, null);
        save("관리자 조회", "/admin/member/manage", "fas fa-fw fa-users", MenuAccessRole.ADMIN, 0, memberGroup.getMenuNo());
        save("관리자 추가", "/admin/member/new", "fas fa-fw fa-user-plus", MenuAccessRole.ADMIN, 1, memberGroup.getMenuNo());
        save("내 정보", "/admin/member/info", "fas fa-fw fa-id-badge", MenuAccessRole.ALL, 2, memberGroup.getMenuNo());

        save("활동 로그", "/admin/log/manage", "fas fa-fw fa-clipboard-list", MenuAccessRole.ADMIN, 3, null);
    }

    private Menu save(String menuName, String menuUrl, String menuIcon,
                      MenuAccessRole accessRole, int ord, Long upMenuNo) {
        LocalDateTime now = LocalDateTime.now();
        return menuRepository.save(Menu.builder()
                .menuName(menuName)
                .menuUrl(menuUrl)
                .menuIcon(menuIcon)
                .useYn(true)
                .accessRole(accessRole)
                .ord(ord)
                .upMenuNo(upMenuNo)
                .createDate(now)
                .updateDate(now)
                .build());
    }
}
