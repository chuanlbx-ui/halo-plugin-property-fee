<template>
  <div class="pf-view">
    <!-- 工具栏 -->
    <div class="pf-toolbar">
      <div class="pf-toolbar__fields">
        <div class="pf-filter">
          <FormKit v-model="year" type="select" :options="yearOptions" @input="load" />
        </div>
      </div>
      <div class="pf-toolbar__actions">
        <VButton :loading="loading" @click="load">
          <template #icon><IconRefreshLine /></template>
          刷新
        </VButton>
      </div>
    </div>

    <!-- 待核实到账提醒：线下缴费需管理员确认到账后才计入收缴 -->
    <VAlert
      v-if="pendingRecords.length"
      class="pf-card-gap"
      type="warning"
      title="有线下缴费待核实到账"
      :description="`当前有 ${pendingRecords.length} 笔线下缴费申请等待确认，请核对银行/收款记录后点击「确认到账」，「驳回」将关闭该申请且不计入收缴。`"
    />

    <VLoading v-if="loading && !report" />

    <VCard class="pf-card-gap" :title="`${year} 年收缴总览`">
      <div v-if="report" class="pf-stats">
        <div v-for="card in overviewCards" :key="card.label" class="pf-stat">
          <div class="pf-stat__value">{{ card.value }}</div>
          <div class="pf-stat__label">{{ card.label }}</div>
        </div>
      </div>
      <VEmpty v-else title="暂无报表数据" message="请确认该年度已录入房屋并产生缴费记录。" />
    </VCard>

    <!-- 按小区汇总 -->
    <VCard class="pf-card-gap" title="按小区汇总">
      <div v-if="byCommunity.length" class="pf-list">
        <VEntityContainer>
          <VEntity v-for="c in byCommunity" :key="c.community">
            <template #start>
              <VEntityField :title="c.community || '-'" :max-width="240" />
              <VEntityField :title="`${c.total}`" description="总户数" :width="100" />
              <VEntityField :title="`${c.paid}`" description="已缴" :width="100" />
              <VEntityField :title="`${c.unpaid}`" description="未缴" :width="100" />
            </template>
            <template #end>
              <VEntityField :width="110">
                <template #title>
                  <span :style="{ color: rateColor(c.rate), fontWeight: 600 }">{{ c.rate }}%</span>
                </template>
                <template #description>收缴率</template>
              </VEntityField>
            </template>
          </VEntity>
        </VEntityContainer>
      </div>
      <VEmpty v-else title="暂无数据" message="该年度暂无小区缴费数据。" />
    </VCard>

    <!-- 按楼栋汇总 -->
    <VCard class="pf-card-gap" title="按楼栋汇总">
      <div v-if="byBuilding.length" class="pf-list">
        <VEntityContainer>
          <VEntity v-for="b in byBuilding" :key="`${b.community}-${b.building}`">
            <template #start>
              <VEntityField :title="`${b.community || '-'} ${b.building || '-'}栋`" :max-width="240" />
              <VEntityField :title="`${b.total}`" description="总户数" :width="100" />
              <VEntityField :title="`${b.paid}`" description="已缴" :width="100" />
              <VEntityField :title="`${b.unpaid}`" description="未缴" :width="100" />
            </template>
            <template #end>
              <VEntityField :width="110">
                <template #title>
                  <span :style="{ color: rateColor(b.rate), fontWeight: 600 }">{{ b.rate }}%</span>
                </template>
                <template #description>收缴率</template>
              </VEntityField>
            </template>
          </VEntity>
        </VEntityContainer>
      </div>
      <VEmpty v-else title="暂无数据" message="该年度暂无楼栋缴费数据。" />
    </VCard>

    <!-- 缴费记录：待核实到账的线下记录置顶并高亮，提供确认到账/驳回 -->
    <VCard :title="`${year} 年缴费记录`">
      <p class="pf-hint">
        线下收款需管理员核对到账后确认。「待核实到账」的记录不受年份筛选影响，始终置顶展示；已入账记录按年份筛选。
      </p>
      <div v-if="records.length" class="pf-list">
        <VEntityContainer>
          <VEntity
            v-for="r in records"
            :key="r.metadata.name"
            :class="{ 'pf-record--pending': isPending(r) }"
          >
            <template #start>
              <VEntityField
                :title="`${r.spec.community || '-'} ${r.spec.building || '-'}栋${r.spec.room || ''}`"
                :description="`业主：${r.spec.ownerName || '-'}`"
                :max-width="260"
              />
              <VEntityField :title="`¥${r.spec.totalAmount ?? '-'}`" description="金额" :width="130" />
              <VEntityField :width="140">
                <template #title>
                  <VTag :theme="r.spec.payType === 'offline' ? 'default' : 'primary'">
                    {{ payTypeName(r.spec.payType) }}
                  </VTag>
                </template>
                <template #description>支付方式</template>
              </VEntityField>
              <VEntityField :width="170">
                <template #title>
                  <VStatusDot :state="statusDot(r.spec.status)" :text="statusText(r.spec.status)" />
                </template>
                <template #description>缴费状态</template>
              </VEntityField>
            </template>
            <template #end>
              <VEntityField
                :title="r.spec.paidAt ? formatTime(r.spec.paidAt) : '-'"
                description="缴费时间"
                :width="180"
              />
              <VEntityField v-if="isPending(r)" :width="190">
                <template #title>
                  <div class="pf-row-actions">
                    <VButton
                      size="sm"
                      type="primary"
                      :loading="confirming === r.metadata.name"
                      :disabled="!!rejecting"
                      @click="confirmRecord(r)"
                    >
                      确认到账
                    </VButton>
                    <VButton
                      size="sm"
                      type="danger"
                      ghost
                      :loading="rejecting === r.metadata.name"
                      :disabled="!!confirming"
                      @click="rejectRecord(r)"
                    >
                      驳回
                    </VButton>
                  </div>
                </template>
                <template #description>线下到账审核</template>
              </VEntityField>
            </template>
          </VEntity>
        </VEntityContainer>
      </div>
      <VEmpty v-else :title="`${year} 年暂无缴费记录`" message="缴费完成后将自动汇总到此处。" />
    </VCard>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import {
  VAlert,
  VButton,
  VCard,
  VEmpty,
  VEntity,
  VEntityContainer,
  VEntityField,
  VLoading,
  VStatusDot,
  VTag,
  Dialog,
  Toast,
  IconRefreshLine,
} from '@halo-dev/components'
import axios, { API_BASE, errMsg } from '@/utils/api'

const currentYear = new Date().getFullYear()
const year = ref(String(currentYear))
const yearOptions = [currentYear + 1, currentYear, currentYear - 1, currentYear - 2].map((y) => ({
  label: `${y} 年`,
  value: String(y),
}))

const loading = ref(false)
const report = ref<any>(null)
const records = ref<any[]>([])
const pendingRecords = ref<any[]>([])

/** 正在提交的缴费记录 name（用于按钮禁用/加载态），同一时刻只允许一个操作 */
const confirming = ref('')
const rejecting = ref('')

const byCommunity = computed<any[]>(() => report.value?.byCommunity || [])
const byBuilding = computed<any[]>(() => report.value?.byBuilding || [])

const overviewCards = computed(() => {
  const r = report.value
  if (!r) return []
  return [
    { label: '总户数', value: r.totalProperties ?? 0 },
    { label: '已缴', value: r.paidProperties ?? 0 },
    { label: '未缴', value: r.unpaidProperties ?? 0 },
    { label: '收缴率', value: `${r.overallRate ?? 0}%` },
    { label: '已收金额(元)', value: r.paidAmount ?? 0 },
  ]
})

async function load() {
  loading.value = true
  try {
    const res = await axios.get(`${API_BASE}/reports/summary`, { params: { year: year.value } })
    report.value = res.data
  } catch (e) {
    Toast.error(errMsg(e, '报表加载失败'))
  }
  await loadRecords()
  loading.value = false
}

async function loadRecords() {
  try {
    const res = await axios.get(`${API_BASE}/feerecords`)
    const all: any[] = res.data.items || []
    // 待核实到账：线下缴费申请，需管理员确认；不受年份筛选，始终置顶
    pendingRecords.value = all
      .filter((r) => r.spec?.status === 'PENDING_CONFIRM')
      .sort((a, b) =>
        String(b.spec?.createdAt || '').localeCompare(String(a.spec?.createdAt || ''))
      )
    // 已入账记录：按所选年份筛选
    const paid = all
      .filter((r) => String(r.spec?.year) === year.value && r.spec?.status === 'PAID')
      .sort((a, b) => String(b.spec?.paidAt || '').localeCompare(String(a.spec?.paidAt || '')))
    records.value = [...pendingRecords.value, ...paid]
  } catch {
    pendingRecords.value = []
    records.value = []
  }
}

function isPending(r: any) {
  return r?.spec?.status === 'PENDING_CONFIRM'
}

/** 记录定位文案，用于二次确认弹窗 */
function recordLabel(r: any) {
  return `${r.spec?.community || '-'} ${r.spec?.building || '-'}栋${r.spec?.room || ''}`
}

function confirmRecord(r: any) {
  const name = r.metadata.name
  Dialog.warning({
    title: '确认到账',
    description: `确认「${recordLabel(r)}」的线下缴费 ¥${r.spec?.totalAmount ?? '-'} 已实际到账？确认后该记录将入账为「已入账」，并计入本年度收缴。`,
    confirmText: '确认到账',
    onConfirm: () => {
      void doConfirm(name)
    },
  })
}

function rejectRecord(r: any) {
  const name = r.metadata.name
  Dialog.warning({
    title: '驳回线下缴费',
    description: `确认驳回「${recordLabel(r)}」的线下缴费申请？驳回后该申请将关闭，且不计入收缴，业主需重新缴费。`,
    confirmText: '驳回',
    confirmType: 'danger',
    onConfirm: () => {
      void doReject(name)
    },
  })
}

async function doConfirm(name: string) {
  confirming.value = name
  try {
    await axios.post(`${API_BASE}/feerecords/${name}/confirm`)
    Toast.success('已确认到账')
    await load()
  } catch (e) {
    Toast.error(errMsg(e, '确认到账失败'))
  } finally {
    confirming.value = ''
  }
}

async function doReject(name: string) {
  rejecting.value = name
  try {
    await axios.post(`${API_BASE}/feerecords/${name}/reject`)
    Toast.success('已驳回该线下缴费申请')
    await load()
  } catch (e) {
    Toast.error(errMsg(e, '驳回失败'))
  } finally {
    rejecting.value = ''
  }
}

function statusText(status?: string) {
  switch (status) {
    case 'PENDING':
      return '待支付'
    case 'PENDING_CONFIRM':
      return '待核实到账'
    case 'PAID':
      return '已入账'
    case 'CLOSED':
      return '已驳回'
    case 'FAILED':
      return '支付失败'
    default:
      return status || '待缴纳'
  }
}

function statusDot(status?: string): 'default' | 'success' | 'warning' | 'error' {
  switch (status) {
    case 'PAID':
      return 'success'
    case 'PENDING_CONFIRM':
      return 'warning'
    case 'CLOSED':
    case 'FAILED':
      return 'error'
    default:
      return 'default'
  }
}

function payTypeName(t?: string) {
  switch (t) {
    case 'native':
      return '微信扫码'
    case 'jsapi':
      return '公众号支付'
    case 'offline':
      return '线下收款'
    case 'h5':
      return 'H5 支付'
    default:
      return t || '-'
  }
}

function rateColor(rate: number) {
  if (rate >= 80) return '#15803d'
  if (rate >= 50) return '#b45309'
  return '#b91c1c'
}

function formatTime(t?: string) {
  if (!t) return '-'
  return t.replace('T', ' ').substring(0, 19)
}

onMounted(load)
</script>
