<template>
  <el-dialog
    :model-value="modelValue"
    :title="step === 1 ? '新建知识库 · 步骤 1 建库' : '新建知识库 · 步骤 2 上传文档（可选）'"
    width="700px"
    align-center
    @update:model-value="(v) => $emit('update:modelValue', v)"
    @closed="reset"
  >
    <div class="steps">
      <span class="step" :class="{ on: step >= 1, cur: step === 1 }">1. 建库</span>
      <span class="step-sep" />
      <span class="step" :class="{ on: step >= 2, cur: step === 2 }">2. 上传文档</span>
    </div>

    <!-- 步骤 1：建库 -->
    <div v-if="step === 1" class="form-grid">
      <div class="span-2">
        <label class="form-label">知识库名称 *</label>
        <div class="field"><input v-model="form.name" placeholder="如：员工手册" maxlength="80" /></div>
      </div>
      <div class="span-2">
        <label class="form-label">描述</label>
        <div class="field"><input v-model="form.description" placeholder="一句话描述（可空）" maxlength="500" /></div>
      </div>
      <div>
        <label class="form-label">Embedding 模型</label>
        <div class="field"><input v-model="form.embeddingModel" placeholder="留空取默认 BGE-M3" /></div>
      </div>
      <div>
        <label class="form-label">相似度阈值</label>
        <div class="field"><input v-model.number="form.similarityThreshold" type="number" step="0.05" min="0" max="1" placeholder="0.6" /></div>
      </div>
      <div>
        <label class="form-label">切片策略</label>
        <el-select v-model="form.chunkStrategy" placeholder="AUTO" style="width:100%">
          <el-option label="AUTO（自动）" value="AUTO" />
          <el-option label="RECURSIVE（递归字符）" value="RECURSIVE" />
          <el-option label="MARKDOWN_HEADER（标题层级）" value="MARKDOWN_HEADER" />
        </el-select>
      </div>
      <div>
        <label class="form-label">文档语言</label>
        <div class="field"><input v-model="form.language" placeholder="zh-CN" /></div>
      </div>
      <div>
        <label class="form-label">可被挂载到 Agent</label>
        <el-switch v-model="form.isPublic" />
      </div>
    </div>

    <!-- 步骤 2：可选上传 -->
    <div v-else>
      <UploadPanel v-if="createdKb" :kb-id="createdKb.id" @uploaded="onUploaded" />
      <div v-else class="muted">请先完成步骤 1 建库。</div>
    </div>

    <p v-if="error" class="error-text" style="margin-top:14px">{{ error }}</p>

    <template #footer>
      <button class="btn-ghost" @click="close">取消</button>
      <template v-if="step === 1">
        <button class="btn-grad" :disabled="saving" @click="submitBase">下一步</button>
      </template>
      <template v-else>
        <button class="btn-ghost" @click="step = 1">上一步</button>
        <button class="btn-grad" @click="finish">完成</button>
      </template>
    </template>
  </el-dialog>
</template>

<script setup>
import { ref, reactive } from 'vue'
import { createBase } from '../api/knowledge'
import UploadPanel from './UploadPanel.vue'

const props = defineProps({ modelValue: Boolean })
const emit = defineEmits(['update:modelValue', 'created'])

const step = ref(1)
const saving = ref(false)
const error = ref('')
const createdKb = ref(null)

const empty = () => ({
  name: '', description: '', embeddingModel: '', similarityThreshold: null,
  chunkStrategy: 'AUTO', language: 'zh-CN', isPublic: true
})
const form = reactive(empty())

function close() { emit('update:modelValue', false) }
function reset() {
  step.value = 1
  createdKb.value = null
  error.value = ''
  Object.assign(form, empty())
}

async function submitBase() {
  if (!form.name) { error.value = '请填写知识库名称'; return }
  saving.value = true
  error.value = ''
  try {
    const payload = {
      name: form.name,
      description: form.description || null,
      embeddingModel: form.embeddingModel || null,
      similarityThreshold: form.similarityThreshold != null ? Number(form.similarityThreshold) : null,
      chunkStrategy: form.chunkStrategy,
      language: form.language || 'zh-CN',
      isPublic: form.isPublic
    }
    const kb = await createBase(payload)
    createdKb.value = kb
    step.value = 2
  } catch (e) {
    error.value = e.message || '建库失败'
  } finally {
    saving.value = false
  }
}

function onUploaded() { /* 上传完成，可继续或完成 */ }
function finish() {
  emit('created', createdKb.value)
  close()
}
</script>

<style scoped>
.steps { display: flex; align-items: center; gap: 10px; margin-bottom: 20px; }
.step { font-size: 13px; font-weight: 700; color: var(--muted); padding: 4px 10px; border-radius: 999px; border: 1px solid var(--line); }
.step.on { color: var(--text); }
.step.cur { color: #14110F; background: linear-gradient(100deg, var(--accent-a), var(--accent-b)); border-color: transparent; }
.step-sep { flex: 1; height: 1px; background: var(--line); }
</style>
