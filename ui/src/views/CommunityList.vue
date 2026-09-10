<template>
  <div class="pf-view">
    <p class="pf-hint">
      小区与楼栋在此统一维护：房屋录入时只能从配置中选择，避免手填导致的口径不一致。
    </p>

    <!-- 工具栏：筛选 + 操作 -->
    <div class="pf-toolbar">
      <div class="pf-toolbar__fields">
        <div class="pf-filter">
          <FormKit v-model="keyword" type="text" placeholder="搜索小区名称" />
        </div>
      </div>
      <div class="pf-toolbar__actions">
        <VButton :loading="loading" @click="load">
          <template #icon><IconRefreshLine /></template>
          刷新
        </VButton>
        <VButton @click="downloadTpl">
          <template #icon><IconDownload /></template>
          下载导入模板
        </VButton>
        <VButton :loading="importing" @click="pickFile">
          <template #icon><IconRiUpload2Fill /></template>
          Excel 导入
        </VButton>
        <input
          ref="fileInput"
          type="file"
          accept=".xlsx,.xls"
          style="display: none"
          @change="onFileChange"
        />
        <VButton type="primary" @click="openCreate">
          <template #icon><IconAddCircle /></template>
          新增小区
        </VButton>
      </div>
    </div>

    <VLoading v-if="loading" />

    <VEmpty
      v-else-if="!filtered.length"
      title="暂无小区配置"
      message="先添加小区并填好楼栋（如 1栋 / A栋），再录入房屋。"
    >
      <template #actions>
        <VButton type="primary" @click="openCreate">新增小区</VButton>
      </template>
    </VEmpty>

    <div v-else class="pf-list">
      <VEntityContainer>
        <VEntity v-for="c in filtered" :key="c.metadata.name">
          <template #start>
            <VEntityField :title="c.spec?.name || '-'" :description="c.spec?.remark || ''" :max-width="240" />
            <VEntityField :width="320">
              <template #description>
                <div class="pf-tags">
                  <VTag v-for="b in c.spec?.buildings || []" :key="b">{{ b }}</VTag>
                  <span v-if="!(c.spec?.buildings || []).length" class="pf-hint pf-hint--tight">
                    未配置楼栋
                  </span>
                </div>
              </template>
            </VEntityField>
          </template>

          <template #end>
            <VEntityField :width="100">
              <template #description>
                <VStatusDot
                  :state="c.spec?.enabled === false ? 'error' : 'success'"
                  :text="c.spec?.enabled === false ? '停用' : '启用'"
                />
              </template>
            </VEntityField>
          </template>

          <template #dropdownItems>
            <VDropdownItem @click="openEdit(c)">编辑</VDropdownItem>
            <VDropdownDivider />
            <VDropdownItem type="danger" @click="del(c)">删除</VDropdownItem>
          </template>
        </VEntity>
      </VEntityContainer>
    </div>

    <!-- 新增 / 编辑小区 -->
    <VModal
      v-model:visible="showDialog"
      :title="form.metadata?.name ? '编辑小区' : '新增小区'"
      :width="620"
      mount-to-body
      layer-closable
      @close="close"
    >
      <FormKit
        ref="formRef"
        id="pf-community-form"
        type="form"
        :actions="false"
        @submit="onFormSubmit"
      >
        <div class="pf-form-grid">
          <div class="pf-form-grid__full">
            <FormKit
              v-model="form.spec.name"
              name="name"
              label="小区名称"
              type="text"
              placeholder="如：阳光花园"
              validation="required"
              :disabled="!!form.metadata?.name"
            />
          </div>

          <div class="pf-form-grid__full">
            <div class="pf-owner-card">
              <div class="pf-owner-card__head">
                <span class="pf-owner-card__title">楼栋列表</span>
                <span class="pf-hint pf-hint--tight pf-hint--inline">
                  每行一栋，将作为房屋录入时的楼栋下拉选项
                </span>
                <span class="pf-owner-card__spacer"></span>
                <VButton size="sm" @click="addBuilding">
                  <template #icon><IconAddCircle /></template>
                  添加楼栋
                </VButton>
              </div>

              <p v-if="!buildings.length" class="pf-hint pf-hint--tight">
                暂无楼栋，请点击右上角「添加楼栋」录入。
              </p>

              <div v-for="(b, i) in buildings" :key="i" class="pf-owner-grid">
                <div>
                  <FormKit
                    v-model="buildings[i]"
                    :name="`building_${i}`"
                    :label="`楼栋 ${i + 1}`"
                    type="text"
                    placeholder="如：1栋"
                    validation="required"
                  />
                </div>
                <div class="pf-owner-grid__actions">
                  <VButton size="sm" type="danger" ghost @click="buildings.splice(i, 1)">
                    移除
                  </VButton>
                </div>
              </div>
            </div>
          </div>

          <div class="pf-form-grid__full">
            <FormKit
              v-model="form.spec.enabled"
              name="enabled"
              label="启用（停用后不出现在房屋录入下拉）"
              type="checkbox"
            />
          </div>

          <div class="pf-form-grid__full">
            <FormKit
              v-model="form.spec.remark"
              name="remark"
              label="备注（选填）"
              type="text"
              placeholder="备注"
            />
          </div>
        </div>
      </FormKit>

      <template #footer>
        <div class="pf-modal-footer">
          <VButton :disabled="saving" @click="close">取消</VButton>
          <VButton type="primary" :loading="saving" @click="submitForm">保存</VButton>
        </div>
      </template>
    </VModal>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import {
  VButton,
  VDropdownDivider,
  VDropdownItem,
  VEmpty,
  VEntity,
  VEntityContainer,
  VEntityField,
  VLoading,
  VModal,
  VStatusDot,
  VTag,
  Dialog,
  Toast,
  IconAddCircle,
  IconRefreshLine,
  IconRiUpload2Fill,
} from '@halo-dev/components'
import IconDownload from '~icons/ri/download-2-line'
import axios, { API_BASE, API_VERSION, errMsg } from '@/utils/api'

const loading = ref(false)
const saving = ref(false)
const importing = ref(false)
const keyword = ref('')

const list = ref<any[]>([])
const showDialog = ref(false)
const fileInput = ref<HTMLInputElement | null>(null)
const buildings = ref<string[]>([])
const formRef = ref<any>(null)

const form = ref<any>(emptyForm())
const filtered = computed(() =>
  list.value.filter((c) => {
    if (!keyword.value) return true
    const kw = keyword.value.toLowerCase()
    const name = String(c.spec?.name || '').toLowerCase()
    const blds = (c.spec?.buildings || []).join(',').toLowerCase()
    return name.includes(kw) || blds.includes(kw)
  })
)

function emptyForm() {
  return {
    apiVersion: API_VERSION,
    kind: 'Community',
    metadata: { generateName: 'community-' },
    spec: { name: '', buildings: [], enabled: true, remark: '', sortOrder: 0 },
  }
}

async function load() {
  loading.value = true
  try {
    const res = await axios.get(`${API_BASE}/communities`)
    list.value = res.data.items || []
  } catch (e) {
    Toast.error(errMsg(e, '小区列表加载失败'))
  } finally {
    loading.value = false
  }
}

function openCreate() {
  form.value = emptyForm()
  buildings.value = ['']
  showDialog.value = true
}

function openEdit(c: any) {
  form.value = JSON.parse(JSON.stringify(c))
  if (!form.value.spec) form.value.spec = {}
  if (form.value.spec.enabled === undefined) form.value.spec.enabled = true
  buildings.value = (form.value.spec.buildings || []).length
    ? [...form.value.spec.buildings]
    : ['']
  showDialog.value = true
}

function close() {
  showDialog.value = false
  buildings.value = []
}

function addBuilding() {
  buildings.value.push('')
}

function submitForm() {
  const node = formRef.value?.node
  if (node && typeof node.submit === 'function') {
    node.submit()
    return
  }
  void save()
}

function onFormSubmit() {
  void save()
}

async function save() {
  const spec = form.value.spec
  spec.name = (spec.name || '').trim()
  if (!spec.name) {
    Toast.warning('请填写小区名称')
    return
  }
  spec.buildings = buildings.value.map((b) => (b || '').trim()).filter(Boolean)
  if (!spec.buildings.length) {
    Toast.warning('请至少填写一个楼栋')
    return
  }
  spec.enabled = !!spec.enabled

  saving.value = true
  try {
    if (form.value.metadata?.name) {
      await axios.put(`${API_BASE}/communities/${form.value.metadata.name}`, form.value)
    } else {
      await axios.post(`${API_BASE}/communities`, form.value)
    }
    Toast.success(form.value.metadata?.name ? '小区已更新' : '小区已创建')
    close()
    await load()
  } catch (e) {
    Toast.error(errMsg(e, '保存失败'))
  } finally {
    saving.value = false
  }
}

function del(c: any) {
  Dialog.warning({
    title: '删除小区',
    description: `确认删除小区「${c.spec?.name || ''}」？不影响已录入的房屋数据，但不可恢复。`,
    confirmText: '删除',
    onConfirm: async () => {
      try {
        await axios.delete(`${API_BASE}/communities/${c.metadata.name}`)
        Toast.success('小区已删除')
        await load()
      } catch (e) {
        Toast.error(errMsg(e, '删除失败'))
      }
    },
  })
}

/* ------------------------------- Excel 导入 ------------------------------- */
function pickFile() {
  fileInput.value?.click()
}

async function downloadTpl() {
  try {
    const m = await loadXlsx()
    const ws = m.utils.aoa_to_sheet([
      ['小区名称', '楼栋'],
      ['阳光花园', '1栋'],
      ['阳光花园', '2栋'],
    ])
    const wb = m.utils.book_new()
    m.utils.book_append_sheet(wb, ws, '小区楼栋')
    m.writeFile(wb, '小区楼栋导入模板.xlsx')
  } catch (e) {
    Toast.error(errMsg(e, '模板生成失败'))
  }
}

let xlsxPromise: Promise<any> | null = null
function loadXlsx(): Promise<any> {
  if (!xlsxPromise) xlsxPromise = import('xlsx')
  return xlsxPromise
}

async function onFileChange(e: Event) {
  const el = e.target as HTMLInputElement
  const file = el.files?.[0]
  if (!file) return
  try {
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
      if (i === 0 && /小区|community/i.test(community)) continue
      if (!community && !building) continue
      items.push({ community, building })
    }
    if (!items.length) {
      Toast.warning('表格为空或格式不正确，请下载模板参考')
      return
    }
    importing.value = true
    const res = await axios.post(`${API_BASE}/communities/import`, { rows: items })
    Toast.success(res.data?.message || `导入完成，共 ${items.length} 行`)
    await load()
  } catch (e) {
    Toast.error(errMsg(e, '导入失败，请检查表格格式'))
  } finally {
    importing.value = false
    el.value = ''
  }
}

onMounted(load)
</script>
