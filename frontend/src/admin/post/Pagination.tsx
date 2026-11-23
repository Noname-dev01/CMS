/** 목록·휴지통 공용 페이지 버튼(Bootstrap 4). 한 쪽 이하면 빈 목록이다. */
import { pageWindow } from './format'

interface Props {
  id: string
  label: string
  page: { page: number; totalPages: number; last: boolean } | null
  onPage: (page: number) => void
}

export function Pagination({ id, label, page, onPage }: Props) {
  const pages = page ? pageWindow(page.page, page.totalPages) : []
  return (
    <div className="pagination-bar">
      <nav aria-label={label}>
        <ul className="pagination mb-0" id={id}>
          {page && pages.length > 0 && (
            <>
              <li className={'page-item' + (page.page === 0 ? ' disabled' : '')}>
                <button type="button" className="page-link" onClick={() => page.page > 0 && onPage(page.page - 1)}>이전</button>
              </li>
              {pages.map((i) => (
                <li key={i} className={'page-item' + (i === page.page ? ' active' : '')}>
                  <button type="button" className="page-link" onClick={() => i !== page.page && onPage(i)}>{i + 1}</button>
                </li>
              ))}
              <li className={'page-item' + (page.last ? ' disabled' : '')}>
                <button type="button" className="page-link" onClick={() => !page.last && onPage(page.page + 1)}>다음</button>
              </li>
            </>
          )}
        </ul>
      </nav>
    </div>
  )
}
