import react from '@vitejs/plugin-react'
import { defineConfig } from 'vitest/config'

// 관리자 화면 React 아일랜드 번들(PLAN-react-admin-faq.md 결정 2).
// - 산출물은 build/frontend → Gradle processResources가 static/js/admin-react/로 복사한다(src/main/resources에 생성물을 두지 않는다).
// - 진입점은 고정 이름(템플릿이 이름으로 싣는다). 1단계는 진입점 하나·동적 import 없음이라 산출물이 JS 파일 하나다.
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
