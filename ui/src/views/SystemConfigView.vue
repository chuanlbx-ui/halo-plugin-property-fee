<template>
  <div>
    <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px">
      <h2 style="margin: 0">⚙️ 系统配置</h2>
      <button style="padding: 6px 16px; background: #389e0d; color: #fff; border: none; border-radius: 6px; cursor: pointer" @click="save">
        💾 保存配置
      </button>
    </div>
    <p style="color: #888; font-size: 13px; margin: 0 0 16px">
      平台级配置：前台缴费页地址、短信验证码通道、微信一键登录（凭据已由平台注入，此处不展示明文）。
    </p>

    <div style="background: #fff; border-radius: 10px; padding: 20px; box-shadow: 0 2px 8px rgba(10,42,94,.06)">
      <!-- 前台页面地址 -->
      <div style="margin-bottom: 18px">
        <label style="display: block; font-size: 14px; color: #0a2a5e; font-weight: 600; margin-bottom: 6px">🏠 前台缴费页地址</label>
        <input v-model="spec.frontUrl" placeholder="留空自动推导（当前站点 /apis/api.propertyfee.halo.run/v1alpha1/pages/property-fee）" style="width: 100%; box-sizing: border-box; padding: 8px 10px; border: 1px solid #d0d7e2; border-radius: 6px" />
        <div style="font-size: 12px; color: #99a3b3; margin-top: 4px">
          管理后台顶部的「前台缴费页」按钮与微信登录回跳均使用此地址。留空时自动使用插件内置页面（迁移到任何 Halo 系统均可直接访问）。
        </div>
      </div>

      <!-- 短信通道 -->
      <div style="margin-bottom: 18px">
        <label style="display: block; font-size: 14px; color: #0a2a5e; font-weight: 600; margin-bottom: 6px">📱 短信验证码</label>
        <div style="display: flex; gap: 20px; align-items: center">
          <label style="display: flex; align-items: center; gap: 6px; font-size: 13px; color: #555">
            <input type="checkbox" v-model="spec.smsEnabled" style="width: 16px; height: 16px" /> 启用真实短信（腾讯云）
          </label>
          <span style="font-size: 12px; color: #99a3b3">关闭时前台使用万能码 123456（开发测试用）。短信签名/模板由平台注入。</span>
        </div>
      </div>

      <!-- 微信登录 -->
      <div style="margin-bottom: 18px">
        <label style="display: block; font-size: 14px; color: #0a2a5e; font-weight: 600; margin-bottom: 6px">💬 微信一键登录</label>
        <div style="display: flex; gap: 20px; align-items: center; flex-wrap: wrap">
          <label style="display: flex; align-items: center; gap: 6px; font-size: 13px; color: #555">
            <input type="checkbox" v-model="spec.wxEnabled" style="width: 16px; height: 16px" /> 启用微信一键登录（服务号）
          </label>
          <input v-model="spec.wxRedirectBase" placeholder="微信回调域名基础（如 https://aiedu.yn.cn）" style="flex: 1; min-width: 240px; padding: 8px 10px; border: 1px solid #d0d7e2; border-radius: 6px" />
        </div>
        <div style="font-size: 12px; color: #99a3b3; margin-top: 4px">
          必须是公众号后台已配置网页授权的域名，且该域名 nginx 需将 /pf-wx/ 反代到本插件 wx/callback。AppID/AppSecret 由平台注入。
        </div>
      </div>

      <div v-if="msg" :style="{ color: msgType === 'ok' ? '#389e0d' : '#cf1322', fontSize: '13px', marginTop: '8px' }">{{ msg }}</div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import axios from 'axios'

const API_BASE = '/apis/console.api.propertyfee.halo.run/v1alpha1'
const spec = ref<any>({
  frontUrl: '', smsEnabled: false, smsSignName: '', wxEnabled: false, wxRedirectBase: '',
})
const msg = ref('')
const msgType = ref<'ok' | 'err'>('ok')

async function load() {
  try {
    const res = await axios.get(`${API_BASE}/systemconfig`)
    const s = res.data?.spec || {}
    spec.value = {
      frontUrl: s.frontUrl || '',
      smsEnabled: !!s.smsEnabled,
      smsSignName: s.smsSignName || '',
      wxEnabled: !!s.wxEnabled,
      wxRedirectBase: s.wxRedirectBase || '',
    }
  } catch (e: any) {
    // 未创建过配置则使用默认
  }
}

async function save() {
  msg.value = ''
  try {
    // ?data=base64url(JSON)：与后端 Base64.getUrlDecoder() 匹配（先 UTF-8 再 url-safe base64）
    const raw = encodeURIComponent(JSON.stringify(spec.value))
    const b64 = btoa(unescape(raw))
    const b64url = b64.replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
    const res = await axios.put(
      `${API_BASE}/systemconfig?data=${b64url}`,
      {},
      { headers: { 'Content-Type': 'application/json' } }
    )
    msgType.value = 'ok'
    msg.value = '✅ 系统配置已保存'
  } catch (e: any) {
    msgType.value = 'err'
    msg.value = '保存失败: ' + (e.response?.data?.message || e.message)
  }
}

onMounted(load)
</script>
