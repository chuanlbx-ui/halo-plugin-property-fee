<template>
  <div class="pf-view">
    <!-- 工具栏：筛选 + 操作 -->
    <div class="pf-toolbar">
      <div class="pf-toolbar__fields">
        <div class="pf-filter">
          <FormKit v-model="filter.community" type="text" placeholder="小区" />
        </div>
        <div class="pf-filter">
          <FormKit v-model="filter.building" type="text" placeholder="楼栋" />
        </div>
        <div class="pf-filter">
          <FormKit v-model="filter.room" type="text" placeholder="房号" />
        </div>
        <div class="pf-filter">
          <FormKit v-model="filter.ownerName" type="text" placeholder="业主姓名" />
        </div>
      </div>
      <div class="pf-toolbar__actions">
        <VButton :loading="loading" @click="load">
          <template #icon><IconRefreshLine /></template>
          刷新
        </VButton>
        <VButton @click="downloadTemplate">
          <template #icon><IconDownload /></template>
          下载模板
        </VButton>
        <VButton @click="openImport">
          <template #icon><IconRiUpload2Fill /></template>
          Excel 导入
        </VButton>
        <VButton type="primary" @click="openAdd">
          <template #icon><IconAddCircle /></template>
          新增房屋
        </VButton>
      </div>
    </div>

    <VLoading v-if="loading" />

    <VEmpty
      v-else-if="!filtered.length"
      title="暂无房屋数据"
      message="可点击右上角「新增房屋」逐户录入，或通过 Excel 批量导入。"
    >
      <template #actions>
        <VButton type="primary" @click="openAdd">新增房屋</VButton>
      </template>
    </VEmpty>

    <template v-else>
      <div class="pf-list">
        <VEntityContainer>
          <VEntity v-for="p in pageItems" :key="p.metadata.name">
            <template #start>
              <VEntityField
                :title="houseLabel(p.spec)"
                :description="`业主：${ownerNames(p.spec)}`"
                :max-width="260"
              />
              <VEntityField :title="p.spec.propertyType || '住宅'" description="物业类型" :width="100" />
              <VEntityField
                :title="p.spec.area === undefined || p.spec.area === null ? '-' : `${p.spec.area}`"
                description="面积(㎡)"
                :width="96"
              />
              <VEntityField :title="p.spec.ownerPhone || '-'" description="手机号" :width="140" />
            </template>

            <template #end>
              <VEntityField :width="110">
                <template #description>
                  <VStatusDot
                    :state="houseStatusState(p.spec.houseStatus)"
                    :text="p.spec.houseStatus || '自住'"
                  />
                </template>
              </VEntityField>
            </template>

            <template #dropdownItems>
              <VDropdownItem @click="openEdit(p)">编辑</VDropdownItem>
              <VDropdownDivider />
              <VDropdownItem type="danger" @click="remove(p)">删除</VDropdownItem>
            </template>
          </VEntity>
        </VEntityContainer>
      </div>

      <VPagination
        class="pf-list__pagination"
        :page="page"
        :size="size"
        :total="filtered.length"
        show-total
        @update:page="onPageChange"
        @update:size="onSizeChange"
      />
    </template>

    <!-- 新增 / 编辑房屋 -->
    <VModal
      v-model:visible="showForm"
      :title="editing ? '编辑房屋' : '新增房屋'"
      :width="760"
      mount-to-body
      layer-closable
      @close="closeForm"
    >
      <FormKit
        ref="formRef"
        id="pf-property-form"
        type="form"
        :actions="false"
        @submit="onFormSubmit"
      >
        <div class="pf-form-grid">
          <div>
            <FormKit
              v-model="newProp.community"
              name="community"
              label="小区"
              type="select"
              placeholder="请选择小区"
              :options="communityOptions"
              validation="required"
              @input="onCommunityChange"
            />
          </div>
          <div>
            <FormKit
              v-model="newProp.propertyType"
              name="propertyType"
              label="物业类型"
              type="select"
              :options="propertyTypeOptions"
            />
          </div>
          <div>
            <FormKit
              v-model="newProp.building"
              name="building"
              label="楼栋"
              type="select"
              placeholder="请选择楼栋"
              :options="buildingOptions"
              validation="required"
            />
            <p v-if="!buildingOptions.length && newProp.community" class="pf-hint pf-hint--tight">
              该小区尚未配置楼栋，请先到「小区配置」中补充。
            </p>
          </div>
          <div>
            <FormKit v-model="newProp.unit" name="unit" label="单元（选填）" type="text" placeholder="如 1 / 2" />
          </div>
          <div>
            <FormKit
              v-model="newProp.room"
              name="room"
              label="房号"
              type="text"
              placeholder="如 101 / 1202"
              validation="required"
            />
          </div>
          <div>
            <FormKit
              v-model.number="newProp.area"
              name="area"
              label="建筑面积(㎡)"
              type="number"
              placeholder="如 89.5"
              validation="required|min:0.01"
            />
          </div>
          <div>
            <FormKit
              v-model="newProp.moveInDate"
              name="moveInDate"
              label="入住日期（选填）"
              type="date"
            />
          </div>
          <div>
            <FormKit
              v-model="newProp.houseStatus"
              name="houseStatus"
              label="房屋状态"
              type="select"
              :options="houseStatusOptions"
            />
          </div>

          <!-- 业主档案：同一房屋可登记多位业主 / 共有人 / 租户 -->
          <div class="pf-form-grid__full">
            <div class="pf-owner-card">
              <div class="pf-owner-card__head">
                <span class="pf-owner-card__title">业主档案</span>
                <span class="pf-hint pf-hint--tight pf-hint--inline">
                  同一房屋可登记多位业主 / 共有人 / 租户，标星号的为主业主（可缴费、收通知）
                </span>
                <span class="pf-owner-card__spacer"></span>
                <VButton size="sm" @click="addOwner">
                  <template #icon><IconAddCircle /></template>
                  添加业主
                </VButton>
              </div>

              <p v-if="!owners.length" class="pf-hint pf-hint--tight">
                暂无业主，请点击右上角「添加业主」录入。
              </p>

              <div v-for="(o, i) in owners" :key="i" class="pf-owner-grid">
                <div>
                  <FormKit
                    v-model="o.name"
                    :name="`owner_${i}_name`"
                    label="姓名"
                    type="text"
                    placeholder="姓名"
                    validation="required"
                  />
                </div>
                <div>
                  <FormKit
                    v-model="o.phone"
                    :name="`owner_${i}_phone`"
                    label="手机号"
                    type="text"
                    placeholder="手机号"
                    validation="required|matches:/^1[3-9]\d{9}$/"
                    :validation-messages="{ matches: '请输入正确的 11 位手机号' }"
                  />
                </div>
                <div>
                  <FormKit
                    v-model="o.idCard"
                    :name="`owner_${i}_idCard`"
                    label="身份证（选填）"
                    type="text"
                    placeholder="身份证号"
                  />
                </div>
                <div>
                  <FormKit
                    v-model="o.type"
                    :name="`owner_${i}_type`"
                    label="身份"
                    type="select"
                    :options="ownerTypeOptions"
                  />
                </div>
                <div class="pf-owner-grid__actions">
                  <VButton
                    size="sm"
                    :type="o.isPrimary ? 'primary' : 'secondary'"
                    @click="setPrimary(i)"
                  >
                    {{ o.isPrimary ? '主业主' : '设为主业主' }}
                  </VButton>
                  <VButton size="sm" type="danger" ghost @click="removeOwner(i)">移除</VButton>
                </div>
              </div>
            </div>
          </div>

          <div class="pf-form-grid__full">
            <FormKit v-model="newProp.remark" name="remark" label="备注（选填）" type="textarea" rows="2" />
          </div>
        </div>
      </FormKit>

      <template #footer>
        <div class="pf-modal-footer">
          <VButton :disabled="saving" @click="closeForm">取消</VButton>
          <VButton type="primary" :loading="saving" @click="submitForm">保存</VButton>
        </div>
      </template>
    </VModal>

    <!-- Excel 批量导入 -->
    <VModal
      v-model:visible="showImport"
      title="Excel 批量导入"
      :width="600"
      mount-to-body
      layer-closable
      @close="closeImport"
    >
      <p class="pf-hint">
        ① 下载模板 → ② 按模板填写数据 → ③ 选择文件上传。同小区、同楼栋、同房号会自动更新，不会重复新增。
      </p>

      <div class="pf-import-actions">
        <VButton @click="downloadTemplate">
          <template #icon><IconDownload /></template>
          下载 Excel 模板
        </VButton>
      </div>

      <FormKit
        v-model="importFiles"
        name="excel"
        label="选择 Excel 文件（.xlsx / .xls）"
        type="file"
        accept=".xlsx,.xls"
        @input="onFileInput"
      />

      <div
        v-if="importResult"
        class="pf-import-result"
        :class="importOk ? 'pf-import-result--ok' : 'pf-import-result--err'"
      >
        {{ importResult }}
      </div>

      <template #footer>
        <div class="pf-modal-footer">
          <VButton :disabled="importing" @click="closeImport">关闭</VButton>
          <VButton type="primary" :loading="importing" :disabled="!file" @click="doImport">
            上传导入
          </VButton>
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
  VPagination,
  VStatusDot,
  Dialog,
  Toast,
  IconAddCircle,
  IconRefreshLine,
  IconRiUpload2Fill,
} from '@halo-dev/components'
import IconDownload from '~icons/ri/download-2-line'
import axios, { API_BASE, API_VERSION, errMsg } from '@/utils/api'

interface Owner {
  name: string
  phone: string
  idCard: string
  type: string
  isPrimary: boolean
}

const loading = ref(false)
const saving = ref(false)
const importing = ref(false)

const items = ref<any[]>([])
const communities = ref<any[]>([])

const filter = ref({ community: '', building: '', room: '', ownerName: '' })
const page = ref(1)
const size = ref(20)

const showForm = ref(false)
const showImport = ref(false)
const editing = ref<any>(null)
const formRef = ref<any>(null)
const file = ref<File | null>(null)
const importFiles = ref<any>(null)
const importResult = ref('')
const importOk = ref(false)
const owners = ref<Owner[]>([])

/* ------------------------------- 选项 ------------------------------- */
const propertyTypeOptions = ['住宅', '商铺', '车位', '其他'].map((v) => ({ label: v, value: v }))
const houseStatusOptions = ['自住', '出租', '空置', '装修'].map((v) => ({ label: v, value: v }))
const ownerTypeOptions = ['业主', '共有人', '租户', '亲属'].map((v) => ({ label: v, value: v }))

const enabledCommunities = computed(() =>
  communities.value.filter((c) => c.spec?.enabled !== false)
)
const communityOptions = computed(() =>
  enabledCommunities.value.map((c) => ({ label: c.spec?.name || '', value: c.spec?.name || '' }))
)
const buildingOptions = computed(() => {
  const c = enabledCommunities.value.find((x) => x.spec?.name === newProp.value.community)
  return (c?.spec?.buildings || []).map((b: string) => ({ label: b, value: b }))
})

/* ------------------------------- 表单状态 ------------------------------- */
const emptyProp = () => ({
  community: '',
  building: '',
  unit: '',
  room: '',
  area: undefined as number | undefined,
  propertyType: '住宅',
  ownerName: '',
  ownerPhone: '',
  ownerIdCard: '',
  ownerType: '业主',
  moveInDate: '',
  houseStatus: '自住',
  remark: '',
  owners: [] as Owner[],
})
const newProp = ref(emptyProp())

const filtered = computed(() => {
  const f = filter.value
  const kw = (v: unknown) => String(v ?? '').toLowerCase()
  const hit = (needle: string, haystack: unknown) =>
    !needle || kw(haystack).includes(needle.toLowerCase())
  return items.value.filter((p) => {
    const s = p.spec || {}
    return (
      hit(f.community, s.community) &&
      hit(f.building, s.building) &&
      hit(f.room, s.room) &&
      hit(f.ownerName, s.ownerName) &&
      hit(f.ownerName, (s.owners || []).map((o: Owner) => o?.name).join('、'))
    )
  })
})

const pageItems = computed(() => {
  const start = (page.value - 1) * size.value
  return filtered.value.slice(start, start + size.value)
})

/* ------------------------------- 数据加载 ------------------------------- */
async function load() {
  loading.value = true
  try {
    const res = await axios.get(`${API_BASE}/properties`)
    items.value = res.data.items || []
    syncPage()
  } catch (e) {
    Toast.error(errMsg(e, '房屋列表加载失败'))
  } finally {
    loading.value = false
  }
}

async function loadCommunities() {
  try {
    const res = await axios.get(`${API_BASE}/communities`)
    communities.value = res.data.items || []
  } catch {
    // 老版本后端无 communities 接口时忽略，表单退化为可自由填写由后端兼容
    communities.value = []
  }
}

function syncPage() {
  const maxPage = Math.max(1, Math.ceil(filtered.value.length / size.value))
  if (page.value > maxPage) page.value = maxPage
}

function onPageChange(next: number) {
  page.value = next
}

function onSizeChange(next: number) {
  size.value = next
  page.value = 1
}

function onCommunityChange() {
  newProp.value.building = ''
}

/* ------------------------------- 弹窗 ------------------------------- */
function openAdd() {
  editing.value = null
  newProp.value = emptyProp()
  owners.value = [{ name: '', phone: '', idCard: '', type: '业主', isPrimary: true }]
  showForm.value = true
}

function openEdit(p: any) {
  editing.value = p
  const spec = JSON.parse(JSON.stringify(p.spec || {}))
  newProp.value = { ...emptyProp(), ...spec }
  const list: Owner[] = Array.isArray(spec.owners) && spec.owners.length
    ? spec.owners.map((o: any) => ({
        name: o?.name || '',
        phone: o?.phone || '',
        idCard: o?.idCard || '',
        type: o?.type || '业主',
        isPrimary: !!o?.isPrimary,
      }))
    : [
        {
          name: spec.ownerName || '',
          phone: spec.ownerPhone || '',
          idCard: spec.ownerIdCard || '',
          type: spec.ownerType || '业主',
          isPrimary: true,
        },
      ]
  if (!list.some((o) => o.isPrimary)) list[0].isPrimary = true
  owners.value = list
  showForm.value = true
}

function closeForm() {
  showForm.value = false
  editing.value = null
  owners.value = []
}

function addOwner() {
  owners.value.push({
    name: '',
    phone: '',
    idCard: '',
    type: '业主',
    isPrimary: owners.value.length === 0,
  })
}

function removeOwner(i: number) {
  const wasPrimary = owners.value[i]?.isPrimary
  owners.value.splice(i, 1)
  if (wasPrimary && owners.value.length && !owners.value.some((o) => o.isPrimary)) {
    owners.value[0].isPrimary = true
  }
}

function setPrimary(i: number) {
  owners.value.forEach((o, idx) => (o.isPrimary = idx === i))
}

/** 提交：优先走 FormKit 节点（触发校验与错误提示），节点不可用时兜底直存 */
function submitForm() {
  const node = formRef.value?.node
  if (node && typeof node.submit === 'function') {
    node.submit()
    return
  }
  void saveProperty()
}

function onFormSubmit() {
  void saveProperty()
}

function syncOwnersToSpec() {
  const clean: Owner[] = owners.value
    .map((o) => ({
      name: (o.name || '').trim(),
      phone: (o.phone || '').trim(),
      idCard: (o.idCard || '').trim(),
      type: o.type || '业主',
      isPrimary: !!o.isPrimary,
    }))
    .filter((o) => o.name || o.phone)
  if (!clean.length) {
    clean.push({ name: '', phone: '', idCard: '', type: '业主', isPrimary: true })
  }
  if (!clean.some((o) => o.isPrimary)) clean[0].isPrimary = true
  const primary = clean.find((o) => o.isPrimary) || clean[0]
  newProp.value.owners = clean
  newProp.value.ownerName = primary.name
  newProp.value.ownerPhone = primary.phone
  newProp.value.ownerIdCard = primary.idCard
  newProp.value.ownerType = primary.type || '业主'
}

async function saveProperty() {
  if (saving.value) return
  if (!newProp.value.community || !newProp.value.building || !newProp.value.room) {
    Toast.warning('请选择小区、楼栋并填写房号')
    return
  }
  if (!newProp.value.area || Number(newProp.value.area) <= 0) {
    Toast.warning('请填写建筑面积')
    return
  }
  syncOwnersToSpec()

  saving.value = true
  try {
    if (editing.value) {
      await axios.put(`${API_BASE}/properties/${editing.value.metadata.name}`, {
        apiVersion: API_VERSION,
        kind: 'Property',
        metadata: {
          name: editing.value.metadata.name,
          version: editing.value.metadata.version,
        },
        spec: { ...newProp.value },
      })
    } else {
      await axios.post(`${API_BASE}/properties`, {
        apiVersion: API_VERSION,
        kind: 'Property',
        spec: { ...newProp.value },
      })
    }
    Toast.success(editing.value ? '房屋信息已更新' : '房屋已创建')
    closeForm()
    await load()
  } catch (e) {
    Toast.error(errMsg(e, '保存失败'))
  } finally {
    saving.value = false
  }
}

/* ------------------------------- 删除 ------------------------------- */
function remove(p: any) {
  Dialog.warning({
    title: '删除房屋',
    description: `确认删除「${p.spec?.community || ''} ${p.spec?.building || ''}栋 ${p.spec?.room || ''}」？该操作不可恢复。`,
    confirmText: '删除',
    onConfirm: async () => {
      try {
        await axios.delete(`${API_BASE}/properties/${p.metadata.name}`)
        Toast.success('房屋已删除')
        await load()
      } catch (e) {
        Toast.error(errMsg(e, '删除失败'))
      }
    },
  })
}

/* ------------------------------- Excel 导入 ------------------------------- */
function openImport() {
  showImport.value = true
  file.value = null
  importFiles.value = null
  importResult.value = ''
}

function closeImport() {
  showImport.value = false
  file.value = null
  importFiles.value = null
}

function onFileInput(payload: any) {
  const list: File[] = Array.isArray(payload) ? payload : payload ? [payload] : []
  file.value = list[0] || null
  importResult.value = ''
}

async function doImport() {
  if (!file.value) {
    Toast.warning('请先选择 Excel 文件')
    return
  }
  importing.value = true
  try {
    const buf = await file.value.arrayBuffer()
    const rows = await parseExcel(buf)
    if (!rows.length) {
      importOk.value = false
      importResult.value = '未解析到有效数据，请检查是否使用了正确的模板。'
      return
    }
    const res = await axios.post(`${API_BASE}/properties/import`, { rows })
    importOk.value = true
    importResult.value = `导入完成：新增/更新 ${res.data.created} 条，跳过 ${res.data.skipped} 条。`
    Toast.success('Excel 导入完成')
    await load()
  } catch (e) {
    importOk.value = false
    importResult.value = `导入失败：${errMsg(e)}`
    Toast.error(errMsg(e, '导入失败'))
  } finally {
    importing.value = false
  }
}

async function parseExcel(buf: ArrayBuffer): Promise<any[]> {
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
      ownerIdCard: String(row[7] || '').trim(),
      ownerType: String(row[8] || '').trim() || '业主',
      moveInDate: String(row[9] || '').trim(),
      houseStatus: String(row[10] || '').trim() || '自住',
      propertyType: String(row[11] || '').trim() || '住宅',
    })
  })
  return rows
}

let xlsxPromise: Promise<any> | null = null
function loadXlsx(): Promise<any> {
  if (!xlsxPromise) {
    xlsxPromise = import('xlsx')
  }
  return xlsxPromise
}

/** 前端实时生成模板并下载，不依赖后端静态资源（避免 404） */
async function downloadTemplate() {
  try {
    const XLSX = await loadXlsx()
    const header = [
      '小区', '楼栋', '单元', '房号', '面积(㎡)', '业主姓名', '手机号',
      '身份证号', '业主类型', '入住日期', '房屋状态', '物业类型', '备注',
    ]
    const example = [
      '阳光花园', '1', '1', '101', 89.5, '张三', '13800000001',
      '532621198001010011', '业主', '2024-06-01', '自住', '住宅', '',
    ]
    const ws = XLSX.utils.aoa_to_sheet([header, example])
    ws['!cols'] = header.map((h: string) => ({ wch: h.length >= 8 ? h.length * 2.2 : 12 }))
    const wb = XLSX.utils.book_new()
    XLSX.utils.book_append_sheet(wb, ws, '房屋导入模板')
    XLSX.writeFile(wb, 'property-import-template.xlsx')
  } catch (e) {
    Toast.error(errMsg(e, '模板生成失败'))
  }
}

/* ------------------------------- 展示辅助 ------------------------------- */
function houseLabel(spec: any): string {
  if (!spec) return '-'
  const unit = spec.unit ? `${spec.unit}单元` : ''
  return `${spec.community || '-'} ${spec.building || '-'}栋${unit}${spec.room || ''}`
}

function ownerNames(spec: any): string {
  if (!spec) return '-'
  if (Array.isArray(spec.owners) && spec.owners.length) {
    const names = spec.owners.map((o: any) => o?.name).filter(Boolean)
    if (names.length) return names.join('、')
  }
  return spec.ownerName || '-'
}

function houseStatusState(status?: string): 'default' | 'success' | 'warning' | 'error' {
  switch (status) {
    case '自住':
      return 'success'
    case '出租':
      return 'default'
    case '装修':
      return 'warning'
    case '空置':
      return 'error'
    default:
      return 'default'
  }
}

onMounted(() => {
  void load()
  void loadCommunities()
})
</script>
