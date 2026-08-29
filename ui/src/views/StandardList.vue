<template>
  <div>
    <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px">
      <h2 style="margin: 0">💰 收费标准</h2>
      <button style="padding: 6px 16px; background: #389e0d; color: #fff; border: none; border-radius: 6px; cursor: pointer" @click="showAdd = true">
        ➕ 新增标准
      </button>
    </div>
    <p style="color: #888; font-size: 13px; margin: 0 0 12px">
      年物业费 = 面积(㎡) × 单价(元/㎡·月) × 12 + 额外费用。一个小区+年份一条标准。
    </p>

    <table style="width: 100%; border-collapse: collapse; background: #fff; border-radius: 10px; overflow: hidden; box-shadow: 0 2px 8px rgba(10,42,94,.06); font-size: 14px">
      <thead>
        <tr style="background: #f0f4ff; color: #0a2a5e">
          <th style="padding: 10px; text-align: left">小区</th>
          <th style="padding: 10px; text-align: center">年份</th>
          <th style="padding: 10px; text-align: center">单价(元/㎡·月)</th>
          <th style="padding: 10px; text-align: left">额外费用</th>
          <th style="padding: 10px; text-align: center">操作</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="s in items" :key="s.metadata.name">
          <td style="padding: 10px; border-top: 1px solid #eef1f8">{{ s.spec.community }}</td>
          <td style="padding: 10px; text-align: center; border-top: 1px solid #eef1f8">{{ s.spec.year }}</td>
          <td style="padding: 10px; text-align: center; border-top: 1px solid #eef1f8">{{ s.spec.unitPrice }}</td>
          <td style="padding: 10px; border-top: 1px solid #eef1f8">
            <span v-for="f in s.spec.extraFees || []" :key="f.name" style="background: #eef4ff; color: #1a4f9e; padding: 2px 8px; border-radius: 4px; font-size: 12px; margin-right: 6px">
              {{ f.name }} ¥{{ f.amount }}/年
            </span>
            <span v-if="!(s.spec.extraFees || []).length" style="color: #999">无</span>
          </td>
          <td style="padding: 10px; text-align: center; border-top: 1px solid #eef1f8">
            <button style="color: #cf1322; background: none; border: none; cursor: pointer; font-size: 13px; margin-right: 8px" @click="remove(s)">删除</button>
          </td>
        </tr>
        <tr v-if="!items.length">
          <td colspan="5" style="padding: 30px; text-align: center; color: #999">暂无收费标准，请新增</td>
        </tr>
      </tbody>
    </table>

    <!-- 新增弹窗 -->
    <div v-if="showAdd" style="position: fixed; inset: 0; background: rgba(0,0,0,.4); display: flex; align-items: center; justify-content: center; z-index: 1000">
      <div style="background: #fff; border-radius: 12px; padding: 24px; width: 520px; max-width: 92vw">
        <h3 style="margin: 0 0 12px">➕ 新增收费标准</h3>
        <div style="display: grid; grid-template-columns: 1fr 1fr; gap: 10px">
          <input v-model="form.community" placeholder="小区 *" style="padding: 8px 10px; border: 1px solid #d0d7e2; border-radius: 6px" />
          <input v-model.number="form.year" type="number" placeholder="年份 *" style="padding: 8px 10px; border: 1px solid #d0d7e2; border-radius: 6px" />
          <input v-model.number="form.unitPrice" type="number" step="0.01" placeholder="单价(元/㎡·月) *" style="padding: 8px 10px; border: 1px solid #d0d7e2; border-radius: 6px; grid-column: span 2" />
        </div>

        <h4 style="margin: 14px 0 8px; font-size: 14px; color: #0a2a5e">额外费用项（选填）</h4>
        <div v-for="(f, i) in form.extraFees" :key="i" style="display: flex; gap: 8px; margin-bottom: 8px">
          <input v-model="f.name" placeholder="费用名称（如电梯费）" style="flex: 1; padding: 8px 10px; border: 1px solid #d0d7e2; border-radius: 6px" />
          <input v-model.number="f.amount" type="number" step="0.01" placeholder="金额(元/年)" style="width: 120px; padding: 8px 10px; border: 1px solid #d0d7e2; border-radius: 6px" />
          <button style="color: #cf1322; background: none; border: none; cursor: pointer" @click="form.extraFees.splice(i, 1)">✕</button>
        </div>
        <button style="color: #1a4f9e; background: none; border: 1px dashed #1a4f9e; padding: 6px 12px; border-radius: 6px; cursor: pointer; font-size: 13px" @click="form.extraFees.push({ name: '', amount: 0 })">
          ＋ 添加费用项
        </button>

        <div style="display: flex; justify-content: flex-end; gap: 8px; margin-top: 20px">
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
const form = ref({ community: '', year: new Date().getFullYear(), unitPrice: 0, extraFees: [] as any[] })

async function load() {
  try {
    const res = await axios.get(`${API_BASE}/feestandards`)
    items.value = res.data.items || []
  } catch (e: any) {
    alert('加载失败: ' + (e.response?.data?.message || e.message))
  }
}

async function add() {
  if (!form.value.community || !form.value.year || !form.value.unitPrice) {
    alert('请填写小区、年份、单价')
    return
  }
  try {
    await axios.post(`${API_BASE}/feestandards`, {
      apiVersion: 'propertyfee.halo.run/v1alpha1',
      kind: 'FeeStandard',
      spec: { ...form.value },
    })
    showAdd.value = false
    form.value = { community: '', year: new Date().getFullYear(), unitPrice: 0, extraFees: [] }
    load()
  } catch (e: any) {
    alert('保存失败: ' + (e.response?.data?.message || e.message))
  }
}

async function remove(s: any) {
  if (!confirm(`确认删除 ${s.spec.community} ${s.spec.year}年标准？`)) return
  try {
    await axios.delete(`${API_BASE}/feestandards/${s.metadata.name}`)
    load()
  } catch (e: any) {
    alert('删除失败: ' + (e.response?.data?.message || e.message))
  }
}

onMounted(load)
</script>
