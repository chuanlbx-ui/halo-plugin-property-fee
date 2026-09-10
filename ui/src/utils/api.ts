import axios from 'axios'

/** 后端控制台 API 前缀（契约固定，前端不得更改） */
export const API_BASE = '/apis/console.api.propertyfee.halo.run/v1alpha1'

/** 扩展对象 apiVersion（契约固定，前端不得更改） */
export const API_VERSION = 'propertyfee.halo.run/v1alpha1'

/**
 * 统一从异常中提取可读的错误信息（Halo ProblemDetail 用 detail / message）。
 * 供 Toast.error 使用，替代原生 alert。
 */
export function errMsg(e: unknown, fallback = '操作失败'): string {
  const err = e as {
    response?: { data?: { message?: string; detail?: string; title?: string } }
    message?: string
  }
  const data = err?.response?.data
  return data?.message || data?.detail || data?.title || err?.message || fallback
}

export default axios
