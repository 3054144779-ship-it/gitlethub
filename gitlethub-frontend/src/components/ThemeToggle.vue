<script setup>
import { computed } from 'vue'

const props = defineProps({
  dark: { type: Boolean, default: true }
})

const emit = defineEmits(['toggle'])

const label = computed(() => (props.dark ? '切换到浅色模式' : '切换到深色模式'))
</script>

<template>
  <button
    type="button"
    class="theme-toggle"
    :class="{ 'is-dark': dark }"
    role="switch"
    :aria-checked="dark"
    :aria-label="label"
    :title="label"
    @click="emit('toggle')"
  >
    <span class="track" aria-hidden="true">
      <span class="scenes">
        <span class="sky sky-day">
          <span class="cloud cloud-a" />
          <span class="cloud cloud-b" />
        </span>
        <span class="sky sky-night">
          <span class="star star-a" />
          <span class="star star-b" />
          <span class="star star-c" />
        </span>
      </span>
      <el-icon class="icon sun"><Sunny /></el-icon>
      <el-icon class="icon moon"><Moon /></el-icon>
      <span class="knob" />
    </span>
  </button>
</template>

<style scoped>
.theme-toggle {
  --tw: 68px;
  --th: 32px;
  --ks: 28px;
  --is: 16px;

  display: inline-flex;
  flex: 0 0 auto;
  align-items: center;
  justify-content: center;
  padding: 0;
  border: 0;
  border-radius: 999px;
  background: transparent;
  cursor: pointer;
}

.track {
  position: relative;
  display: block;
  width: var(--tw);
  height: var(--th);
  border-radius: 999px;
  background: var(--surface-2);
}

/* 场景层单独裁切，滑块的光晕不会被切掉 */
.scenes {
  position: absolute;
  inset: 0;
  border-radius: inherit;
  overflow: hidden;
}

.sky {
  position: absolute;
  inset: 0;
  opacity: 0;
  transition: opacity 380ms var(--ease);
}

/* 白天：浅蓝天空 + 左下的暖阳光晕 */
.sky-day {
  background:
    radial-gradient(circle at 16% 56%, rgba(255, 203, 92, 0.55), transparent 56%),
    linear-gradient(135deg, #cfe9ff, #f3fbff);
}

/* 黑夜：深蓝夜空 + 右侧淡淡的月光 */
.sky-night {
  background:
    radial-gradient(circle at 84% 52%, rgba(148, 174, 255, 0.3), transparent 56%),
    linear-gradient(135deg, #2a3566, #141a33);
}

.theme-toggle:not(.is-dark) .sky-day {
  opacity: 1;
}

.theme-toggle.is-dark .sky-night {
  opacity: 1;
}

/* 云朵：一个胶囊 + 两个圆凸起 */
.cloud {
  position: absolute;
  height: 6px;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.92);
}

.cloud::before,
.cloud::after {
  content: '';
  position: absolute;
  border-radius: 50%;
  background: inherit;
}

.cloud-a {
  width: 18px;
  left: 52%;
  top: 24%;
}

.cloud-a::before {
  width: 8px;
  height: 8px;
  left: 3px;
  top: -3px;
}

.cloud-a::after {
  width: 6px;
  height: 6px;
  right: 2px;
  top: -2px;
}

.cloud-b {
  width: 13px;
  left: 64%;
  top: 62%;
  opacity: 0.78;
}

.cloud-b::before {
  width: 6px;
  height: 6px;
  left: 2px;
  top: -2px;
}

.cloud-b::after {
  width: 4px;
  height: 4px;
  right: 2px;
  top: -1px;
}

/* 星星：只落在滑块左侧的区域，避免被挡 */
.star {
  position: absolute;
  width: 2px;
  height: 2px;
  border-radius: 50%;
  background: #eef2ff;
  box-shadow: 0 0 4px rgba(226, 232, 255, 0.9);
  animation: twinkle 3.2s ease-in-out infinite;
}

.star-a {
  left: 15%;
  top: 28%;
}

.star-b {
  left: 32%;
  top: 66%;
  animation-delay: 0.9s;
}

.star-c {
  left: 47%;
  top: 34%;
  animation-delay: 1.7s;
}

@keyframes twinkle {
  0%,
  100% {
    opacity: 0.4;
    transform: scale(0.85);
  }

  50% {
    opacity: 1;
    transform: scale(1.2);
  }
}

.icon {
  position: absolute;
  top: 50%;
  z-index: 3;
  font-size: var(--is);
  color: var(--text-3);
  opacity: 0.5;
  transform: translateY(-50%) scale(0.9);
  transition: color 320ms var(--ease), opacity 320ms var(--ease),
    transform 320ms var(--ease-spring);
  pointer-events: none;
}

.sun {
  left: calc((var(--th) - var(--is)) / 2);
}

.moon {
  right: calc((var(--th) - var(--is)) / 2);
}

.knob {
  position: absolute;
  top: 2px;
  left: 2px;
  z-index: 2;
  width: var(--ks);
  height: var(--ks);
  border-radius: 50%;
  background: linear-gradient(140deg, #ffffff, #fff3d6);
  box-shadow: 0 1px 3px rgba(15, 23, 42, 0.24), inset 0 1px 0 rgba(255, 255, 255, 0.9);
  transition: transform 340ms var(--ease-spring), box-shadow var(--dur) var(--ease);
}

/* 深色渐变放在伪元素上，靠透明度过渡，避免 background-image 无法插值 */
.knob::before {
  content: '';
  position: absolute;
  inset: 0;
  border-radius: inherit;
  background: linear-gradient(140deg, #5266c4, #2b3560);
  opacity: 0;
  transition: opacity 320ms var(--ease);
}

.theme-toggle.is-dark .knob {
  transform: translateX(calc(var(--tw) - var(--ks) - 4px));
  box-shadow: 0 0 12px rgba(122, 162, 247, 0.4), inset 0 1px 0 rgba(255, 255, 255, 0.18);
}

.theme-toggle.is-dark .knob::before {
  opacity: 1;
}

/* 当前模式那一侧的图标抬亮并落在滑块上 */
.theme-toggle:not(.is-dark) .sun {
  color: var(--accent-warn);
  opacity: 1;
  transform: translateY(-50%) scale(1);
}

.theme-toggle.is-dark .moon {
  color: #eef2ff;
  opacity: 1;
  transform: translateY(-50%) scale(1);
}

/* 描边压在场景之上 */
.track::after {
  content: '';
  position: absolute;
  inset: 0;
  z-index: 4;
  border-radius: inherit;
  box-shadow: inset 0 0 0 1px var(--line-strong);
  pointer-events: none;
  transition: box-shadow var(--dur) var(--ease);
}

.theme-toggle:hover .track::after {
  box-shadow: inset 0 0 0 1px rgba(148, 163, 184, 0.45);
}

.theme-toggle:focus-visible {
  outline: none;
}

.theme-toggle:focus-visible .track {
  box-shadow: 0 0 0 2px var(--surface-1), 0 0 0 4px var(--focus-line);
}

@media (prefers-reduced-motion: reduce) {
  .sky,
  .icon,
  .knob,
  .knob::before {
    transition: none;
  }

  .star {
    animation: none;
  }
}
</style>
