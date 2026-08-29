<template>
  <div>
    <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px">
      <h2 style="margin: 0">🏠 房屋管理</h2>
      <div style="display: flex; gap: 8px">
        <button style="padding: 6px 16px; background: #0a2a5e; color: #fff; border: none; border-radius: 6px; cursor: pointer" @click="showImport = true">
          📥 Excel 导入
        </button>
        <button style="padding: 6px 16px; background: #389e0d; color: #fff; border: none; border-radius: 6px; cursor: pointer" @click="showAdd = true">
          ➕ 新增房屋
        </button>
      </div>
    </div>

    <!-- 搜索 -->
    <div style="margin-bottom: 16px; display: flex; gap: 8px">
      <input v-model="filter.community" placeholder="小区" style="padding: 8px 10px; border: 1px solid #d0d7e2; border-radius: 6px; width: 140px" />
      <input v-model="filter.building" placeholder="楼栋" style="padding: 8px 10px; border: 1px solid #d0d7e2; border-radius: 6px; width: 100px" />
      <input v-model="filter.room" placeholder="房号" style="padding: 8px 10px; border: 1px solid #d0d7e2; border-radius: 6px; width: 100px" />
      <button style="padding: 8px 16px; background: #1a4f9e; color: #fff; border: none; border-radius: 6px; cursor: pointer" @click="load">查询</button>
    </div>

    <table style="width: 100%; border-collapse: collapse; background: #fff; border-radius: 10px; overflow: hidden; box-shadow: 0 2px 8px rgba(10,42,94,.06); font-size: 14px">
      <thead>
        <tr style="background: #f0f4ff; color: #0a2a5e">
          <th style="padding: 10px; text-align: left">小区</th>
          <th style="padding: 10px; text-align: center">楼栋</th>
          <th style="padding: 10px; text-align: center">单元</th>
          <th style="padding: 10px; text-align: center">房号</th>
          <th style="padding: 10px; text-align: center">面积(㎡)</th>
          <th style="padding: 10px; text-align: left">业主</th>
          <th style="padding: 10px; text-align: left">手机号</th>
          <th style="padding: 10px; text-align: center">操作</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="p in filtered" :key="p.metadata.name">
          <td style="padding: 10px; border-top: 1px solid #eef1f8">{{ p.spec.community }}</td>
          <td style="padding: 10px; text-align: center; border-top: 1px solid #eef1f8">{{ p.spec.building }}</td>
          <td style="padding: 10px; text-align: center; border-top: 1px solid #eef1f8">{{ p.spec.unit || '-' }}</td>
          <td style="padding: 10px; text-align: center; border-top: 1px solid #eef1f8">{{ p.spec.room }}</td>
          <td style="padding: 10px; text-align: center; border-top: 1px solid #eef1f8">{{ p.spec.area }}</td>
          <td style="padding: 10px; border-top: 1px solid #eef1f8">{{ p.spec.ownerName || '-' }}</td>
          <td style="padding: 10px; border-top: 1px solid #eef1f8">{{ p.spec.ownerPhone || '-' }}</td>
          <td style="padding: 10px; text-align: center; border-top: 1px solid #eef1f8">
            <button style="color: #cf1322; background: none; border: none; cursor: pointer; font-size: 13px" @click="remove(p)">删除</button>
          </td>
        </tr>
        <tr v-if="!filtered.length">
          <td colspan="8" style="padding: 30px; text-align: center; color: #999">暂无房屋数据，请先导入或新增</td>
        </tr>
      </tbody>
    </table>

    <div style="margin-top: 12px; color: #999; font-size: 13px">共 {{ filtered.length }} 户</div>

    <!-- 导入弹窗 -->
    <div v-if="showImport" style="position: fixed; inset: 0; background: rgba(0,0,0,.4); display: flex; align-items: center; justify-content: center; z-index: 1000">
      <div style="background: #fff; border-radius: 12px; padding: 24px; width: 560px; max-width: 92vw">
        <h3 style="margin: 0 0 12px">📥 Excel 批量导入</h3>
        <p style="font-size: 13px; color: #666; margin: 0 0 12px">
          ① 下载模板 → ② 填好数据 → ③ 选择文件上传。模板列：小区、楼栋、单元、房号、面积、业主姓名、手机号
        </p>
        <a href="/templates/property-import-template.xlsx" download style="color: #1a4f9e; font-size: 14px">⬇️ 下载 Excel 模板</a>
        <div style="margin: 16px 0">
          <input type="file" accept=".xlsx,.xls" @change="onFileChange" style="font-size: 14px" />
        </div>
        <div v-if="importResult" style="background: #f6ffed; color: #389e0d; padding: 10px; border-radius: 6px; font-size: 14px; margin-bottom: 12px">
          {{ importResult }}
        </div>
        <div style="display: flex; justify-content: flex-end; gap: 8px">
          <button style="padding: 8px 16px; background: #bbb; color: #fff; border: none; border-radius: 6px; cursor: pointer" @click="showImport = false">关闭</button>
          <button style="padding: 8px 16px; background: #0a2a5e; color: #fff; border: none; border-radius: 6px; cursor: pointer" :disabled="!file" @click="doImport">上传导入</button>
        </div>
      </div>
    </div>

    <!-- 新增弹窗 -->
    <div v-if="showAdd" style="position: fixed; inset: 0; background: rgba(0,0,0,.4); display: flex; align-items: center; justify-content: center; z-index: 1000">
      <div style="background: #fff; border-radius: 12px; padding: 24px; width: 480px; max-width: 92vw">
        <h3 style="margin: 0 0 12px">➕ 新增房屋</h3>
        <div style="display: grid; grid-template-columns: 1fr 1fr; gap: 10px">
          <input v-model="newProp.community" placeholder="小区 *" style="padding: 8px 10px; border: 1px solid #d0d7e2; border-radius: 6px" />
          <input v-model="newProp.building" placeholder="楼栋 *" style="padding: 8px 10px; border: 1px solid #d0d7e2; border-radius: 6px" />
          <input v-model="newProp.unit" placeholder="单元" style="padding: 8px 10px; border: 1px solid #d0d7e2; border-radius: 6px" />
          <input v-model="newProp.room" placeholder="房号 *" style="padding: 8px 10px; border: 1px solid #d0d7e2; border-radius: 6px" />
          <input v-model.number="newProp.area" type="number" placeholder="面积(㎡) *" style="padding: 8px 10px; border: 1px solid #d0d7e2; border-radius: 6px" />
          <input v-model="newProp.ownerName" placeholder="业主姓名" style="padding: 8px 10px; border: 1px solid #d0d7e2; border-radius: 6px" />
          <input v-model="newProp.ownerPhone" placeholder="手机号" style="padding: 8px 10px; border: 1px solid #d0d7e2; border-radius: 6px; grid-column: span 2" />
        </div>
        <div style="display: flex; justify-content: flex-end; gap: 8px; margin-top: 16px">
          <button style="padding: 8px 16px; background: #bbb; color: #fff; border: none; border-radius: 6px; cursor: pointer" @click="showAdd = false">取消</button>
          <button style="padding: 8px 16px; background: #389e0d; color: #fff; border: none; border-radius: 6px; cursor: pointer" @click="addProperty">保存</button>
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
const filter = ref({ community: '', building: '', room: '' })
const showImport = ref(false)
const showAdd = ref(false)
const file = ref<File | null>(null)
const importResult = ref('')
const newProp = ref({ community: '', building: '', unit: '', room: '', area: 0, ownerName: '', ownerPhone: '' })

const filtered = computed(() => {
  return items.value.filter((p) => {
    const s = p.spec || {}
    return (!filter.value.community || (s.community || '').includes(filter.value.community))
      && (!filter.value.building || (s.building || '').includes(filter.value.building))
      && (!filter.value.room || (s.room || '').includes(filter.value.room))
  })
})

async function load() {
  try {
    const res = await axios.get(`${API_BASE}/properties`)
    items.value = res.data.items || []
  } catch (e: any) {
    alert('加载失败: ' + (e.response?.data?.message || e.message))
  }
}

function onFileChange(e: Event) {
  const el = e.target as HTMLInputElement
  file.value = el.files?.[0] || null
  importResult.value = ''
}

async function doImport() {
  if (!file.value) return
  try {
    // 读取 Excel 文件
    const buf = await file.value.arrayBuffer()
    const rows = await parseExcel(buf)
    if (!rows.length) {
      importResult.value = '未解析到有效数据，请检查模板格式'
      return
    }
    const res = await axios.post(`${API_BASE}/properties/import`, { rows })
    importResult.value = `✅ 导入完成：新增/更新 ${res.data.created} 条，跳过 ${res.data.skipped} 条`
    load()
  } catch (e: any) {
    importResult.value = '❌ 导入失败: ' + (e.response?.data?.message || e.message)
  }
}

// Excel 解析（前端读取 xlsx：通过 SheetJS）
async function parseExcel(buf: ArrayBuffer): Promise<any[]> {
  // 动态加载 SheetJS
  const XLSX = await loadXlsx()
  const workbook = XLSX.read(buf, { type: 'array' })
  const sheet = workbook.Sheets[workbook.SheetNames[0]]
  const data = XLSX.utils.sheet_to_json(sheet, { header: 1 })
  const rows: any[] = []
  data.forEach((row: any, idx: number) => {
    if (idx === 0) return // 跳过表头
    if (!row || !row[0]) return
    rows.push({
      community: String(row[0] || '').trim(),
      building: String(row[1] || '').trim(),
      unit: String(row[2] || '').trim(),
      room: String(row[3] || '').trim(),
      area: Number(row[4]) || 0,
      ownerName: String(row[5] || '').trim(),
      ownerPhone: String(row[6] || '').trim(),
    })
  })
  return rows
}

// SheetJS 动态加载（避免打包体积）
let xlsxPromise: Promise<any> | null = null
function loadXlsx(): Promise<any> {
  if (!xlsxPromise) {
    xlsxPromise = import('xlsx')
  }
  return xlsxPromise
}

async function addProperty() {
  if (!newProp.value.community || !newProp.value.building || !newProp.value.room) {
    alert('请填写小区、楼栋、房号')
    return
  }
  try {
    await axios.post(`${API_BASE}/properties`, {
      apiVersion: 'propertyfee.halo.run/v1alpha1',
      kind: 'Property',
      spec: { ...newProp.value },
    })
    showAdd.value = false
    newProp.value = { community: '', building: '', unit: '', room: '', area: 0, ownerName: '', ownerPhone: '' }
    load()
  } catch (e: any) {
    alert('保存失败: ' + (e.response?.data?.message || e.message))
  }
}

async function remove(p: any) {
  if (!confirm(`确认删除 ${p.spec.community} ${p.spec.building}栋 ${p.spec.room}？`)) return
  try {
    await axios.delete(`${API_BASE}/properties/${p.metadata.name}`)
    load()
  } catch (e: any) {
    alert('删除失败: ' + (e.response?.data?.message || e.message))
  }
}

onMounted(load)
</script>
