<template>
  <div class="pf-view">
    <p class="pf-hint">
      年物业费 = 面积(㎡) × 单价(元/㎡·月) × 计费月数 + 额外费用 − 优惠减免。支持物业类型差异化定价与滞纳金。
    </p>

    <!-- 工具栏 -->
    <div class="pf-toolbar">
      <div class="pf-toolbar__fields">
        <div class="pf-filter">
          <FormKit v-model="keyword" type="text" placeholder="搜索小区 / 年份" />
        </div>
      </div>
      <div class="pf-toolbar__actions">
        <VButton :loading="loading" @click="load">
          <template #icon><IconRefreshLine /></template>
          刷新
        </VButton>
        <VButton type="primary" @click="openAdd">
          <template #icon><IconAddCircle /></template>
          新增标准
        </VButton>
      </div>
    </div>

    <VLoading v-if="loading" />

    <VEmpty
      v-else-if="!filtered.length"
      title="暂无收费标准"
      message="为每个小区、年份、物业类型分别配置单价与额外费用。"
    >
      <template #actions>
        <VButton type="primary" @click="openAdd">新增标准</VButton>
      </template>
    </VEmpty>

    <div v-else class="pf-list">
      <VEntityContainer>
        <VEntity v-for="s in filtered" :key="s.metadata.name">
          <template #start>
            <VEntityField
              :title="`${s.spec.community || '-'} · ${s.spec.year || '-'}年`"
              :description="s.spec.propertyType || '住宅'"
              :max-width="240"
            />
            <VEntityField :title="`${s.spec.unitPrice ?? '-'}`" description="单价(元/㎡·月)" :width="140" />
            <VEntityField :title="cycleName(s.spec.billingCycle)" description="缴费周期" :width="100" />
            <VEntityField :width="240">
              <template #description>
                <div class="pf-tags">
                  <VTag v-for="f in s.spec.extraFees || []" :key="f.name">
                    {{ f.name }} {{ chargeModeText(f) }}
                  </VTag>
                  <span v-if="!(s.spec.extraFees || []).length" class="pf-hint pf-hint--tight">无</span>
                </div>
              </template>
            </VEntityField>
            <VEntityField
              :title="s.spec.discount ? discountText(s.spec.discount) : '无'"
              description="优惠减免"
              :width="130"
            />
          </template>

          <template #end>
            <VEntityField :width="100">
              <template #description>
                <VStatusDot
                  :state="s.spec.enabled === false ? 'error' : 'success'"
                  :text="s.spec.enabled === false ? '停用' : '启用'"
                />
              </template>
            </VEntityField>
          </template>

          <template #dropdownItems>
            <VDropdownItem @click="openEdit(s)">编辑</VDropdownItem>
            <VDropdownDivider />
            <VDropdownItem type="danger" @click="remove(s)">删除</VDropdownItem>
          </template>
        </VEntity>
      </VEntityContainer>
    </div>

    <!-- 新增 / 编辑收费标准 -->
    <VModal
      v-model:visible="showAdd"
      :title="editing ? '编辑收费标准' : '新增收费标准'"
      :width="760"
      mount-to-body
      layer-closable
      @close="close"
    >
      <FormKit ref="formRef" id="pf-standard-form" type="form" :actions="false" @submit="onFormSubmit">
        <div class="pf-form-grid">
          <div>
            <FormKit
              v-model="form.community"
              name="community"
              label="小区"
              type="text"
              placeholder="如：阳光花园"
              validation="required"
            />
            <p v-if="communityNames.length" class="pf-hint pf-hint--tight">
              已配置小区：{{ communityNames.join('、') }}
            </p>
          </div>
          <div>
            <FormKit
              v-model.number="form.year"
              name="year"
              label="年份"
              type="number"
              placeholder="如：2026"
              validation="required|min:2000"
            />
          </div>
          <div>
            <FormKit
              v-model="form.propertyType"
              name="propertyType"
              label="物业类型"
              type="select"
              :options="propertyTypeOptions"
            />
          </div>
          <div>
            <FormKit
              v-model.number="form.unitPrice"
              name="unitPrice"
              label="单价(元/㎡·月)"
              type="number"
              step="0.01"
              placeholder="如：1.20"
              validation="required|min:0"
            />
          </div>
          <div>
            <FormKit
              v-model="form.billingCycle"
              name="billingCycle"
              label="缴费周期"
              type="select"
              :options="cycleOptions"
            />
          </div>
          <div>
            <FormKit
              v-model="form.enabled"
              name="enabled"
              label="启用该收费标准"
              type="checkbox"
            />
          </div>

          <!-- 额外费用项 -->
          <div class="pf-form-grid__full">
            <div class="pf-owner-card">
              <div class="pf-owner-card__head">
                <span class="pf-owner-card__title">额外费用项（选填）</span>
                <span class="pf-hint pf-hint--tight pf-hint--inline">
                  如电梯费、垃圾清运费等，可设置固定金额或按面积计费
                </span>
                <span class="pf-owner-card__spacer"></span>
                <VButton size="sm" @click="form.extraFees.push({ name: '', amount: 0, chargeMode: 'fixed' })">
                  <template #icon><IconAddCircle /></template>
                  添加费用项
                </VButton>
              </div>

              <p v-if="!form.extraFees.length" class="pf-hint pf-hint--tight">暂无额外费用项。</p>

              <div v-for="(f, i) in form.extraFees" :key="i" class="pf-owner-grid">
                <div>
                  <FormKit
                    v-model="f.name"
                    :name="`extraFee_${i}_name`"
                    label="费用名称"
                    type="text"
                    placeholder="如：电梯费"
                  />
                </div>
                <div>
                  <FormKit
                    v-model="f.chargeMode"
                    :name="`extraFee_${i}_chargeMode`"
                    label="计费方式"
                    type="select"
                    :options="chargeModeOptions"
                  />
                </div>
                <div>
                  <FormKit
                    v-model.number="f.amount"
                    :name="`extraFee_${i}_amount`"
                    label="金额"
                    type="number"
                    step="0.01"
                    placeholder="金额"
                  />
                </div>
                <div class="pf-owner-grid__actions">
                  <VButton size="sm" type="danger" ghost @click="form.extraFees.splice(i, 1)">
                    移除
                  </VButton>
                </div>
              </div>
            </div>
          </div>

          <!-- 优惠减免 -->
          <div>
            <FormKit
              v-model="discountType"
              name="discountType"
              label="优惠减免"
              type="select"
              :options="discountTypeOptions"
            />
          </div>
          <div>
            <FormKit
              v-if="discountType === 'amount'"
              v-model.number="form.discount.amount"
              name="discountAmount"
              label="减免金额(元)"
              type="number"
              step="0.01"
            />
            <FormKit
              v-else-if="discountType === 'percent' || discountType === 'firstYear'"
              v-model.number="form.discount.percent"
              name="discountPercent"
              label="减免比例（0–1，如 0.2 表示减免 20%）"
              type="number"
              step="0.01"
              validation="min:0|max:1"
            />
          </div>

          <!-- 滞纳金 -->
          <div>
            <FormKit
              v-model.number="form.lateFee.graceDays"
              name="graceDays"
              label="滞纳金宽限期(天，选填)"
              type="number"
              min="0"
            />
          </div>
          <div>
            <FormKit
              v-model.number="form.lateFee.dailyRate"
              name="dailyRate"
              label="滞纳金日利率（选填，如 0.0005）"
              type="number"
              step="0.0001"
              min="0"
            />
          </div>
          <div class="pf-form-grid__full">
            <FormKit
              v-model.number="form.lateFee.maxRate"
              name="maxRate"
              label="滞纳金封顶比例（选填，0–1，留空表示不封顶）"
              type="number"
              step="0.01"
              min="0"
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
} from '@halo-dev/components'
import axios, { API_BASE, API_VERSION, errMsg } from '@/utils/api'

interface ExtraFee {
  name: string
  amount: number
  chargeMode: string
}

const loading = ref(false)
const saving = ref(false)
const keyword = ref('')

const items = ref<any[]>([])
const communities = ref<any[]>([])
const showAdd = ref(false)
const editing = ref<any>(null)
const discountType = ref('')
const formRef = ref<any>(null)

const propertyTypeOptions = ['住宅', '商铺', '车位', '其他'].map((v) => ({ label: v, value: v }))
const cycleOptions = [
  { label: '按年缴（12 个月）', value: 'year' },
  { label: '按半年缴（6 个月）', value: 'half' },
  { label: '按季缴（3 个月）', value: 'quarter' },
  { label: '按月缴（1 个月）', value: 'month' },
]
const chargeModeOptions = [
  { label: '固定金额/年', value: 'fixed' },
  { label: '按面积(元/㎡·年)', value: 'perArea' },
  { label: '按月(元/月)', value: 'perMonth' },
]
const discountTypeOptions = [
  { label: '无优惠', value: '' },
  { label: '固定减免(元)', value: 'amount' },
  { label: '按比例减免(%)', value: 'percent' },
  { label: '首年优惠(比例)', value: 'firstYear' },
]

const communityNames = computed(() =>
  communities.value.map((c) => c.spec?.name).filter(Boolean)
)

const filtered = computed(() =>
  items.value.filter((s) => {
    if (!keyword.value) return true
    const kw = keyword.value.toLowerCase()
    return (
      String(s.spec?.community || '').toLowerCase().includes(kw) ||
      String(s.spec?.year || '').includes(kw)
    )
  })
)

const emptyForm = () => ({
  community: '',
  year: new Date().getFullYear(),
  propertyType: '住宅',
  unitPrice: 0,
  billingCycle: 'year',
  enabled: true,
  extraFees: [] as ExtraFee[],
  discount: { type: '', amount: 0, percent: 0 },
  lateFee: { graceDays: 0, dailyRate: 0.0005, maxRate: undefined as number | undefined },
})
const form = ref(emptyForm())

async function load() {
  loading.value = true
  try {
    const res = await axios.get(`${API_BASE}/feestandards`)
    items.value = res.data.items || []
  } catch (e) {
    Toast.error(errMsg(e, '收费标准加载失败'))
  } finally {
    loading.value = false
  }
}

async function loadCommunities() {
  try {
    const res = await axios.get(`${API_BASE}/communities`)
    communities.value = res.data.items || []
  } catch {
    communities.value = []
  }
}

function openAdd() {
  editing.value = null
  form.value = emptyForm()
  discountType.value = ''
  showAdd.value = true
}

function openEdit(s: any) {
  editing.value = s
  const spec = JSON.parse(JSON.stringify(s.spec || {}))
  form.value = {
    community: spec.community || '',
    year: spec.year || new Date().getFullYear(),
    propertyType: spec.propertyType || '住宅',
    unitPrice: spec.unitPrice ?? 0,
    billingCycle: spec.billingCycle || 'year',
    enabled: spec.enabled !== false,
    extraFees: Array.isArray(spec.extraFees) ? spec.extraFees : [],
    discount: spec.discount || { type: '', amount: 0, percent: 0 },
    lateFee: spec.lateFee || { graceDays: 0, dailyRate: 0.0005, maxRate: undefined },
  }
  discountType.value = spec.discount?.type || ''
  showAdd.value = true
}

function close() {
  showAdd.value = false
  editing.value = null
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
  if (!form.value.community || !form.value.year) {
    Toast.warning('请填写小区与年份')
    return
  }
  if (!form.value.unitPrice && form.value.unitPrice !== 0) {
    Toast.warning('请填写单价')
    return
  }
  const spec: any = {
    ...form.value,
    extraFees: form.value.extraFees
      .map((f) => ({ ...f, name: (f.name || '').trim() }))
      .filter((f) => f.name),
  }
  spec.discount = discountType.value
    ? { ...form.value.discount, type: discountType.value }
    : undefined
  if (!spec.discount) delete spec.discount
  if (!form.value.lateFee.graceDays && !form.value.lateFee.dailyRate) {
    delete spec.lateFee
  }

  saving.value = true
  try {
    if (editing.value) {
      await axios.put(`${API_BASE}/feestandards/${editing.value.metadata.name}`, {
        apiVersion: API_VERSION,
        kind: 'FeeStandard',
        metadata: {
          name: editing.value.metadata.name,
          version: editing.value.metadata.version,
        },
        spec,
      })
    } else {
      await axios.post(`${API_BASE}/feestandards`, {
        apiVersion: API_VERSION,
        kind: 'FeeStandard',
        spec,
      })
    }
    Toast.success(editing.value ? '收费标准已更新' : '收费标准已创建')
    close()
    await load()
  } catch (e) {
    Toast.error(errMsg(e, '保存失败'))
  } finally {
    saving.value = false
  }
}

function remove(s: any) {
  Dialog.warning({
    title: '删除收费标准',
    description: `确认删除「${s.spec?.community || ''} ${s.spec?.year || ''}年 ${s.spec?.propertyType || '住宅'}」的收费标准？`,
    confirmText: '删除',
    onConfirm: async () => {
      try {
        await axios.delete(`${API_BASE}/feestandards/${s.metadata.name}`)
        Toast.success('收费标准已删除')
        await load()
      } catch (e) {
        Toast.error(errMsg(e, '删除失败'))
      }
    },
  })
}

function cycleName(c?: string) {
  return { year: '年缴', half: '半年', quarter: '季缴', month: '月缴' }[c || 'year'] || c || '-'
}
function chargeModeText(f: any) {
  const mode = f?.chargeMode || 'fixed'
  if (mode === 'perArea') return `¥${f.amount}/㎡·年`
  if (mode === 'perMonth') return `¥${f.amount}/月`
  return `¥${f.amount}/年`
}
function discountText(d: any) {
  if (d.type === 'amount') return `减 ${d.amount} 元`
  if (d.type === 'percent') return `减 ${(d.percent * 100).toFixed(0)}%`
  if (d.type === 'firstYear') return `首年减 ${((d.percent || 0.5) * 100).toFixed(0)}%`
  return '优惠'
}

onMounted(() => {
  void load()
  void loadCommunities()
})
</script>
