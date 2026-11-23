/**
 * 게시글 관리 화면 진입점 — templates/admin/board/posts.html이 #postManageRoot(미디어 라이브러리 버튼 표시 여부)와 #postModalRoot를 렌더링한다.
 * 나머지 데이터는 모두 API로 읽는다(인라인 스크립트 없음).
 */
import { createRoot } from 'react-dom/client'
import { QueryClientProvider } from '@tanstack/react-query'
import { createHttpClient } from '../../api/http'
import { createPostApi } from '../../api/post'
import { metaCsrf } from '../shared/csrf'
import { PostManage } from './PostManage'
import { createPostQueryClient } from './queries'

const root = document.getElementById('postManageRoot')
const modalContainer = document.getElementById('postModalRoot')
if (root && modalContainer) {
  createRoot(root).render(
    <QueryClientProvider client={createPostQueryClient()}>
      <PostManage api={createPostApi(createHttpClient(metaCsrf))} canPickLibrary={root.dataset.canPickLibrary === 'true'} modalContainer={modalContainer} />
    </QueryClientProvider>,
  )
}
