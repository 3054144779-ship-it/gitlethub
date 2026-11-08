import { marked } from 'marked'
import DOMPurify from 'dompurify'

marked.setOptions({ gfm: true, breaks: true })

/** 模型输出是不可信文本：先按 Markdown 渲染，再交给 DOMPurify 去掉脚本/事件属性 */
export function renderMarkdown(text) {
  const html = marked.parse(text ?? '', { async: false })
  return DOMPurify.sanitize(html, { USE_PROFILES: { html: true } })
}
