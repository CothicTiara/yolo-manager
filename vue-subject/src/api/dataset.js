import request from './request'

/** 数据集 */
export const createDataset = (data) => request.post('/dataset', data)
export const listDatasets = () => request.get('/dataset')
export const getDataset = (id) => request.get(`/dataset/${id}`)
export const deleteDataset = (id) => request.delete(`/dataset/${id}`)

/** 图片 */
export const uploadImage = (datasetId, file) => {
  const form = new FormData()
  form.append('file', file)
  return request.post(`/dataset/${datasetId}/upload`, form, {
    headers: { 'Content-Type': 'multipart/form-data' }
  })
}
export const listImages = (datasetId) => request.get(`/dataset/${datasetId}/images`)
export const deleteImage = (datasetId, imageId) => request.delete(`/dataset/${datasetId}/images/${imageId}`)
/** 启动异步导出（返回进度快照） */
export const exportLabels = (datasetId) => request.post(`/dataset/${datasetId}/export`)
/** 轮询导出进度 */
export const getExportStatus = (datasetId) => request.get(`/dataset/${datasetId}/export-status`)
/** 启动异步导入（返回进度快照）；大文件上传不设超时 */
export const importDataset = (datasetId, file, format = 'AUTO', jsonPath) => {
  const form = new FormData()
  form.append('file', file)
  form.append('format', format)
  if (jsonPath) form.append('jsonPath', jsonPath)
  return request.post(`/dataset/${datasetId}/import`, form, {
    headers: { 'Content-Type': 'multipart/form-data' },
    timeout: 0
  })
}
/** 轮询导入进度 */
export const getImportStatus = (datasetId) => request.get(`/dataset/${datasetId}/import-status`)

/** 标签 */
export const listLabels = (datasetId) => request.get(`/dataset/${datasetId}/label`)
export const saveLabel = (datasetId, data) => request.post(`/dataset/${datasetId}/label`, data)
/** 重命名标签：全部已有标注自动使用新名称 */
export const renameLabel = (datasetId, labelId, name) => request.put(`/dataset/${datasetId}/label/${labelId}`, { name })
export const deleteLabel = (datasetId, labelId) => request.delete(`/dataset/${datasetId}/label/${labelId}`)

/** 标注 */
export const saveAnnotation = (data, annotationId) =>
  request.post('/annotation', data, { params: annotationId ? { annotationId } : {} })
export const getAnnotations = (imageId) => request.get(`/annotation/${imageId}`)
export const deleteAnnotation = (annotationId) => request.delete(`/annotation/${annotationId}`)
/** 批量同步某张图片的全部标注（draft 保存） */
export const syncAnnotations = (imageId, items) => request.post(`/annotation/sync/${imageId}`, items)