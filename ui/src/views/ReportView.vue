<template>
  <div>
    <div
      style="
        display: flex;
        justify-content: space-between;
        align-items: center;
        margin-bottom: 16px;
      "
    >
      <h2 style="margin: 0">📊 缴费报表（{{ year }}年）</h2>
      <div style="display: flex; gap: 8px; align-items: center">
        <select
          v-model.number="year"
          style="
            padding: 6px 10px;
            border: 1px solid #d0d7e2;
            border-radius: 6px;
            font-size: 14px;
          "
        >
          <option v-for="y in years" :key="y" :value="y">{{ y }}年</option>
        </select>
        <button
          style="
            padding: 6px 16px;
            background: #0a2a5e;
            color: #fff;
            border: none;
            border-radius: 6px;
            cursor: pointer;
          "
          @click="load()"
        >
          刷新
        </button>
      </div>
    </div>

    <!-- 总览卡片 -->
    <div
      style="
        display: grid;
        grid-template-columns: repeat(auto-fit, minmax(150px, 1fr));
        gap: 12px;
        margin-bottom: 20px;
      "
    >
      <div
        v-for="card in overviewCards"
        :key="card.label"
        style="
          background: #fff;
          border-radius: 10px;
          padding: 16px;
          box-shadow: 0 2px 8px rgba(10, 42, 94, 0.06);
          text-align: center;
        "
      >
        <div style="font-size: 22px; font-weight: 700; color: #0a2a5e">
          {{ card.value }}
        </div>
        <div style="font-size: 13px; color: #888; margin-top: 4px">
          {{ card.label }}
        </div>
      </div>
    </div>

    <!-- 按小区汇总 -->
    <h3 style="margin: 16px 0 10px; color: #0a2a5e">🏘️ 按小区汇总</h3>
    <table
      style="
        width: 100%;
        border-collapse: collapse;
        background: #fff;
        border-radius: 10px;
        overflow: hidden;
        box-shadow: 0 2px 8px rgba(10, 42, 94, 0.06);
        font-size: 14px;
      "
    >
      <thead>
        <tr style="background: #f0f4ff; color: #0a2a5e">
          <th style="padding: 10px; text-align: left">小区</th>
          <th style="padding: 10px; text-align: center">总户数</th>
          <th style="padding: 10px; text-align: center">已缴</th>
          <th style="padding: 10px; text-align: center">未缴</th>
          <th style="padding: 10px; text-align: center">收缴率</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="c in report?.byCommunity || []" :key="c.community">
          <td style="padding: 10px; border-top: 1px solid #eef1f8">{{ c.community }}</td>
          <td style="padding: 10px; text-align: center; border-top: 1px solid #eef1f8">
            {{ c.total }}
          </td>
          <td style="padding: 10px; text-align: center; border-top: 1px solid #eef1f8; color: #389e0d">
            {{ c.paid }}
          </td>
          <td style="padding: 10px; text-align: center; border-top: 1px solid #eef1f8; color: #cf1322">
            {{ c.unpaid }}
          </td>
          <td
            :style="{
              padding: '10px',
              textAlign: 'center',
              borderTop: '1px solid #eef1f8',
              fontWeight: 700,
              color: c.rate >= 80 ? '#389e0d' : c.rate >= 50 ? '#d48806' : '#cf1322',
            }"
          >
            {{ c.rate }}%
          </td>
        </tr>
      </tbody>
    </table>

    <!-- 按楼栋汇总 -->
    <h3 style="margin: 20px 0 10px; color: #0a2a5e">🏢 按楼栋汇总</h3>
    <table
      style="
        width: 100%;
        border-collapse: collapse;
        background: #fff;
        border-radius: 10px;
        overflow: hidden;
        box-shadow: 0 2px 8px rgba(10, 42, 94, 0.06);
        font-size: 14px;
      "
    >
      <thead>
        <tr style="background: #f0f4ff; color: #0a2a5e">
          <th style="padding: 10px; text-align: left">小区</th>
          <th style="padding: 10px; text-align: left">楼栋</th>
          <th style="padding: 10px; text-align: center">总户数</th>
          <th style="padding: 10px; text-align: center">已缴</th>
          <th style="padding: 10px; text-align: center">未缴</th>
          <th style="padding: 10px; text-align: center">收缴率</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="b in report?.byBuilding || []" :key="b.community + b.building">
          <td style="padding: 10px; border-top: 1px solid #eef1f8">{{ b.community }}</td>
          <td style="padding: 10px; border-top: 1px solid #eef1f8">{{ b.building }}栋</td>
          <td style="padding: 10px; text-align: center; border-top: 1px solid #eef1f8">
            {{ b.total }}
          </td>
          <td style="padding: 10px; text-align: center; border-top: 1px solid #eef1f8; color: #389e0d">
            {{ b.paid }}
          </td>
          <td style="padding: 10px; text-align: center; border-top: 1px solid #eef1f8; color: #cf1322">
            {{ b.unpaid }}
          </td>
          <td
            :style="{
              padding: '10px',
              textAlign: 'center',
              borderTop: '1px solid #eef1f8',
              fontWeight: 700,
              color: b.rate >= 80 ? '#389e0d' : b.rate >= 50 ? '#d48806' : '#cf1322',
            }"
          >
            {{ b.rate }}%
          </td>
        </tr>
      </tbody>
    </table>

    <!-- 缴费明细 -->
    <h3 style="margin: 20px 0 10px; color: #0a2a5e">🧾 缴费明细（{{ year }}年）</h3>
    <table
      style="
        width: 100%;
        border-collapse: collapse;
        background: #fff;
        border-radius: 10px;
        overflow: hidden;
        box-shadow: 0 2px 8px rgba(10, 42, 94, 0.06);
        font-size: 14px;
      "
    >
      <thead>
        <tr style="background: #f0f4ff; color: #0a2a5e">
          <th style="padding: 10px; text-align: left">小区/楼栋/房号</th>
          <th style="padding: 10px; text-align: left">业主</th>
          <th style="padding: 10px; text-align: center">金额(元)</th>
          <th style="padding: 10px; text-align: center">支付方式</th>
          <th style="padding: 10px; text-align: center">状态</th>
          <th style="padding: 10px; text-align: center">缴费时间</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="r in records" :key="r.metadata.name">
          <td style="padding: 10px; border-top: 1px solid #eef1f8">{{ r.spec.community }} {{ r.spec.building }}栋{{ r.spec.room }}</td>
          <td style="padding: 10px; border-top: 1px solid #eef1f8">{{ r.spec.ownerName || '-' }}</td>
          <td style="padding: 10px; text-align: center; border-top: 1px solid #eef1f8">{{ r.spec.totalAmount }}</td>
          <td style="padding: 10px; text-align: center; border-top: 1px solid #eef1f8">
            <span :style="{ padding: '2px 8px', borderRadius: '4px', fontSize: '12px', background: payTypeBg(r.spec.payType), color: payTypeColor(r.spec.payType) }">{{ payTypeName(r.spec.payType) }}</span>
          </td>
          <td style="padding: 10px; text-align: center; border-top: 1px solid #eef1f8">
            <span :style="{ color: r.spec.status === 'PAID' ? '#389e0d' : '#d48806' }">{{ r.spec.status }}</span>
          </td>
          <td style="padding: 10px; text-align: center; border-top: 1px solid #eef1f8; font-size: 12px">
            {{ r.spec.paidAt ? formatTime(r.spec.paidAt) : '-' }}
          </td>
        </tr>
        <tr v-if="!records.length">
          <td colspan="6" style="padding: 20px; text-align: center; color: #999">{{ year }}年暂无缴费记录</td>
        </tr>
      </tbody>
    </table>

    <div v-if="!report" style="text-align: center; color: #999; padding: 40px">
      加载中…
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import axios from 'axios'

const API_BASE = '/apis/console.api.propertyfee.halo.run/v1alpha1'
const year = ref(new Date().getFullYear())
const years = computed(() => {
  const y = new Date().getFullYear()
  return [y, y - 1, y + 1]
})
const report = ref<any>(null)
const records = ref<any[]>([])

const overviewCards = computed(() => {
  if (!report.value) return []
  return [
    { label: '总户数', value: report.value.totalProperties },
    { label: '已缴', value: report.value.paidProperties },
    { label: '未缴', value: report.value.unpaidProperties },
    { label: '收缴率', value: report.value.overallRate + '%' },
    { label: '已收金额(元)', value: report.value.paidAmount },
  ]
})

async function load() {
  try {
    const res = await axios.get(`${API_BASE}/reports/summary`, {
      params: { year: year.value },
    })
    report.value = res.data
  } catch (e: any) {
    alert('加载失败: ' + (e.response?.data?.message || e.message))
  }
  await loadRecords()
}

async function loadRecords() {
  try {
    const res = await axios.get(`${API_BASE}/feerecords`)
    const all = res.data.items || []
    records.value = all
      .filter((r: any) => r.spec?.year === year.value && r.spec?.status === 'PAID')
      .sort((a: any, b: any) => (b.spec?.paidAt || '').localeCompare(a.spec?.paidAt || ''))
  } catch (e: any) {
    records.value = []
  }
}

function payTypeName(t: string) {
  return {
    native: '微信扫码', jsapi: '公众号支付', alipay: '支付宝',
    offline: '线下收款', h5: 'H5支付',
  }[t] || t || '-'
}
function payTypeBg(t: string) {
  return { native: '#e6f7ff', jsapi: '#e6f7ff', alipay: '#e6f4ff', offline: '#f6ffed' }[t] || '#f0f0f0'
}
function payTypeColor(t: string) {
  return { native: '#1890ff', jsapi: '#1890ff', alipay: '#1677ff', offline: '#389e0d' }[t] || '#666'
}
function formatTime(t: string) {
  if (!t) return '-'
  return t.replace('T', ' ').substring(0, 19)
}

onMounted(load)
</script>
