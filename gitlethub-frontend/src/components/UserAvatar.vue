<script setup>
import { computed } from 'vue'
import { useAuthStore } from '../stores/auth'

const props = defineProps({
  size: { type: Number, default: 24 }
})

const auth = useAuthStore()
const initial = computed(() => (auth.username || '?').slice(0, 1).toUpperCase())
const avatar = computed(() => auth.avatar || '')
const fontSize = computed(() => `${Math.round(props.size * 0.44)}px`)
</script>

<template>
  <span
    class="user-avatar"
    :style="{ width: `${size}px`, height: `${size}px`, fontSize }"
    aria-hidden="true"
  >
    <img v-if="avatar" :src="avatar" alt="" />
    <template v-else>{{ initial }}</template>
  </span>
</template>

<style scoped>
.user-avatar {
  display: inline-flex;
  flex: 0 0 auto;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  overflow: hidden;
  background: var(--grad-primary);
  color: #fff;
  font-weight: 650;
  line-height: 1;
  user-select: none;
}

.user-avatar img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}
</style>
