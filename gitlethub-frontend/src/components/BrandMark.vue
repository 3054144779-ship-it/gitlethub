<script setup>
import { useId } from 'vue'

defineProps({
  size: { type: Number, default: 25 }
})

const gradientId = `brand-grad-${useId()}`
</script>

<template>
  <span class="brand-mark" :style="{ width: `${size}px`, height: `${size}px` }" aria-hidden="true">
    <svg class="brand-glyph" viewBox="0 0 24 24" fill="none">
      <defs>
        <linearGradient
          :id="gradientId"
          x1="20"
          y1="2"
          x2="4"
          y2="22"
          gradientUnits="userSpaceOnUse"
        >
          <stop offset="0" stop-color="var(--brand-a)" />
          <stop offset="0.52" stop-color="var(--brand-b)" />
          <stop offset="1" stop-color="var(--brand-c)" />
        </linearGradient>
      </defs>
      <path
        d="M6 3v12M18 9a9 9 0 0 1-9 9"
        :stroke="`url(#${gradientId})`"
        stroke-width="2.2"
        stroke-linecap="round"
      />
      <circle cx="6" cy="18" r="3" :stroke="`url(#${gradientId})`" stroke-width="2.2" />
      <path
        d="M18 2.9c.29 1.72 1.13 2.56 2.85 2.85-1.72.29-2.56 1.13-2.85 2.85-.29-1.72-1.13-2.56-2.85-2.85 1.72-.29 2.56-1.13 2.85-2.85Z"
        :fill="`url(#${gradientId})`"
      />
    </svg>
  </span>
</template>

<style scoped>
.brand-mark {
  /* 外层可用 --brand-mark-a/b/c 覆盖品牌色，未设置时沿用应用主色 */
  --brand-a: var(--brand-mark-a, #4d9bff);
  --brand-b: var(--brand-mark-b, #5b6cf0);
  --brand-c: var(--brand-mark-c, #8b5cf6);

  display: inline-flex;
  flex: 0 0 auto;
  align-items: center;
  justify-content: center;
}

html.dark .brand-mark {
  --brand-a: var(--brand-mark-a, #7dbbff);
  --brand-b: var(--brand-mark-b, #818cf8);
  --brand-c: var(--brand-mark-c, #a78bfa);
}

.brand-glyph {
  width: 100%;
  height: 100%;
}
</style>
