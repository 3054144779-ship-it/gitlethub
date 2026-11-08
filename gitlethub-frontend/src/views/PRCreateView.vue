<script setup>
import { onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { getRepo, listBranches } from '../api/repo'
import { createPullRequest } from '../api/pr'
import RepoHeader from '../components/RepoHeader.vue'

const route = useRoute()
const router = useRouter()
const owner = route.params.owner
const repo = route.params.repo

const repoInfo = ref(null)
const branches = ref([])
const formRef = ref()
const submitting = ref(false)

const form = reactive({ title: '', description: '', sourceBranch: '', targetBranch: '' })

const rules = {
  title: [{ required: true, message: '请输入标题', trigger: 'blur' }],
  sourceBranch: [{ required: true, message: '请选择源分支', trigger: 'change' }],
  targetBranch: [
    { required: true, message: '请选择目标分支', trigger: 'change' },
    {
      validator: (_rule, value, callback) =>
        value && value === form.sourceBranch
          ? callback(new Error('源分支和目标分支不能相同'))
          : callback(),
      trigger: 'change'
    }
  ]
}

async function submit() {
  await formRef.value.validate()
  submitting.value = true
  try {
    await createPullRequest(owner, repo, { ...form })
    ElMessage.success('Pull Request 已创建')
    router.push({ name: 'pulls', params: { owner, repo } })
  } catch {
    // 错误提示已由 http 拦截器统一弹出
  } finally {
    submitting.value = false
  }
}

onMounted(async () => {
  try {
    const [info, branchList] = await Promise.all([getRepo(owner, repo), listBranches(owner, repo)])
    repoInfo.value = info
    branches.value = branchList || []
    form.targetBranch = info?.defaultBranch || ''
    form.sourceBranch = branches.value.find((b) => b.branchName !== form.targetBranch)?.branchName || ''
  } catch {
    branches.value = []
  }
})
</script>

<template>
  <div class="page">
    <RepoHeader :owner="owner" :repo="repo" :repo-info="repoInfo" />

    <div class="toolbar">
      <el-button @click="router.push({ name: 'pulls', params: { owner, repo } })">返回 PR 列表</el-button>
    </div>

    <el-card>
      <h3 class="title">新建 Pull Request</h3>
      <el-form ref="formRef" :model="form" :rules="rules" label-width="100px" class="form">
        <el-form-item label="源分支" prop="sourceBranch">
          <el-select v-model="form.sourceBranch" placeholder="要合并的分支" style="width: 100%">
            <el-option v-for="b in branches" :key="b.branchName" :label="b.branchName" :value="b.branchName" />
          </el-select>
        </el-form-item>
        <el-form-item label="目标分支" prop="targetBranch">
          <el-select v-model="form.targetBranch" placeholder="合入到哪个分支" style="width: 100%">
            <el-option v-for="b in branches" :key="b.branchName" :label="b.branchName" :value="b.branchName" />
          </el-select>
        </el-form-item>
        <el-form-item label="标题" prop="title">
          <el-input v-model="form.title" placeholder="简要说明这次变更" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="form.description" type="textarea" :rows="5" placeholder="可选" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="submitting" @click="submit">创建</el-button>
          <el-button @click="router.push({ name: 'pulls', params: { owner, repo } })">取消</el-button>
        </el-form-item>
      </el-form>

      <el-alert
        v-if="branches.length < 2"
        type="info"
        :closable="false"
        title="至少需要两个分支才能创建 PR（请先用 CLI 推送一个 feature 分支）"
      />
    </el-card>
  </div>
</template>

<style scoped>
.title {
  margin: 0 0 20px;
  font-size: 18px;
}

.form {
  max-width: 560px;
}
</style>
