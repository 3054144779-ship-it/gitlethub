import router from '../router'
import { useAuthStore } from '../stores/auth'

/** 解析一个 SSE 帧的 event 和 data 字段（多行 data 按规范用 \n 拼接） */
function readEvent(frame) {
  const dataLines = []
  let event = null
  for (const line of frame.split(/\r?\n/)) {
    // 空行和以冒号开头的注释帧（Java 端每 15s 发一次 keep-alive）直接跳过
    if (!line || line.startsWith(':')) continue
    const colon = line.indexOf(':')
    const field = colon === -1 ? line : line.slice(0, colon)
    let value = colon === -1 ? '' : line.slice(colon + 1)
    if (value.startsWith(' ')) value = value.slice(1)
    if (field === 'data') dataLines.push(value)
    else if (field === 'event') event = value
  }
  if (!dataLines.length) return null
  return { event, data: dataLines.join('\n') }
}

/**
 * 新协议：event: chunk + JSON 字符串（保留换行）。
 * 旧协议（无 event、纯文本 data）原样返回，避免后端未重启时内容丢失。
 */
function decodeChunk(event, data) {
  if (event !== 'chunk') return data
  try {
    const parsed = JSON.parse(data)
    return typeof parsed === 'string' ? parsed : data
  } catch {
    return data
  }
}

/**
 * AI 对话（SSE 流式）。
 * POST 无法使用 EventSource，这里用 fetch 读取响应流并手工切帧。
 * onToken 收到的是文本增量；服务端的 [DONE] 结束标记会被过滤掉，不会回调。
 */
export async function chatWithAIStream(owner, repo, payload, { signal, onToken } = {}) {
  const auth = useAuthStore()
  const response = await fetch(`/api/ai/${owner}/${repo}/chat`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Accept: 'text/event-stream',
      ...(auth.token ? { Authorization: `Bearer ${auth.token}` } : {})
    },
    body: JSON.stringify(payload),
    signal
  })

  if (response.status === 401) {
    auth.logout()
    router.push({ name: 'login' })
  }
  if (!response.ok) {
    let detail = ''
    try {
      detail = await response.text()
    } catch {
      // 错误响应体读不出来时忽略，保留状态码即可
    }
    throw new Error(
      `AI 服务请求失败（HTTP ${response.status}）${detail ? `：${detail.slice(0, 200)}` : ''}`
    )
  }
  if (!response.body) {
    throw new Error('当前浏览器不支持流式响应')
  }

  const reader = response.body.getReader()
  const decoder = new TextDecoder('utf-8')
  let buffer = ''
  let done = false

  try {
    while (!done) {
      const { done: eof, value } = await reader.read()
      if (eof) break
      buffer += decoder.decode(value, { stream: true })

      let separator
      while ((separator = /\r?\n\r?\n/.exec(buffer))) {
        const frame = buffer.slice(0, separator.index)
        buffer = buffer.slice(separator.index + separator[0].length)
        const parsed = readEvent(frame)
        if (!parsed) continue
        if (parsed.data.trim() === '[DONE]') {
          done = true
          break
        }
        onToken?.(decodeChunk(parsed.event, parsed.data))
      }
    }

    // 服务端没发 [DONE] 就断开时，把最后一个没有空行结尾的帧也处理掉
    if (!done) {
      buffer += decoder.decode()
      const parsed = readEvent(buffer)
      if (parsed && parsed.data.trim() !== '[DONE]') {
        onToken?.(decodeChunk(parsed.event, parsed.data))
      }
    }
  } finally {
    try {
      await reader.cancel()
    } catch {
      // 流已经结束，取消失败无需处理
    }
  }
}
