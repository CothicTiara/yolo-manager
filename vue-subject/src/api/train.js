import request from './request'

export const createTrainTask = (data) => request.post('/train', data)
export const listTrainTasks = () => request.get('/train')
export const getTrainTask = (id) => request.get(`/train/${id}`)
export const getTrainMetrics = (id) => request.get(`/train/${id}/metrics`)
export const cancelTrainTask = (id) => request.post(`/train/${id}/cancel`)
export const deleteTrainTask = (id) => request.delete(`/train/${id}`)
/** 训练表单可选模型列表（base + trained） */
export const listBaseModels = () => request.get('/train/base-models')