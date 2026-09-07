<template>
  <div>
    <!-- 说明与操作 -->
    <div style="display: flex; align-items: center; margin-bottom: 16px">
      <span style="font-size: 13px; color: #66788f">
        小区与楼栋在此统一维护：房屋录入时只能从配置中选择，避免手填不一致
      </span>
      <div style="flex: 1"></div>
      <button style="padding: 6px 16px; background: #fff; color: #389e0d; border: 1px solid #389e0d; border-radius: 6px; cursor: pointer; margin-right: 8px" @click="downloadTpl">📥 下载导入模板</button>
      <button style="padding: 6px 16px; background: #389e0d; color: #fff; border: none; border-radius: 6px; cursor: pointer; margin-right: 8px" :disabled="importing" @click="pickFile">{{ importing ? '导入中…' : '📊 导入 Excel' }}</button>
      <input ref="fileInput" type="file" accept=".xlsx,.xls" style="display:none" @change="onFileChange" />
      <button style="padding: 6px 16px; background: #0a2a5e; color: #fff; border: none; border-radius: 6px; cursor: pointer" @click="openCreate">
        ＋ 新增小区
      </button>
    </div>

    <table style="width: 100%; border-collapse: collapse; background: #fff; border-radius: 10px; overflow: hidden; box-shadow: 0 2px 8px rgba(10,42,94,.06); font-size: 14px">
      <thead>
        <tr style="background: #f0f4ff; color: #0a2a5e">
          <th style="padding: 10px; text-align: left">小区名称</th>
          <th style="padding: 10px; text-align: left">楼栋列表</th>
          <th style="padding: 10px; text-align: center">状态</th>
          <th style="padding: 10px; text-align: left">备注</th>
          <th style="padding: 10px; text-align: center">操作</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="c in list" :key="c.metadata.name">
          <td style="padding: 10px; border-top: 1px solid #eef1f8; font-weight: 600; color: #0a2a5e">{{ c.spec?.name }}</td>
          <td style="padding: 10px; border-top: 1px solid #eef1f8">
            <span v-for="b in c.spec?.buildings || []" :key="b" style="display:inline-block; background:#f0f4ff; color:#1a4f9e; padding:2px 8px; border-radius:4px; font-size:12px; margin:2px 4px 2px 0">{{ b }}</span>
            <span v-if="!(c.spec?.buildings || []).length" style="color:#99a3b3">未配置楼栋</span>
          </td>
          <td style="padding: 10px; text-align: center; border-top: 1px solid #eef1f8">
            <span :style="c.spec?.enabled === false ? 'color:#cf1322' : 'color:#389e0d'">
              {{ c.spec?.enabled === false ? '停用' : '启用' }}
            </span>
          </td>
          <td style="padding: 10px; border-top: 1px solid #eef1f8; color: #66788f">{{ c.spec?.remark || '-' }}</td>
          <td style="padding: 10px; text-align: center; border-top: 1px solid #eef1f8">
            <button style="color: #1a4f9e; background: none; border: none; cursor: pointer; font-size: 13px; margin-right: 8px" @click="openEdit(c)">编辑</button>
            <button style="color: #cf1322; background: none; border: none; cursor: pointer; font-size: 13px" @click="del(c)">删除</button>
          </td>
        </tr>
        <tr v-if="!list.length">
          <td colspan="5" style="padding: 30px; text-align: center; color: #999">
            还没有小区配置。先添加小区并填好楼栋（如 1栋 / A栋），再录入房屋
          </td>
        </tr>
      </tbody>
    </table>

    <!-- 编辑弹窗 -->
    <div v-if="showDialog" style="position: fixed; inset: 0; background: rgba(0,0,0,.4); display: flex; align-items: center; justify-content: center; z-index: 1000" @click.self="close">
      <div style="background: #fff; border-radius: 12px; width: 560px; max-width: 94vw; max-height: 88vh; overflow: auto">
        <div style="display: flex; align-items: center; padding: 16px 20px; border-bottom: 1px solid #eef1f8">
          <h3 style="margin: 0; font-size: 16px; color: #0a2a5e">{{ form.metadata?.name ? '编辑小区' : '新增小区' }}</h3>
          <div style="flex: 1"></div>
          <button style="background:none;border:none;cursor:pointer;font-size:16px;color:#999" @click="close">✕</button>
        </div>
        <div style="padding: 18px 20px">
          <div style="margin-bottom: 14px">
            <label style="display:block;font-size:13px;color:#55647a;margin-bottom:6px">小区名称 <b style="color:#cf1322">*</b></label>
            <input v-model="form.spec.name" placeholder="如：阳光花园" :disabled="!!form.metadata?.name"
              style="width:100%;padding:8px 10px;border:1px solid #d0d7e2;border-radius:6px;box-sizing:border-box" />
          </div>
          <div style="margin-bottom: 14px">
            <label style="display:block;font-size:13px;color:#55647a;margin-bottom:6px">楼栋列表 <b style="color:#cf1322">*</b></label>
            <div v-for="(b, i) in buildings" :key="i" style="display:flex;gap:6px;margin-bottom:6px">
              <input v-model="buildings[i]" placeholder="如：1栋" style="flex:1;padding:8px 10px;border:1px solid #d0d7e2;border-radius:6px" />
              <button style="padding:8px 12px;background:#fff;border:1px solid #e0655f;color:#cf1322;border-radius:6px;cursor:pointer" @click="buildings.splice(i,1)">删除</button>
            </div>
            <button style="padding:6px 12px;background:#f0f4ff;border:1px dashed #1a4f9e;color:#1a4f9e;border-radius:6px;cursor:pointer" @click="buildings.push('')">＋ 添加楼栋</button>
            <div style="color:#99a3b3;font-size:12px;margin-top:4px">每行一栋，与房屋录入时的下拉选项一致</div>
          </div>
          <div style="margin-bottom: 14px">
            <label style="display:flex;align-items:center;gap:6px;font-size:13px;color:#55647a">
              <input type="checkbox" v-model="form.spec.enabled" style="accent-color:#0a2a5e" />
              启用（停用后不出现在房屋录入下拉）
            </label>
          </div>
          <div style="margin-bottom: 14px">
            <label style="display:block;font-size:13px;color:#55647a;margin-bottom:6px">备注</label>
            <input v-model="form.spec.remark" placeholder="选填" style="width:100%;padding:8px 10px;border:1px solid #d0d7e2;border-radius:6px;box-sizing:border-box" />
          </div>
          <div style="display:flex;justify-content:flex-end;gap:10px;margin-top:18px">
            <button style="padding:8px 20px;background:#fff;border:1px solid #d0d7e2;border-radius:6px;cursor:pointer" @click="close">取消</button>
            <button style="padding:8px 20px;background:#0a2a5e;color:#fff;border:none;border-radius:6px;cursor:pointer" :disabled="saving" @click="save">{{ saving ? '保存中…' : '保存' }}</button>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import axios from 'axios'

const API_BASE = '/apis/console.api.propertyfee.halo.run/v1alpha1'
const list = ref<any[]>([])
const showDialog = ref(false)
const saving = ref(false)
const importing = ref(false)
const fileInput = ref<any>(null)
const buildings = ref<string[]>([''])
const form = ref<any>({ spec: { name: '', buildings: [], enabled: true, remark: '' } })

function emptyForm() {
  return {
    apiVersion: 'propertyfee.halo.run/v1alpha1', kind: 'Community',
    metadata: { generateName: 'community-' },
    spec: { name: '', buildings: [], enabled: true, remark: '', sortOrder: 0 },
  }
}

async function load() {
  const r = await axios.get(`${API_BASE}/communities`)
  list.value = r.data.items || []
}
function openCreate() {
  form.value = emptyForm()
  buildings.value = ['']
  showDialog.value = true
}
function openEdit(c: any) {
  form.value = JSON.parse(JSON.stringify(c))
  if (!form.value.spec) form.value.spec = {}
  buildings.value = (form.value.spec.buildings || []).length ? [...form.value.spec.buildings] : ['']
  showDialog.value = true
}
function close() { showDialog.value = false }

// ===== Excel 导入（小区名 + 楼栋；同小区多行自动合并） =====
let xlsxPromise: Promise<any> | null = null
async function loadXlsx() {
  if (!xlsxPromise) xlsxPromise = import('xlsx')
  return xlsxPromise
}
function pickFile() { fileInput.value?.click() }
function downloadTpl() {
  import('xlsx').then(m => {
    const ws = m.utils.aoa_to_sheet([['小区名称', '楼栋'], ['阳光花园', '1栋'], ['阳光花园', '2栋']])
    const wb = m.utils.book_new()
    m.utils.book_append_sheet(wb, ws, '小区楼栋')
    m.writeFile(wb, '小区楼栋导入模板.xlsx')
  })
}
async function onFileChange(e: any) {
  const file = e.target.files?.[0]
  if (!file) return
  const XLSX = await loadXlsx()
  const buf = await file.arrayBuffer()
  const wb = XLSX.read(buf, { type: 'array' })
  const sheet = wb.Sheets[wb.SheetNames[0]]
  const rows: any[][] = XLSX.utils.sheet_to_json(sheet, { header: 1 })
  const items: any[] = []
  for (let i = 0; i < rows.length; i++) {
    const r = rows[i] || []
    const community = String(r[0] ?? '').trim()
    const building = String(r[1] ?? '').trim()
    if (i === 0 && /小区|community/i.test(community)) continue // 跳过表头
    if (!community && !building) continue
    items.push({ community, building })
  }
  if (!items.length) { alert('表格为空或格式不对，请下载模板参考'); e.target.value = ''; return }
  if (!confirm(`识别到 ${items.length} 行小区/楼栋数据，确认导入？（已存在小区将自动补充楼栋）`)) { e.target.value = ''; return }
  importing.value = true
  try {
    const res = await axios.post(`${API_BASE}/communities/import`, { rows: items })
    alert(res.data?.message || '导入完成')
    await load()
  } catch (err: any) {
    alert(err.response?.data?.message || '导入失败，请检查表格格式')
  } finally {
    importing.value = false
    e.target.value = ''
  }
}
async function save() {
  const spec = form.value.spec
  spec.name = (spec.name || '').trim()
  if (!spec.name) { alert('请填写小区名称'); return }
  spec.buildings = buildings.value.map((b: string) => (b || '').trim()).filter(Boolean)
  if (!spec.buildings.length) { alert('请至少填写一个楼栋'); return }
  spec.enabled = !!spec.enabled
  saving.value = true
  try {
    if (form.value.metadata?.name) {
      await axios.put(`${API_BASE}/communities/${form.value.metadata.name}`, form.value)
    } else {
      await axios.post(`${API_BASE}/communities`, form.value)
    }
    close()
    await load()
  } catch (e: any) {
    alert(e.response?.data?.message || '保存失败')
  } finally { saving.value = false }
}
async function del(c: any) {
  if (!confirm(`删除小区「${c.spec?.name}」？不影响已录入房屋数据，不可恢复`)) return
  try {
    await axios.delete(`${API_BASE}/communities/${c.metadata.name}`)
    await load()
  } catch (e: any) { alert(e.response?.data?.message || '删除失败') }
}
onMounted(load)
</script>
