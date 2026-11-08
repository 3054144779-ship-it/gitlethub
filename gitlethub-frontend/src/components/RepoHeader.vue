<script setup>
defineProps({
  owner: { type: String, required: true },
  repo: { type: String, required: true },
  repoInfo: { type: Object, default: null }
})
</script>

<template>
  <div class="repo-header">
    <div class="repo-avatar" aria-hidden="true">
      <el-icon class="repo-avatar-icon"><Share /></el-icon>
    </div>
    <div class="repo-info">
      <div class="repo-title">
        <span class="muted">{{ owner }} /</span>
        <span class="name">{{ repo }}</span>
        <el-tag v-if="repoInfo" :type="repoInfo.visibility === 1 ? 'success' : 'info'" size="small">
          {{ repoInfo.visibility === 1 ? '公开' : '私有' }}
        </el-tag>
      </div>
      <div class="repo-desc muted">
        {{ repoInfo?.description || '无描述' }}
      </div>
      <div v-if="repoInfo?.defaultBranch" class="repo-meta">
        <span class="meta-chip">
          <span class="meta-dot" />
          默认分支 {{ repoInfo.defaultBranch }}
        </span>
      </div>
    </div>
  </div>
</template>

<style scoped>
.repo-header {
  position: relative;
  display: flex;
  align-items: flex-start;
  gap: 18px;
  padding: 16px 0 24px;
  margin-bottom: 18px;
}

.repo-avatar {
  position: relative;
  flex: 0 0 auto;
  width: 56px;
  height: 56px;
  border-radius: 14px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #fff;
  background: linear-gradient(135deg, oklch(0.54 0.19 274 / 0.95), oklch(0.68 0.12 230 / 0.85));
  box-shadow: inset 0 1px 0 oklch(1 0 0 / 0.18), 0 10px 26px -10px oklch(0.25 0.05 260 / 0.5);
}

.repo-avatar-icon {
  font-size: 26px;
}

.repo-info {
  min-width: 0;
}

.repo-title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 24px;
}

.repo-title .name {
  font-weight: 650;
  color: var(--text-0);
}

.repo-desc {
  margin-top: 6px;
  font-size: 14px;
}

.repo-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 10px;
}

.meta-chip {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 3px 10px;
  font-size: 12px;
  color: var(--text-2);
  border-radius: 999px;
  background: var(--hover-1);
}

.meta-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--accent-ok);
  box-shadow: 0 0 8px rgba(52, 211, 153, 0.8);
}

@media (max-width: 560px) {
  .repo-header {
    gap: 12px;
  }

  .repo-avatar {
    width: 46px;
    height: 46px;
    border-radius: 13px;
    font-size: 21px;
  }

  .repo-title {
    font-size: 20px;
    flex-wrap: wrap;
  }
}
</style>
