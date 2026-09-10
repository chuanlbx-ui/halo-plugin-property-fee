<template>
  <div class="pf-view">
    <VPageHeader title="系统配置">
      <template #actions>
        <VButton type="primary" :loading="saving" :disabled="loading" @click="save">
          <template #icon><IconSave /></template>
          保存配置
        </VButton>
      </template>
    </VPageHeader>

    <VAlert
      class="pf-card-gap"
      type="info"
      title="密钥安全说明"
      description="短信 SecretKey 与公众号 AppSecret 属敏感凭据：后端加密存储，接口不回传明文。留空表示保留现有密钥不修改；如需彻底清除，请打开「清除该密钥」开关后再保存。"
    />

    <VLoading v-if="loading" />

    <template v-else>
      <!-- 前台缴费页 -->
      <VCard class="pf-card-gap" title="前台缴费页">
        <div class="pf-form-grid">
          <div class="pf-form-grid__full">
            <FormKit
              v-model="form.frontUrl"
              name="frontUrl"
              label="前台缴费页地址"
              type="text"
              placeholder="留空自动推导（当前站点内置缴费页 /apis/api.propertyfee.halo.run/v1alpha1/property-fee-page）"
              help="管理后台顶部的「前台缴费页」按钮与微信登录回跳均使用此地址。留空时自动使用插件内置页面，迁移到任何 Halo 系统均可直接访问。"
            />
          </div>
        </div>
      </VCard>

      <!-- 短信通道（腾讯云）-->
      <VCard class="pf-card-gap" title="短信验证码（腾讯云）">
        <div class="pf-form-grid">
          <div class="pf-form-grid__full">
            <FormKit
              v-model="form.smsEnabled"
              name="smsEnabled"
              label="启用真实短信通道"
              type="checkbox"
              help="关闭时前台使用万能码 123456（仅开发测试）。"
            />
          </div>
          <div>
            <FormKit
              v-model="form.smsSecretId"
              name="smsSecretId"
              label="SecretId"
              type="text"
              placeholder="腾讯云 SecretId"
            />
          </div>
          <div>
            <FormKit
              v-model="form.smsSdkAppId"
              name="smsSdkAppId"
              label="SDKAppID"
              type="text"
              placeholder="短信应用 SDKAppID"
            />
          </div>
          <div class="pf-form-grid__full">
            <FormKit
              v-model="form.smsSecretKey"
              name="smsSecretKey"
              label="SecretKey"
              type="text"
              :disabled="clearSmsSecretKey"
              :placeholder="smsSecretKeySet ? '已配置，留空表示不修改' : '未配置，填写后保存'"
              help="仅保存时上传，保存后接口不再回传明文。"
            />
            <div class="pf-secret-clear">
              <VSwitch v-model="clearSmsSecretKey" />
              <span class="pf-secret-clear__label">清除该密钥</span>
              <span class="pf-secret-clear__hint">
                {{
                  smsSecretKeySet
                    ? '勾选后保存将清空已配置的 SecretKey'
                    : '当前未配置任何 SecretKey'
                }}
              </span>
            </div>
          </div>
          <div>
            <FormKit
              v-model="form.smsSignName"
              name="smsSignName"
              label="短信签名"
              type="text"
              placeholder="如：文山市文笔塔商贸"
            />
          </div>
          <div>
            <FormKit
              v-model="form.smsTemplateId"
              name="smsTemplateId"
              label="验证码模板 ID"
              type="text"
              placeholder="短信模板 ID"
            />
          </div>
        </div>
      </VCard>

      <!-- 微信一键登录（服务号）-->
      <VCard class="pf-card-gap" title="微信一键登录（服务号）">
        <div class="pf-form-grid">
          <div class="pf-form-grid__full">
            <FormKit
              v-model="form.wxEnabled"
              name="wxEnabled"
              label="启用微信一键登录"
              type="checkbox"
              help="需公众号已配置「网页授权域名」，且该域名 nginx 将回调路径反代到本插件。"
            />
          </div>
          <div>
            <FormKit
              v-model="form.wxAppId"
              name="wxAppId"
              label="服务号 AppID"
              type="text"
              placeholder="wx 开头的公众号 AppID"
            />
          </div>
          <div>
            <FormKit
              v-model="form.wxRedirectBase"
              name="wxRedirectBase"
              label="微信回调域名基础"
              type="text"
              placeholder="如：https://aiedu.yn.cn"
            />
          </div>
          <div class="pf-form-grid__full">
            <FormKit
              v-model="form.wxAppSecret"
              name="wxAppSecret"
              label="服务号 AppSecret"
              type="text"
              :disabled="clearWxAppSecret"
              :placeholder="wxAppSecretSet ? '已配置，留空表示不修改' : '未配置，填写后保存'"
              help="仅保存时上传，保存后接口不再回传明文。"
            />
            <div class="pf-secret-clear">
              <VSwitch v-model="clearWxAppSecret" />
              <span class="pf-secret-clear__label">清除该密钥</span>
              <span class="pf-secret-clear__hint">
                {{
                  wxAppSecretSet ? '勾选后保存将清空已配置的 AppSecret' : '当前未配置任何 AppSecret'
                }}
              </span>
            </div>
          </div>
        </div>
      </VCard>
    </template>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import {
  VAlert,
  VButton,
  VCard,
  VLoading,
  VPageHeader,
  VSwitch,
  Toast,
  IconSave,
} from '@halo-dev/components'
import axios, { API_BASE, errMsg } from '@/utils/api'

/** 非敏感字段 + 密钥输入框的本地草稿（密钥初始为空，提交前不下发明文） */
const form = reactive({
  frontUrl: '',
  smsEnabled: false,
  smsSecretId: '',
  smsSdkAppId: '',
  smsSignName: '',
  smsTemplateId: '',
  smsSecretKey: '',
  wxEnabled: false,
  wxAppId: '',
  wxRedirectBase: '',
  wxAppSecret: '',
})

/** 后端只回传「是否已配置」，不回传密钥明文 */
const smsSecretKeySet = ref(false)
const wxAppSecretSet = ref(false)

/** 「清除该密钥」开关：勾选后提交空串，明确指示后端清空该密钥 */
const clearSmsSecretKey = ref(false)
const clearWxAppSecret = ref(false)

const loading = ref(false)
const saving = ref(false)

async function load() {
  loading.value = true
  try {
    const res = await axios.get(`${API_BASE}/systemconfig`)
    const s = res.data?.spec || {}
    form.frontUrl = s.frontUrl || ''
    form.smsEnabled = !!s.smsEnabled
    form.smsSecretId = s.smsSecretId || ''
    form.smsSdkAppId = s.smsSdkAppId || ''
    form.smsSignName = s.smsSignName || ''
    form.smsTemplateId = s.smsTemplateId || ''
    form.wxEnabled = !!s.wxEnabled
    form.wxAppId = s.wxAppId || ''
    form.wxRedirectBase = s.wxRedirectBase || ''
    // 接口不回传明文：密钥输入框始终留空，仅以 *Set 标记是否已配置
    form.smsSecretKey = ''
    form.wxAppSecret = ''
    clearSmsSecretKey.value = false
    clearWxAppSecret.value = false
    smsSecretKeySet.value = !!s.smsSecretKeySet
    wxAppSecretSet.value = !!s.wxAppSecretSet
  } catch (e) {
    // 未创建过配置：保留默认空值即可
    Toast.error(errMsg(e, '系统配置加载失败'))
  } finally {
    loading.value = false
  }
}

/**
 * 密钥字段提交值：
 *  - 勾选「清除该密钥」→ 空串（后端据此清空）；
 *  - 未填写 → null（后端据此保留旧值，避免误清空）；
 *  - 填写了新值 → 提交新值（后端加密落库）。
 */
function resolveSecret(input: string, clear: boolean): string | null {
  if (clear) return ''
  const value = input ?? ''
  return value.trim() ? value : null
}

async function save() {
  saving.value = true
  try {
    // 新契约：仅支持请求体 JSON（旧的 ?data=base64url 传参已废弃）
    // 请求体即 SystemConfigSpec 本身，且不携带 *Set 只读字段。
    const payload: Record<string, unknown> = {
      frontUrl: form.frontUrl,
      smsEnabled: form.smsEnabled,
      smsSecretId: form.smsSecretId,
      smsSdkAppId: form.smsSdkAppId,
      smsSignName: form.smsSignName,
      smsTemplateId: form.smsTemplateId,
      wxEnabled: form.wxEnabled,
      wxAppId: form.wxAppId,
      wxRedirectBase: form.wxRedirectBase,
      smsSecretKey: resolveSecret(form.smsSecretKey, clearSmsSecretKey.value),
      wxAppSecret: resolveSecret(form.wxAppSecret, clearWxAppSecret.value),
    }
    await axios.put(`${API_BASE}/systemconfig`, payload, {
      headers: { 'Content-Type': 'application/json' },
    })
    Toast.success('系统配置已保存')
    await load()
  } catch (e) {
    Toast.error(errMsg(e, '保存失败'))
  } finally {
    saving.value = false
  }
}

onMounted(load)
</script>
