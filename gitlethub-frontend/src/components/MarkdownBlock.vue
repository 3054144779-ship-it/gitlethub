<script setup>
import { computed } from 'vue'
import { renderMarkdown } from '../utils/markdown'

const props = defineProps({
  text: { type: String, default: '' }
})

const html = computed(() => renderMarkdown(props.text))
</script>

<template>
  <div class="md-body" v-html="html" />
</template>

<style scoped>
.md-body {
  font-size: 13px;
  line-height: 1.7;
  color: var(--text-1);
  word-break: break-word;
}

.md-body :deep(p) {
  margin: 0 0 8px;
}

.md-body :deep(p:last-child) {
  margin-bottom: 0;
}

.md-body :deep(h1),
.md-body :deep(h2),
.md-body :deep(h3),
.md-body :deep(h4) {
  margin: 12px 0 6px;
  font-size: 14px;
  line-height: 1.4;
}

.md-body :deep(h1:first-child),
.md-body :deep(h2:first-child),
.md-body :deep(h3:first-child) {
  margin-top: 0;
}

.md-body :deep(ul),
.md-body :deep(ol) {
  margin: 6px 0;
  padding-left: 20px;
}

.md-body :deep(li) {
  margin: 2px 0;
}

.md-body :deep(code) {
  padding: 1px 5px;
  border-radius: 5px;
  background: rgba(148, 163, 184, 0.16);
  color: var(--code-inline-text);
  font-family: 'JetBrains Mono', Consolas, 'Courier New', monospace;
  font-size: 12px;
}

.md-body :deep(pre) {
  margin: 10px 0;
  padding: 12px 14px;
  border-radius: var(--r-sm);
  border: 1px solid var(--line);
  background: var(--code-bg);
  color: var(--code-text);
  overflow-x: auto;
}

.md-body :deep(pre code) {
  padding: 0;
  background: transparent;
  color: inherit;
}

.md-body :deep(blockquote) {
  margin: 10px 0;
  padding: 8px 12px;
  border-left: 3px solid var(--accent-1);
  border-radius: 0 var(--r-sm) var(--r-sm) 0;
  background: rgba(99, 102, 241, 0.09);
  color: var(--text-2);
}

.md-body :deep(table) {
  width: 100%;
  margin: 10px 0;
  border-collapse: collapse;
  font-size: 12px;
  border-radius: var(--r-sm);
  overflow: hidden;
}

.md-body :deep(th),
.md-body :deep(td) {
  padding: 6px 10px;
  border: 1px solid var(--line);
  text-align: left;
}

.md-body :deep(th) {
  background: rgba(148, 163, 184, 0.1);
  color: var(--text-1);
  font-weight: 600;
}

.md-body :deep(a) {
  color: var(--accent-2);
}

.md-body :deep(hr) {
  margin: 12px 0;
  border: none;
  border-top: 1px solid var(--line);
}
</style>
