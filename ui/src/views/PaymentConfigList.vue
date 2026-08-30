<template>
  <div>
    <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px">
      <h2 style="margin: 0">💳 支付渠道配置</h2>
      <button style="padding: 6px 16px; background: #389e0d; color: #fff; border: none; border-radius: 6px; cursor: pointer" @click="openAdd">
        ➕ 新增渠道
      </button>
    </div>
    <p style="color: #888; font-size: 13px; margin: 0 0 12px">
      每个小区可绑定多个支付渠道（微信扫码/公众号/支付宝/线下收款），前台缴费时自动列出可用渠道。微信渠道需配置商户号+APIv3密钥+私钥。
    </p>

    <table style="width: 100%; border-collapse: collapse; background: #fff; border-radius: 10px; overflow: hidden; box-shadow: 0 2px 8px rgba(10,42,94,.06); font-size: 14px">
      <thead>
        <tr style="background: #f0f4ff; color: #0a2a5e">
          <th style="padding: 10px; text-align: left">小区</th>
          <th style="padding: 10px; text-align: center">渠道类型</th>
          <th style="padding: 10px; text-align: center">渠道名称</th>
          <th style="padding: 10px; text-align: center">商户号</th>
          <th style="padding: 10px; text-align: center">默认</th>
          <th style="padding: 10px; text-align: center">状态</th>
          <th style="padding: 10px; text-align: center">操作</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="c in items" :key="c.metadata.name">
          <td style="padding: 10px; border-top: 1px solid #eef1f8">{{ c.spec.community }}</td>
          <td style="padding: 10px; text-align: center; border-top: 1px solid #eef1f8">
            <span :style="{ background: typeBg(c.spec.channelType), color: typeColor(c.spec.channelType), padding: '2px 8px', borderRadius: '4px', fontSize: '12px' }">{{ typeName(c.spec.channelType) }}</span>
          </td>
          <td style="padding: 10px; text-align: center; border-top: 1px solid #eef1f8">{{ c.spec.channelName || c.spec.channelType }}</td>
          <td style="padding: 10px; text-align: center; border-top: 1px solid #eef1f8">{{ c.spec.mchId || '-' }}</td>
          <td style="padding: 10px; text-align: center; border-top: 1px solid #eef1f8">{{ c.spec.isDefault ? '⭐' : '' }}</td>
          <td style="padding: 10px; text-align: center; border-top: 1px solid #eef1f8">
            <span :style="{ color: c.spec.enabled === false ? '#999' : '#389e0d' }">{{ c.spec.enabled === false ? '停用' : '启用' }}</span>
          </td>
          <td style="padding: 10px; text-align: center; border-top: 1px solid #eef1f8">
            <button style="color: #1a4f9e; background: none; border: none; cursor: pointer; font-size: 13px; margin-right: 8px" @click="openEdit(c)">编辑</button>
            <button style="color: #cf1322; background: none; border: none; cursor: pointer; font-size: 13px" @click="remove(c)">删除</button>
          </td>
        </tr>
        <tr v-if="!items.length">
          <td colspan="7" style="padding: 30px; text-align: center; color: #999">暂无支付渠道，请新增</td>
        </tr>
      </tbody>
    </table>

    <!-- 新增/编辑弹窗 -->
    <div v-if="showAdd" style="position: fixed; inset: 0; background: rgba(0,0,0,.4); display: flex; align-items: center; justify-content: center; z-index: 1000">
      <div style="background: #fff; border-radius: 12px; padding: 24px; width: 620px; max-width: 94vw; max-height: 90vh; overflow-y: auto">
        <h3 style="margin: 0 0 12px">{{ editing ? '✏️ 编辑支付渠道' : '➕ 新增支付渠道' }}</h3>
        <div style="display: grid; grid-template-columns: 1fr 1fr; gap: 10px">
          <input v-model="form.community" placeholder="小区名称 *" style="padding: 8px 10px; border: 1px solid #d0d7e2; border-radius: 6px" />
          <select v-model="form.channelType" style="padding: 8px 10px; border: 1px solid #d0d7e2; border-radius: 6px" @change="onTypeChange">
            <option value="wechat_native">微信扫码支付（Native）</option>
            <option value="wechat_jsapi">微信公众号支付（JSAPI）</option>
            <option value="alipay">支付宝当面付</option>
            <option value="offline">线下收款（现金/转账）</option>
          </select>
          <input v-model="form.channelName" placeholder="渠道名称（如：微信扫码/线下转账，前台展示用）" style="padding: 8px 10px; border: 1px solid #d0d7e2; border-radius: 6px; grid-column: span 2" />
        </div>

        <!-- 微信渠道字段 -->
        <template v-if="isWechat">
          <h4 style="margin: 14px 0 8px; font-size: 14px; color: #0a2a5e">微信支付配置</h4>
          <div style="display: grid; gap: 10px">
            <input v-model="form.appId" placeholder="微信公众号 AppID" style="padding: 8px 10px; border: 1px solid #d0d7e2; border-radius: 6px" />
            <div style="display: grid; grid-template-columns: 1fr 1fr; gap: 10px">
              <input v-model="form.mchId" placeholder="微信商户号 mch_id *" style="padding: 8px 10px; border: 1px solid #d0d7e2; border-radius: 6px" />
              <input v-model="form.mchSerialNo" placeholder="商户证书序列号" style="padding: 8px 10px; border: 1px solid #d0d7e2; border-radius: 6px" />
            </div>
            <input v-model="form.apiV3Key" placeholder="APIv3 密钥（32位）" style="padding: 8px 10px; border: 1px solid #d0d7e2; border-radius: 6px" />
            <textarea v-model="form.mchPrivateKey" placeholder="商户私钥 PEM 内容（apiclient_key.pem 全文）" rows="4" style="padding: 8px 10px; border: 1px solid #d0d7e2; border-radius: 6px; font-family: monospace; font-size: 12px"></textarea>
          </div>
        </template>

        <!-- 支付宝渠道字段 -->
        <template v-if="form.channelType === 'alipay'">
          <h4 style="margin: 14px 0 8px; font-size: 14px; color: #0a2a5e">支付宝配置</h4>
          <div style="display: grid; gap: 10px">
            <input v-model="form.alipayAppId" placeholder="支付宝开放平台 AppID" style="padding: 8px 10px; border: 1px solid #d0d7e2; border-radius: 6px" />
            <textarea v-model="form.alipayPrivateKey" placeholder="支付宝应用私钥（PKCS8 PEM）" rows="3" style="padding: 8px 10px; border: 1px solid #d0d7e2; border-radius: 6px; font-family: monospace; font-size: 12px"></textarea>
            <textarea v-model="form.alipayPublicKey" placeholder="支付宝公钥" rows="3" style="padding: 8px 10px; border: 1px solid #d0d7e2; border-radius: 6px; font-family: monospace; font-size: 12px"></textarea>
          </div>
        </template>

        <!-- 线下渠道字段 -->
        <template v-if="form.channelType === 'offline'">
          <h4 style="margin: 14px 0 8px; font-size: 14px; color: #0a2a5e">线下收款说明</h4>
          <textarea v-model="form.offlineInstruction" placeholder="线下收款方式说明（如：现金 / 银行转账 户名：文山州互联网协会 账号：XXXX，前台缴费时展示）" rows="3" style="padding: 8px 10px; border: 1px solid #d0d7e2; border-radius: 6px; width: 100%; box-sizing: border-box"></textarea>
        </template>

        <div style="display: grid; grid-template-columns: 1fr 1fr; gap: 10px; margin-top: 10px">
          <input v-model="form.notifyUrl" placeholder="支付回调地址（默认自动生成）" style="padding: 8px 10px; border: 1px solid #d0d7e2; border-radius: 6px" />
          <input v-model="form.remark" placeholder="备注" style="padding: 8px 10px; border: 1px solid #d0d7e2; border-radius: 6px" />
        </div>
        <div style="display: flex; gap: 16px; margin-top: 10px; align-items: center">
          <label style="display: flex; align-items: center; gap: 6px; font-size: 13px; color: #555">
            <input type="checkbox" v-model="form.enabled" style="width: 16px; height: 16px" /> 启用
          </label>
          <label style="display: flex; align-items: center; gap: 6px; font-size: 13px; color: #555">
            <input type="checkbox" v-model="form.isDefault" style="width: 16px; height: 16px" /> 设为默认渠道
          </label>
        </div>

        <div style="display: flex; justify-content: flex-end; gap: 8px; margin-top: 20px">
          <button style="padding: 8px 16px; background: #bbb; color: #fff; border: none; border-radius: 6px; cursor: pointer" @click="showAdd = false">取消</button>
          <button style="padding: 8px 16px; background: #389e0d; color: #fff; border: none; border-radius: 6px; cursor: pointer" @click="save">保存</button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import axios from 'axios'

const API_BASE = '/apis/console.api.propertyfee.halo.run/v1alpha1'
const items = ref<any[]>([])
const showAdd = ref(false)
const editing = ref<any>(null)

const emptyForm = () => ({
  community: '', channelType: 'wechat_native', channelName: '',
  enabled: true, isDefault: false,
  appId: '', mchId: '', apiV3Key: '', mchSerialNo: '', mchPrivateKey: '', notifyUrl: '',
  alipayAppId: '', alipayPrivateKey: '', alipayPublicKey: '',
  offlineInstruction: '', remark: '',
})
const form = ref(emptyForm())

const isWechat = computed(() => form.value.channelType === 'wechat_native' || form.value.channelType === 'wechat_jsapi')

function onTypeChange() {
  if (!form.value.channelName) {
    form.value.channelName = typeName(form.value.channelType)
  }
}

function openAdd() {
  editing.value = null
  form.value = emptyForm()
  showAdd.value = true
}

function openEdit(c: any) {
  editing.value = c
  form.value = JSON.parse(JSON.stringify(c.spec))
  showAdd.value = true
}

async function load() {
  try {
    const res = await axios.get(`${API_BASE}/paymentconfigs`)
    items.value = res.data.items || []
  } catch (e: any) {
    alert('加载失败: ' + (e.response?.data?.message || e.message))
  }
}

async function save() {
  if (!form.value.community) {
    alert('请填写小区名称')
    return
  }
  if (isWechat.value && !form.value.mchId) {
    alert('微信渠道请填写商户号')
    return
  }
  const spec: any = { ...form.value }
  // 清理不相关字段
  if (!isWechat.value) {
    delete spec.appId; delete spec.apiV3Key; delete spec.mchSerialNo; delete spec.mchPrivateKey
    if (form.value.channelType === 'offline') delete spec.mchId
  }
  if (form.value.channelType !== 'alipay') {
    delete spec.alipayAppId; delete spec.alipayPrivateKey; delete spec.alipayPublicKey
  }
  if (form.value.channelType !== 'offline') delete spec.offlineInstruction
  try {
    if (editing.value) {
      await axios.put(`${API_BASE}/paymentconfigs/${editing.value.metadata.name}`, {
        apiVersion: 'propertyfee.halo.run/v1alpha1',
        kind: 'PaymentConfig',
        metadata: { name: editing.value.metadata.name },
        spec,
      })
    } else {
      await axios.post(`${API_BASE}/paymentconfigs`, {
        apiVersion: 'propertyfee.halo.run/v1alpha1',
        kind: 'PaymentConfig',
        spec,
      })
    }
    showAdd.value = false
    load()
  } catch (e: any) {
    alert('保存失败: ' + (e.response?.data?.message || e.message))
  }
}

async function remove(c: any) {
  if (!confirm(`确认删除 ${c.spec.community} 的 ${c.spec.channelName || c.spec.channelType} 渠道？`)) return
  try {
    await axios.delete(`${API_BASE}/paymentconfigs/${c.metadata.name}`)
    load()
  } catch (e: any) {
    alert('删除失败: ' + (e.response?.data?.message || e.message))
  }
}

function typeName(t: string) {
  return {
    wechat_native: '微信扫码', wechat_jsapi: '公众号支付',
    alipay: '支付宝', offline: '线下收款',
  }[t] || t
}
function typeBg(t: string) {
  return { wechat_native: '#e6f7ff', wechat_jsapi: '#e6f7ff', alipay: '#e6f4ff', offline: '#f6ffed' }[t] || '#f0f0f0'
}
function typeColor(t: string) {
  return { wechat_native: '#1890ff', wechat_jsapi: '#1890ff', alipay: '#1677ff', offline: '#389e0d' }[t] || '#666'
}

onMounted(load)
</script>
