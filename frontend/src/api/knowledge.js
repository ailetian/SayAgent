import axios from 'axios'
import { getToken } from '../utils/token'
import router from '../router'
import { useAuthStore } from '../stores/auth'

// K9 专用请求实例。
// 遵循 AGENTS.md §3.6「错误就地提示、禁 toast 依赖」：与全局 request 不同，本实例在错误时
// 【不】弹 ElMessage toast，由调用方组件用 .error-text 就地展示，避免 K9 页面依赖 toast。
const kbRequest = axios.create({
  baseURL: '/api',
  timeout: 30000
})

kbRequest.interceptors.request.use(
  (config) => {
    const token = getToken()
    if (token) config.headers.set('Authorization', `Bearer ${token}`)
    return config
  },
  (error) => Promise.reject(error)
)

kbRequest.interceptors.response.use(
  (response) => {
    const body = response.data
    // 统一响应盒 {code,data,message}
    if (body && typeof body === 'object' && 'code' in body) {
      if (body.code !== 0) return Promise.reject(new Error(body.message || '请求失败'))
      return body.data
    }
    return body
  },
  (error) => {
    const status = error.response?.status
    const body = error.response?.data
    const msg = (body && body.message) || error.message || '网络错误'
    if (status === 401) {
      const auth = useAuthStore()
      auth.logout()
      if (router.currentRoute.value.name !== 'login') router.push('/login')
    }
    // 不弹 toast，交由组件就地提示
    return Promise.reject(new Error(msg))
  }
)

// ===== K8 端点封装（真实后端为唯一事实源）=====

// 建库（两步创建·第一步，K8）：返回空库 KnowledgeBaseVO
export function createBase(payload) {
  return kbRequest.post('/knowledge/bases', payload)
}

// keyset 游标分页列表（§6.4）：传 lastId 翻下一页，limit 默认 20
export function listBases(params = {}) {
  return kbRequest.get('/knowledge/bases', { params })
}

// 批量上传（≤10）：items = [{ type, filename, title, content, sourceUrl }]
export function uploadBatch(kbId, items) {
  return kbRequest.post(`/knowledge/${kbId}/upload`, items)
}

// 单文档上传（M5 兼容路径，可选）
export function uploadDoc(payload) {
  return kbRequest.post('/knowledge/upload', payload)
}

// 二进制文件批量上传（PDF/DOCX/MD/TXT）：FormData 原样传文件字节，后端用 Tika 解析
// 注意：不要手动设 Content-Type: multipart/form-data —— 浏览器/XHR 不会自动补 boundary，
// 会导致服务端解析不到文件而报「系统异常」、文档建不出来（见知识库上传空列表问题）。
// 交给 axios：传 FormData 且未显式设 Content-Type 时，浏览器会自动带上正确的 boundary。
export function uploadFiles(kbId, files) {
  const form = new FormData()
  files.forEach((f) => form.append('files', f))
  return kbRequest.post(`/knowledge/${kbId}/upload-files`, form)
}

// 问答（带源 + 阈值拒答，K8 编排 K5）：{ refused, answer, refusalReason, topScore, threshold, sources }
export function askKb(kbId, payload) {
  return kbRequest.post(`/knowledge/${kbId}/ask`, payload)
}

// 体检（3 指标，K8）：HealthVO
export function healthKb(kbId) {
  return kbRequest.get(`/knowledge/${kbId}/health`)
}

// 试问台（检索预览，不调 LLM，K8）：ProbeResultVO
export function probeKb(kbId, payload) {
  return kbRequest.post(`/knowledge/${kbId}/probe`, payload)
}

// 题集打分（K8）：EvalResultVO
export function evalKb(kbId, payload) {
  return kbRequest.post(`/knowledge/${kbId}/eval`, payload)
}

// 挂载到 Agent（K8）：{ kbId }
export function mountKb(agentId, kbId) {
  return kbRequest.post(`/knowledge/${agentId}/kb-links`, { kbId })
}

// 列出某 Agent 已挂载的知识库（K8）：List<KbLinkVO>
export function listMounted(agentId) {
  return kbRequest.get(`/knowledge/${agentId}/kb-links`)
}

// 卸载（K8）
export function unmountKb(agentId, kbId) {
  return kbRequest.delete(`/knowledge/${agentId}/kb-links/${kbId}`)
}

// 兼容旧检索（可选）
export function retrieve(payload) {
  return kbRequest.post('/knowledge/retrieve', payload)
}

// ===== K11 端点封装（收口 K9 缺口①②③，路径以 KnowledgeController 注解为唯一事实源）=====

// 文档列表（K11，keyset 游标分页 §6.4）：GET /{kbId}/documents?lastId&limit
// 返回 PageVO<DocumentSummaryVO>：{ items:[{docId,title,status,chunkCount,sizeBytes,updatedAt}], nextCursor, hasMore }
export function listDocuments(kbId, params = {}) {
  return kbRequest.get(`/knowledge/${kbId}/documents`, { params })
}

// 删除文档（K11）：软删文档 + 清 PG 切片，孤儿 chunk 不再召回
export function deleteDocument(kbId, documentId) {
  return kbRequest.delete(`/knowledge/${kbId}/documents/${documentId}`)
}

// 查看/下载源文档（返回 Blob）：FILE 落盘字节 → 原始文件（PDF 内联预览）；否则回退原文文本
export function getDocumentSource(kbId, documentId) {
  return kbRequest.get(`/knowledge/${kbId}/documents/${documentId}/source`, {
    responseType: 'blob'
  })
}

// 列出某文档入库后的全部切片（按 seq 升序），供「切片预览」面板
export function getDocumentChunks(kbId, documentId) {
  return kbRequest.get(`/knowledge/${kbId}/documents/${documentId}/chunks`)
}

// 重新上传 / 更新同一篇文档（K11）：走单文档上传接口并透传 documentId，
// 后端 beginIndexing 见到 documentId 非空即转 beginUpdate（复用文档身份，撕旧切片贴新切片）。
// 注意：批量上传 POST /{kbId}/upload 不透传 documentId，重传必须走这里。
export function reuploadDoc(kbId, documentId, payload) {
  return kbRequest.post('/knowledge/upload', { kbId, documentId, ...payload })
}

// 更新知识库（K11 收口 K8 缺口②）：只覆盖非空字段
export function updateBase(id, payload) {
  return kbRequest.put(`/knowledge/bases/${id}`, payload)
}

// 删除知识库（K11 收口 K8 缺口②）：软删库 + 级联软删文档 + 清 PG 切片
export function deleteBase(id) {
  return kbRequest.delete(`/knowledge/bases/${id}`)
}

// 查询索引任务状态（K11 收口 K9 缺口③）：前端轮询上传 / 重传逐节点进度
export function getIndexingJob(kbId, jobId) {
  return kbRequest.get(`/knowledge/${kbId}/indexing-jobs/${jobId}`)
}

// 重试单条 FAILED 索引任务（K11）：从失败节点续跑
export function retryIndexingJob(kbId, jobId) {
  return kbRequest.post(`/knowledge/${kbId}/indexing-jobs/${jobId}/retry`)
}

// 批量重试某批次内所有 FAILED 任务（K11）：batchId 走 query 参数，返回实际重试条数
export function retryIndexingBatch(kbId, batchId) {
  return kbRequest.post(`/knowledge/${kbId}/indexing-jobs/retry-batch`, null, { params: { batchId } })
}

// ===== M9/T7 授权管理端点封装（POST/DELETE/GET /api/resource-access，路径以 ResourceAccessController 注解为唯一事实源）=====

// 列出某资源当前授权（管理者可见）：GET /api/resource-access?resourceType=KB&resourceId=123
export function listGrants(resourceType, resourceId) {
  return kbRequest.get('/resource-access', { params: { resourceType, resourceId } })
}

// 授权（已存在则更新，幂等）：POST /api/resource-access
export function grantAccess(payload) {
  return kbRequest.post('/resource-access', payload)
}

// 撤销授权（DELETE 带 body）：DELETE /api/resource-access
export function revokeAccess(payload) {
  return kbRequest.delete('/resource-access', { data: payload })
}

export default kbRequest
