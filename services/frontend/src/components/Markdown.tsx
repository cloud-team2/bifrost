import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import type { Components } from 'react-markdown'

/* 에이전트 답변(GFM 마크다운) 렌더. Tailwind preflight가 list/heading 스타일을 리셋하므로
   요소별로 스타일을 명시한다. 채팅 버블 톤(작은 글씨)에 맞춤. */
const components: Components = {
  p: (props) => <p className="mb-1.5 last:mb-0" {...props} />,
  ul: (props) => <ul className="mb-1.5 list-disc space-y-0.5 pl-4 last:mb-0" {...props} />,
  ol: (props) => <ol className="mb-1.5 list-decimal space-y-0.5 pl-4 last:mb-0" {...props} />,
  li: (props) => <li className="leading-relaxed" {...props} />,
  strong: (props) => <strong className="font-semibold text-gray-900" {...props} />,
  em: (props) => <em className="italic" {...props} />,
  h1: (props) => <h1 className="mb-1 mt-1.5 text-[13px] font-semibold first:mt-0" {...props} />,
  h2: (props) => <h2 className="mb-1 mt-1.5 text-[13px] font-semibold first:mt-0" {...props} />,
  h3: (props) => <h3 className="mb-1 mt-1.5 font-semibold first:mt-0" {...props} />,
  a: (props) => <a className="text-brand-600 underline" target="_blank" rel="noreferrer" {...props} />,
  code: (props) => (
    <code className="rounded bg-gray-100 px-1 py-0.5 font-mono text-[11px] text-gray-800" {...props} />
  ),
  pre: (props) => (
    <pre className="mb-1.5 overflow-x-auto rounded bg-gray-50 p-2 font-mono text-[11px] leading-relaxed" {...props} />
  ),
  blockquote: (props) => (
    <blockquote className="mb-1.5 border-l-2 border-gray-200 pl-2 text-gray-500" {...props} />
  ),
  table: (props) => (
    <div className="mb-1.5 overflow-x-auto">
      <table className="border-collapse text-[11.5px]" {...props} />
    </div>
  ),
  th: (props) => <th className="border border-gray-200 px-1.5 py-0.5 text-left font-semibold" {...props} />,
  td: (props) => <td className="border border-gray-200 px-1.5 py-0.5" {...props} />,
  hr: () => <hr className="my-2 border-gray-200" />,
}

export function Markdown({ children }: { children: string }) {
  return (
    <ReactMarkdown remarkPlugins={[remarkGfm]} components={components}>
      {children}
    </ReactMarkdown>
  )
}
