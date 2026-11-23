import react from '@vitejs/plugin-react'
import { defineConfig } from 'vitest/config'

// 관리자 화면 React 아일랜드 번들(PLAN-react-admin-faq.md 결정 2).
// - 산출물은 build/frontend → Gradle processResources가 static/js/admin-react/로 복사한다(src/main/resources에 생성물을 두지 않는다).
// - 진입점은 고정 이름(템플릿이 이름으로 싣는다). 진입점들이 함께 쓰는 모듈은 해시 이름 공유 청크(chunks/)가 되고 정적 import로만 참조한다
//   (동적 import 금지 — FrontendSourceConventionTest, 정책은 PLAN-react-admin-menu.md 결정 4).
// - CSS·자산은 번들에 넣지 않는다(스타일은 템플릿이 맡는다 — FrontendSourceConventionTest).
export default defineConfig({
  plugins: [react()],
  base: '/js/admin-react/',
  publicDir: false,
  build: {
    outDir: '../build/frontend',
    emptyOutDir: true,
    modulePreload: false,
    rolldownOptions: {
      input: {
        'faq-manage': 'src/admin/faq/main.tsx',
        'menu-manage': 'src/admin/menu/main.tsx',
        'post-manage': 'src/admin/post/main.tsx',
      },
      output: {
        entryFileNames: '[name].js',
        chunkFileNames: 'chunks/[name]-[hash].js',
        assetFileNames: 'assets/[name]-[hash][extname]',
      },
    },
  },
  test: {
    include: ['src/**/*.test.ts'],
    environment: 'node',
  },
})
