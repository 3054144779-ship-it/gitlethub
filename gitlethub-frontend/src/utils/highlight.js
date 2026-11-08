import hljs from 'highlight.js/lib/core'
import bash from 'highlight.js/lib/languages/bash'
import c from 'highlight.js/lib/languages/c'
import cpp from 'highlight.js/lib/languages/cpp'
import css from 'highlight.js/lib/languages/css'
import dockerfile from 'highlight.js/lib/languages/dockerfile'
import go from 'highlight.js/lib/languages/go'
import ini from 'highlight.js/lib/languages/ini'
import java from 'highlight.js/lib/languages/java'
import javascript from 'highlight.js/lib/languages/javascript'
import json from 'highlight.js/lib/languages/json'
import kotlin from 'highlight.js/lib/languages/kotlin'
import markdown from 'highlight.js/lib/languages/markdown'
import php from 'highlight.js/lib/languages/php'
import python from 'highlight.js/lib/languages/python'
import ruby from 'highlight.js/lib/languages/ruby'
import rust from 'highlight.js/lib/languages/rust'
import scss from 'highlight.js/lib/languages/scss'
import sql from 'highlight.js/lib/languages/sql'
import typescript from 'highlight.js/lib/languages/typescript'
import xml from 'highlight.js/lib/languages/xml'
import yaml from 'highlight.js/lib/languages/yaml'

const languages = {
  bash,
  c,
  cpp,
  css,
  dockerfile,
  go,
  ini,
  java,
  javascript,
  json,
  kotlin,
  markdown,
  php,
  python,
  ruby,
  rust,
  scss,
  sql,
  typescript,
  xml,
  yaml
}

for (const [name, language] of Object.entries(languages)) {
  hljs.registerLanguage(name, language)
}

/** 超过这个字符数不做高亮：hljs 在几十万字符的压缩包源码上会明显卡顿 */
export const MAX_HIGHLIGHT_CHARS = 200_000

const EXT_LANGUAGE = {
  js: 'javascript',
  mjs: 'javascript',
  cjs: 'javascript',
  jsx: 'javascript',
  ts: 'typescript',
  tsx: 'typescript',
  vue: 'xml',
  html: 'xml',
  htm: 'xml',
  xhtml: 'xml',
  xml: 'xml',
  svg: 'xml',
  css: 'css',
  scss: 'scss',
  less: 'scss',
  json: 'json',
  md: 'markdown',
  markdown: 'markdown',
  py: 'python',
  java: 'java',
  c: 'c',
  h: 'c',
  cpp: 'cpp',
  cc: 'cpp',
  cxx: 'cpp',
  hpp: 'cpp',
  hh: 'cpp',
  go: 'go',
  rs: 'rust',
  sh: 'bash',
  bash: 'bash',
  zsh: 'bash',
  yml: 'yaml',
  yaml: 'yaml',
  sql: 'sql',
  kt: 'kotlin',
  kts: 'kotlin',
  php: 'php',
  rb: 'ruby',
  toml: 'ini',
  ini: 'ini',
  conf: 'ini',
  properties: 'ini',
  dockerfile: 'dockerfile'
}

const LANGUAGE_LABEL = {
  javascript: 'JavaScript',
  typescript: 'TypeScript',
  xml: 'HTML / XML',
  css: 'CSS',
  scss: 'SCSS',
  json: 'JSON',
  markdown: 'Markdown',
  python: 'Python',
  java: 'Java',
  c: 'C',
  cpp: 'C++',
  go: 'Go',
  rust: 'Rust',
  bash: 'Shell',
  yaml: 'YAML',
  sql: 'SQL',
  kotlin: 'Kotlin',
  php: 'PHP',
  ruby: 'Ruby',
  ini: 'INI',
  dockerfile: 'Dockerfile'
}

const SPECIAL_FILES = {
  dockerfile: 'dockerfile',
  makefile: 'bash',
  '.gitignore': 'ini',
  '.env': 'ini'
}

function escapeHtml(code) {
  return code.replace(/[&<>]/g, (char) => {
    if (char === '&') return '&amp;'
    if (char === '<') return '&lt;'
    return '&gt;'
  })
}

export function languageOf(path) {
  const name = String(path || '').split('/').pop().toLowerCase()
  if (SPECIAL_FILES[name]) return SPECIAL_FILES[name]
  const ext = name.includes('.') ? name.slice(name.lastIndexOf('.') + 1) : ''
  return EXT_LANGUAGE[ext] || ''
}

export function languageLabel(language) {
  return LANGUAGE_LABEL[language] || ''
}

/** 文件列表的「类型」列：按扩展名归类，没有扩展名时退回「文本」 */
export function fileTypeLabel(path) {
  const language = languageOf(path)
  if (language) return LANGUAGE_LABEL[language] || language.toUpperCase()
  const name = String(path || '').split('/').pop()
  const ext = name.includes('.') ? name.slice(name.lastIndexOf('.') + 1) : ''
  return ext ? ext.toUpperCase() : '文本'
}

/**
 * 返回可直接 v-html 的高亮结果。未知扩展名、超大文件或高亮失败时
 * 退回转义后的纯文本，至少保证内容安全且可读。
 */
export function highlightCode(code, path) {
  const language = languageOf(path)
  const text = String(code ?? '')

  if (!language || text.length > MAX_HIGHLIGHT_CHARS) {
    return { html: escapeHtml(text), language }
  }

  try {
    return { html: hljs.highlight(text, { language, ignoreIllegals: true }).value, language }
  } catch {
    return { html: escapeHtml(text), language }
  }
}
