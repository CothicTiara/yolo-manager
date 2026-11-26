/**
 * 轻量国际化（无第三方依赖）
 *
 * 约定：以「中文原文」作为 key —— 中文环境直接返回 key 本身，英文环境查 en.js 翻译表，
 * 查不到时回退中文，因此新增文案不会因漏译而显示成空白或 xxx.key。
 *
 * 用法：
 *   模板：<span>{{ $t('数据集管理') }}</span>
 *         <span>{{ $t('共 {total} 张图片', { total: list.length }) }}</span>
 *   脚本：import { t } from '@/i18n'  或  const { t } = useI18n()
 *
 * 默认语言为中文；用户选择持久化在 localStorage('yolo-locale')。
 */
import { computed, ref } from 'vue'
import en from './en'

export const STORAGE_KEY = 'yolo-locale'

export const LOCALES = [
  { value: 'zh', label: '中文', short: '中' },
  { value: 'en', label: 'English', short: 'EN' }
]

const DICTS = { zh: null, en }

function readStored() {
  try {
    return window.localStorage.getItem(STORAGE_KEY)
  } catch (e) {
    return null
  }
}

/** 当前语言，默认中文 */
export const locale = ref(readStored() === 'en' ? 'en' : 'zh')

function interpolate(text, params) {
  if (!params) return text
  return Object.keys(params).reduce(
    (acc, key) => acc.replace(new RegExp(`\\{${key}\\}`, 'g'), String(params[key])),
    text
  )
}

/** 翻译：中文环境原样返回；英文环境查表，缺译回退中文 */
export function t(key, params) {
  if (typeof key !== 'string') return String(key ?? '')
  const dict = DICTS[locale.value]
  const text = dict && dict[key] != null ? dict[key] : key
  return interpolate(text, params)
}

export function setLocale(value) {
  locale.value = value === 'en' ? 'en' : 'zh'
  try {
    window.localStorage.setItem(STORAGE_KEY, locale.value)
  } catch (e) {
    /* 隐私模式下写入失败只影响记忆，不影响本次切换 */
  }
  applyHtmlLang()
}

export function toggleLocale() {
  setLocale(locale.value === 'zh' ? 'en' : 'zh')
}

/** <html lang> 与 document.title 之外的浏览器级语言标记 */
export function applyHtmlLang() {
  if (typeof document !== 'undefined') {
    document.documentElement.lang = locale.value === 'zh' ? 'zh-CN' : 'en'
  }
}

/** 供 setup 内解构使用，保持与模板 $t 相同的响应性 */
export function useI18n() {
  return { t, locale, isEn: computed(() => locale.value === 'en'), setLocale, toggleLocale }
}

export default {
  install(app) {
    app.config.globalProperties.$t = t
    app.provide('i18n', { t, locale, setLocale, toggleLocale })
    applyHtmlLang()
  }
}
