import request from './request'

export const listModels = () => request.get('/model')
export const uploadModel = (file) => {
  const form = new FormData()
  form.append('file', file)
  return request.post('/model/upload', form, {
    headers: { 'Content-Type': 'multipart/form-data' },
    timeout: 600000
  })
}
export const activateModel = (id) => request.post(`/model/${id}/activate`)
/** 编辑模型名称（自定义，不影响版本号与 UUID） */
export const renameModel = (id, name) => request.put(`/model/${id}/name`, { name })
/** 模型权重下载地址（浏览器直接下载，文件名 {版本号}.pt） */
export const downloadModelUrl = (id) => `/api/model/${id}/download`
export const deleteModel = (id) => request.delete(`/model/${id}`)
export const testModel = (id, file) => {
  const form = new FormData()
  form.append('file', file)
  return request.post(`/model/${id}/test`, form, {
    headers: { 'Content-Type': 'multipart/form-data' },
    timeout: 120000
  })
}
export const listModelTests = (id) => request.get(`/model/${id}/tests`)