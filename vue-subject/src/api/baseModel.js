import request from './request'

/** 目录全量（含未下载/失败） */
export const listBaseModels = () => request.get('/base-model/list')
/** 从 Python 训练端拉取本地文件清单并回写 DB 状态 */
export const refreshBaseModelStatus = () => request.post('/base-model/refresh-status')
/** 从 Ultralytics 官方 GitHub Release 拉取最新可选目录（同步 upsert 到 DB） */
export const syncOfficialBaseModels = () => request.post('/base-model/sync-official')
/** 触发下载：body { modelNames: string[] } */
export const downloadBaseModels = (modelNames) =>
  request.post('/base-model/download', { modelNames })
/** 启用/下架切换 */
export const toggleBaseModelEnabled = (id, enabled) =>
  request.post(`/base-model/${id}/enabled`, null, { params: { enabled } })
/** 删除目录条目（仅未下载的） */
export const deleteBaseModel = (id) => request.delete(`/base-model/${id}`)
