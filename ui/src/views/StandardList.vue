<template>
  <div>
    <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px">
      <h2 style="margin: 0">💰 收费标准</h2>
      <button style="padding: 6px 16px; background: #389e0d; color: #fff; border: none; border-radius: 6px; cursor: pointer" @click="openAdd">
        ➕ 新增标准
      </button>
    </div>
    <p style="color: #888; font-size: 13px; margin: 0 0 12px">
      年物业费 = 面积(㎡) × 单价(元/㎡·月) × 月数 + 额外费用 − 优惠减免。支持物业类型差异化定价、缴费周期、滞纳金。
    </p>

    <table style="width: 100%; border-collapse: collapse; background: #fff; border-radius: 10px; overflow: hidden; box-shadow: 0 2px 8px rgba(10,42,94,.06); font-size: 14px">
      <thead>
        <tr style="background: #f0f4ff; color: #0a2a5e">
          <th style="padding: 10px; text-align: left">小区</th>
          <th style="padding: 10px; text-align: center">年份</th>
          <th style="padding: 10px; text-align: center">物业类型</th>
          <th style="padding: 10px; text-align: center">单价(元/㎡·月)</th>
          <th style="padding: 10px; text-align: center">周期</th>
          <th style="padding: 10px; text-align: left">额外费用</th>
          <th style="padding: 10px; text-align: center">优惠</th>
          <th style="padding: 10px; text-align: center">状态</th>
          <th style="padding: 10px; text-align: center">操作</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="s in items" :key="s.metadata.name">
          <td style="padding: 10px; border-top: 1px solid #eef1f8">{{ s.spec.community }}</td>
          <td style="padding: 10px; text-align: center; border-top: 1px solid #eef1f8">{{ s.spec.year }}</td>
          <td style="padding: 10px; text-align: center; border-top: 1px solid #eef1f8">{{ s.spec.propertyType || '住宅' }}</td>
          <td style="padding: 10px; text-align: center; border-top: 1px solid #eef1f8">{{ s.spec.unitPrice }}</td>
          <td style="padding: 10px; text-align: center; border-top: 1px solid #eef1f8">{{ cycleName(s.spec.billingCycle) }}</td>
          <td style="padding: 10px; border-top: 1px solid #eef1f8">
            <span v-for="f in s.spec.extraFees || []" :key="f.name" style="background: #eef4ff; color: #1a4f9e; padding: 2px 8px; border-radius: 4px; font-size: 12px; margin-right: 6px">
              {{ f.name }} {{ chargeModeText(f) }}
            </span>
            <span v-if="!(s.spec.extraFees || []).length" style="color: #999">无</span>
          </td>
          <td style="padding: 10px; text-align: center; border-top: 1px solid #eef1f8">
            <span v-if="s.spec.discount" style="background: #fff7e6; color: #d48806; padding: 2px 8px; border-radius: 4px; font-size: 12px">{{ discountText(s.spec.discount) }}</span>
            <span v-else style="color: #999">无</span>
          </td>
          <td style="padding: 10px; text-align: center; border-top: 1px solid #eef1f8">
            <span :style="{ color: s.spec.enabled === false ? '#999' : '#389e0d' }">{{ s.spec.enabled === false ? '停用' : '启用' }}</span>
          </td>
          <td style="padding: 10px; text-align: center; border-top: 1px solid #eef1f8">
            <button style="color: #1a4f9e; background: none; border: none; cursor: pointer; font-size: 13px; margin-right: 8px" @click="openEdit(s)">编辑</button>
            <button style="color: #cf1322; background: none; border: none; cursor: pointer; font-size: 13px" @click="remove(s)">删除</button>
          </td>
        </tr>
        <tr v-if="!items.length">
          <td colspan="9" style="padding: 30px; text-align: center; color: #999">暂无收费标准，请新增</td>
        </tr>
      </tbody>
    </table>

    <!-- 新增/编辑弹窗 -->
    <div v-if="showAdd" style="position: fixed; inset: 0; background: rgba(0,0,0,.4); display: flex; align-items: center; justify-content: center; z-index: 1000">
      <div style="background: #fff; border-radius: 12px; padding: 24px; width: 640px; max-width: 94vw; max-height: 90vh; overflow-y: auto">
        <h3 style="margin: 0 0 12px">{{ editing ? '✏️ 编辑收费标准' : '➕ 新增收费标准' }}</h3>
        <div style="display: grid; grid-template-columns: 1fr 1fr 1fr; gap: 10px">
          <input v-model="form.community" placeholder="小区 *" style="padding: 8px 10px; border: 1px solid #d0d7e2; border-radius: 6px" />
          <input v-model.number="form.year" type="number" placeholder="年份 *" style="padding: 8px 10px; border: 1px solid #d0d7e2; border-radius: 6px" />
          <select v-model="form.propertyType" style="padding: 8px 10px; border: 1px solid #d0d7e2; border-radius: 6px">
            <option value="住宅">住宅</option>
            <option value="商铺">商铺</option>
            <option value="车位">车位</option>
            <option value="其他">其他</option>
          </select>
          <input v-model.number="form.unitPrice" type="number" step="0.01" placeholder="单价(元/㎡·月) *" style="padding: 8px 10px; border: 1px solid #d0d7e2; border-radius: 6px" />
          <select v-model="form.billingCycle" style="padding: 8px 10px; border: 1px solid #d0d7e2; border-radius: 6px">
            <option value="year">按年缴（12月）</option>
            <option value="half">按半年缴（6月）</option>
            <option value="quarter">按季缴（3月）</option>
            <option value="month">按月缴（1月）</option>
          </select>
          <label style="display: flex; align-items: center; gap: 6px; font-size: 13px; color: #555">
            <input type="checkbox" v-model="form.enabled" style="width: 16px; height: 16px" /> 启用
          </label>
        </div>

        <h4 style="margin: 14px 0 8px; font-size: 14px; color: #0a2a5e">额外费用项（选填）</h4>
        <div v-for="(f, i) in form.extraFees" :key="i" style="display: flex; gap: 8px; margin-bottom: 8px">
          <input v-model="f.name" placeholder="费用名称（如电梯费）" style="flex: 1; padding: 8px 10px; border: 1px solid #d0d7e2; border-radius: 6px" />
          <select v-model="f.chargeMode" style="padding: 8px 10px; border: 1px solid #d0d7e2; border-radius: 6px; width: 130px">
            <option value="fixed">固定金额/年</option>
            <option value="perArea">按面积(元/㎡·年)</option>
            <option value="perMonth">按月(元/月)</option>
          </select>
          <input v-model.number="f.amount" type="number" step="0.01" placeholder="金额" style="width: 110px; padding: 8px 10px; border: 1px solid #d0d7e2; border-radius: 6px" />
          <button style="color: #cf1322; background: none; border: none; cursor: pointer" @click="form.extraFees.splice(i, 1)">✕</button>
        </div>
        <button style="color: #1a4f9e; background: none; border: 1px dashed #1a4f9e; padding: 6px 12px; border-radius: 6px; cursor: pointer; font-size: 13px" @click="form.extraFees.push({ name: '', amount: 0, chargeMode: 'fixed' })">
          ＋ 添加费用项
        </button>

        <h4 style="margin: 14px 0 8px; font-size: 14px; color: #0a2a5e">优惠减免（选填）</h4>
        <div style="display: flex; gap: 8px; margin-bottom: 8px">
          <select v-model="discountType" style="padding: 8px 10px; border: 1px solid #d0d7e2; border-radius: 6px; width: 150px">
            <option value="">无优惠</option>
            <option value="amount">固定减免(元)</option>
            <option value="percent">按比例减免(%)</option>
            <option value="firstYear">首年优惠(比例)</option>
          </select>
          <input v-if="discountType === 'amount'" v-model.number="form.discount.amount" type="number" step="0.01" placeholder="减免金额(元)" style="flex: 1; padding: 8px 10px; border: 1px solid #d0d7e2; border-radius: 6px" />
          <input v-if="discountType === 'percent' || discountType === 'firstYear'" v-model.number="form.discount.percent" type="number" step="0.01" min="0" max="1" placeholder="比例(0-1，如0.2=减免20%)" style="flex: 1; padding: 8px 10px; border: 1px solid #d0d7e2; border-radius: 6px" />
        </div>

        <h4 style="margin: 14px 0 8px; font-size: 14px; color: #0a2a5e">滞纳金规则（选填）</h4>
        <div style="display: grid; grid-template-columns: 1fr 1fr 1fr; gap: 8px">
          <input v-model.number="form.lateFee.graceDays" type="number" min="0" placeholder="宽限期(天)" style="padding: 8px 10px; border: 1px solid #d0d7e2; border-radius: 6px" />
          <input v-model.number="form.lateFee.dailyRate" type="number" step="0.0001" min="0" placeholder="日利率(如0.0005)" style="padding: 8px 10px; border: 1px solid #d0d7e2; border-radius: 6px" />
          <input v-model.number="form.lateFee.maxRate" type="number" step="0.01" min="0" max="1" placeholder="封顶比例(0-1，留空不封顶)" style="padding: 8px 10px; border: 1px solid #d0d7e2; border-radius: 6px" />
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
import { ref, onMounted } from 'vue'
import axios from 'axios'

const API_BASE = '/apis/console.api.propertyfee.halo.run/v1alpha1'
const items = ref<any[]>([])
const showAdd = ref(false)
const editing = ref<any>(null)
const discountType = ref('')

const emptyForm = () => ({
  community: '', year: new Date().getFullYear(), propertyType: '住宅',
  unitPrice: 0, billingCycle: 'year', enabled: true,
  extraFees: [] as any[],
  discount: { type: '', amount: 0, percent: 0 },
  lateFee: { graceDays: 0, dailyRate: 0.0005, maxRate: null as any },
})
const form = ref(emptyForm())

function openAdd() {
  editing.value = null
  form.value = emptyForm()
  discountType.value = ''
  showAdd.value = true
}

function openEdit(s: any) {
  editing.value = s
  const spec = JSON.parse(JSON.stringify(s.spec))
  form.value = {
    community: spec.community || '', year: spec.year || new Date().getFullYear(),
    propertyType: spec.propertyType || '住宅', unitPrice: spec.unitPrice || 0,
    billingCycle: spec.billingCycle || 'year', enabled: spec.enabled !== false,
    extraFees: spec.extraFees || [],
    discount: spec.discount || { type: '', amount: 0, percent: 0 },
    lateFee: spec.lateFee || { graceDays: 0, dailyRate: 0.0005, maxRate: null },
  }
  discountType.value = spec.discount?.type || ''
  showAdd.value = true
}

async function load() {
  try {
    const res = await axios.get(`${API_BASE}/feestandards`)
    items.value = res.data.items || []
  } catch (e: any) {
    alert('加载失败: ' + (e.response?.data?.message || e.message))
  }
}

async function save() {
  if (!form.value.community || !form.value.year || !form.value.unitPrice) {
    alert('请填写小区、年份、单价')
    return
  }
  const spec: any = { ...form.value }
  spec.discount = discountType.value ? { ...form.value.discount, type: discountType.value } : null
  if (!spec.discount) delete spec.discount
  if (!form.value.lateFee.graceDays && !form.value.lateFee.dailyRate) {
    delete spec.lateFee
  }
  try {
    if (editing.value) {
      await axios.put(`${API_BASE}/feestandards/${editing.value.metadata.name}`, {
        apiVersion: 'propertyfee.halo.run/v1alpha1',
        kind: 'FeeStandard',
        metadata: { name: editing.value.metadata.name, version: editing.value.metadata.version },
        spec,
      })
    } else {
      await axios.post(`${API_BASE}/feestandards`, {
        apiVersion: 'propertyfee.halo.run/v1alpha1',
        kind: 'FeeStandard',
        spec,
      })
    }
    showAdd.value = false
    load()
  } catch (e: any) {
    alert('保存失败: ' + (e.response?.data?.message || e.message))
  }
}

async function remove(s: any) {
  if (!confirm(`确认删除 ${s.spec.community} ${s.spec.year}年 ${s.spec.propertyType || '住宅'}标准？`)) return
  try {
    await axios.delete(`${API_BASE}/feestandards/${s.metadata.name}`)
    load()
  } catch (e: any) {
    alert('删除失败: ' + (e.response?.data?.message || e.message))
  }
}

function cycleName(c: string) {
  return { year: '年缴', half: '半年', quarter: '季缴', month: '月缴' }[c || 'year'] || c
}
function chargeModeText(f: any) {
  const mode = f.chargeMode || 'fixed'
  if (mode === 'perArea') return `¥${f.amount}/㎡·年`
  if (mode === 'perMonth') return `¥${f.amount}/月`
  return `¥${f.amount}/年`
}
function discountText(d: any) {
  if (d.type === 'amount') return `减${d.amount}元`
  if (d.type === 'percent') return `减${(d.percent * 100).toFixed(0)}%`
  if (d.type === 'firstYear') return `首年减${((d.percent || 0.5) * 100).toFixed(0)}%`
  return '优惠'
}

onMounted(load)
</script>
