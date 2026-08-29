<template>
  <div>
    <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px">
      <h2 style="margin: 0">💳 微信商户配置</h2>
      <button style="padding: 6px 16px; background: #389e0d; color: #fff; border: none; border-radius: 6px; cursor: pointer" @click="showAdd = true">
        ➕ 新增配置
      </button>
    </div>
    <p style="color: #888; font-size: 13px; margin: 0 0 12px">
      每个小区绑定独立的微信支付商户号，缴费时自动使用对应小区的商户收款。
    </p>

    <table style="width: 100%; border-collapse: collapse; background: #fff; border-radius: 10px; overflow: hidden; box-shadow: 0 2px 8px rgba(10,42,94,.06); font-size: 14px">
      <thead>
        <tr style="background: #f0f4ff; color: #0a2a5e">
          <th style="padding: 10px; text-align: left">小区</th>
          <th style="padding: 10px; text-align: center">AppID</th>
          <th style="padding: 10px; text-align: center">商户号</th>
          <th style="padding: 10px; text-align: center">证书序列号</th>
          <th style="padding: 10px; text-align: center">回调地址</th>
          <th style="padding: 10px; text-align: center">操作</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="c in items" :key="c.metadata.name">
          <td style="padding: 10px; border-top: 1px solid #eef1f8">{{ c.spec.community }}</td>
          <td style="padding: 10px; text-align: center; border-top: 1px solid #eef1f8">{{ c.spec.appId || '-' }}</td>
          <td style="padding: 10px; text-align: center; border-top: 1px solid #eef1f8">{{ c.spec.mchId }}</td>
          <td style="padding: 10px; text-align: center; border-top: 1px solid #eef1f8">{{ c.spec.mchSerialNo || '-' }}</td>
          <td style="padding: 10px; text-align: center; border-top: 1px solid #eef1f8; font-size: 12px; word-break: break-all">{{ c.spec.notifyUrl || '-' }}</td>
          <td style="padding: 10px; text-align: center; border-top: 1px solid #eef1f8">
            <button style="color: #cf1322; background: none; border: none; cursor: pointer; font-size: 13px" @click="remove(c)">删除</button>
          </td>
        </tr>
        <tr v-if="!items.length">
          <td colspan="6" style="padding: 30px; text-align: center; color: #999">暂无商户配置，请新增</td>
        </tr>
      </tbody>
    </table>

    <!-- 新增弹窗 -->
    <div v-if="showAdd" style="position: fixed; inset: 0; background: rgba(0,0,0,.4); display: flex; align-items: center; justify-content: center; z-index: 1000">
      <div style="background: #fff; border-radius: 12px; padding: 24px; width: 560px; max-width: 92vw; max-height: 90vh; overflow-y: auto">
        <h3 style="margin: 0 0 12px">➕ 新增商户配置</h3>
        <div style="display: grid; gap: 10px">
          <input v-model="form.community" placeholder="小区名称 *" style="padding: 8px 10px; border: 1px solid #d0d7e2; border-radius: 6px" />
          <input v-model="form.appId" placeholder="微信公众号 AppID" style="padding: 8px 10px; border: 1px solid #d0d7e2; border-radius: 6px" />
          <input v-model="form.mchId" placeholder="微信商户号 mch_id *" style="padding: 8px 10px; border: 1px solid #d0d7e2; border-radius: 6px" />
          <input v-model="form.apiV3Key" placeholder="APIv3 密钥（32位）" style="padding: 8px 10px; border: 1px solid #d0d7e2; border-radius: 6px" />
          <input v-model="form.mchSerialNo" placeholder="商户证书序列号" style="padding: 8px 10px; border: 1px solid #d0d7e2; border-radius: 6px" />
          <textarea v-model="form.mchPrivateKey" placeholder="商户私钥 PEM 内容（apiclient_key.pem 全文）" rows="4" style="padding: 8px 10px; border: 1px solid #d0d7e2; border-radius: 6px; font-family: monospace; font-size: 12px"></textarea>
          <input v-model="form.notifyUrl" placeholder="支付回调地址（默认 https://wenbita.cn/apis/api.propertyfee.halo.run/v1alpha1/feerecords/notify）" style="padding: 8px 10px; border: 1px solid #d0d7e2; border-radius: 6px" />
        </div>
        <div style="display: flex; justify-content: flex-end; gap: 8px; margin-top: 16px">
          <button style="padding: 8px 16px; background: #bbb; color: #fff; border: none; border-radius: 6px; cursor: pointer" @click="showAdd = false">取消</button>
          <button style="padding: 8px 16px; background: #389e0d; color: #fff; border: none; border-radius: 6px; cursor: pointer" @click="add">保存</button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import axios from 'axios'

const API_BASE = '/apis/console.api.propertyfee.halo.run/v1alpha1'
const items = ref<any[]>([])
const showAdd = ref(false)
const form = ref({ community: '', appId: '', mchId: '', apiV3Key: '', mchSerialNo: '', mchPrivateKey: '', notifyUrl: '' })

async function load() {
  try {
    const res = await axios.get(`${API_BASE}/paymentconfigs`)
    items.value = res.data.items || []
  } catch (e: any) {
    alert('加载失败: ' + (e.response?.data?.message || e.message))
  }
}

async function add() {
  if (!form.value.community || !form.value.mchId) {
    alert('请填写小区名称和商户号')
    return
  }
  try {
    await axios.post(`${API_BASE}/paymentconfigs`, {
      apiVersion: 'propertyfee.halo.run/v1alpha1',
      kind: 'PaymentConfig',
      spec: { ...form.value },
    })
    showAdd.value = false
    form.value = { community: '', appId: '', mchId: '', apiV3Key: '', mchSerialNo: '', mchPrivateKey: '', notifyUrl: '' }
    load()
  } catch (e: any) {
    alert('保存失败: ' + (e.response?.data?.message || e.message))
  }
}

async function remove(c: any) {
  if (!confirm(`确认删除 ${c.spec.community} 的商户配置？`)) return
  try {
    await axios.delete(`${API_BASE}/paymentconfigs/${c.metadata.name}`)
    load()
  } catch (e: any) {
    alert('删除失败: ' + (e.response?.data?.message || e.message))
  }
}

onMounted(load)
</script>
