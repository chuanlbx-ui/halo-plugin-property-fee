<template>
  <div class="pf-view">
    <VPageHeader title="批量导入">
      <template #icon><IconUpload /></template>
    </VPageHeader>

    <VAlert
      class="pf-card-gap"
      type="info"
      title="一张表维护小区、楼栋、单元、房号、面积、业主与收费标准"
      description="支持 Excel(.xlsx) 与 CSV。房屋与业主自动联动建档：同一套房的多行会合并为多个业主。价格列（单价+收费年份）会同时创建或更新该小区的收费标准。建议先点「预检」确认无误，再点「确认导入」。"
    />

    <VCard class="pf-card-gap" title="选择文件">
      <div class="pf-import">
        <input
          ref="fileInput"
          type="file"
          accept=".xlsx,.xlsm,.csv"
          class="pf-import__file"
          @change="onFile"
        />
        <div class="pf-import__meta">
          <span v-if="file">
            已选择：<b>{{ file.name }}</b>（{{ (file.size / 1024).toFixed(1) }} KB）
          </span>
          <span v-else class="pf-import__hint">未选择文件</span>
        </div>
        <div class="pf-import__actions">
          <VButton @click="downloadTemplate">
            <template #icon><IconDownloadLine /></template>
            下载模板
          </VButton>
          <VButton :disabled="!file || !!busy" :loading="busy === 'dry'" @click="run(true)">
            <template #icon><IconSearchLine /></template>
            预检
          </VButton>
          <VButton type="primary" :disabled="!file || !!busy" :loading="busy === 'real'" @click="run(false)">
            <template #icon><IconUpload /></template>
            确认导入
          </VButton>
        </div>
      </div>

      <VAlert
        v-if="result"
        class="pf-card-gap"
        :type="result.dryRun ? 'info' : 'success'"
        :title="result.dryRun ? '预检结果（未写入数据）' : '导入完成'"
        :description="summaryText"
      />
      <VAlert
        v-if="result && result.errors && result.errors.length"
        class="pf-card-gap"
        type="warning"
        title="跳过的问题行"
        :description="result.errors.slice(0, 10).join('\n')"
      />
    </VCard>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { VPageHeader, VButton, VCard, VAlert } from '@halo-dev/components'
import IconUpload from '~icons/ri/upload-2-line'
import IconDownloadLine from '~icons/ri/download-2-line'
import IconSearchLine from '~icons/ri/search-line'
import axios, { API_BASE, errMsg } from '@/utils/api'

interface ImportResult {
  dryRun: boolean
  rows: number
  communities: number
  housesCreated: number
  housesUpdated: number
  ownersAdded: number
  standardsCreated: number
  standardsUpdated: number
  errors: string[]
}

const file = ref<File | null>(null)
const fileInput = ref<HTMLInputElement | null>(null)
const busy = ref<'' | 'dry' | 'real'>('')
const result = ref<ImportResult | null>(null)
const summaryText = ref('')

function onFile(e: Event) {
  const target = e.target as HTMLInputElement
  file.value = target.files && target.files.length ? target.files[0] : null
  result.value = null
}

async function run(dryRun: boolean) {
  if (!file.value) return
  busy.value = dryRun ? 'dry' : 'real'
  result.value = null
  try {
    const form = new FormData()
    form.append('file', file.value)
    const res = await axios.post(`${API_BASE}/houses/import?dryRun=${dryRun}`, form, {
      headers: { 'Content-Type': 'multipart/form-data' },
    })
    const d = res.data as ImportResult
    result.value = d
    summaryText.value =
      `读取 ${d.rows} 行；小区 ${d.communities} 个；` +
      `房屋 新增 ${d.housesCreated} / 更新 ${d.housesUpdated}；` +
      `业主 新增 ${d.ownersAdded} 人；` +
      `收费标准 新增 ${d.standardsCreated} / 更新 ${d.standardsUpdated}`
  } catch (e) {
    result.value = null
    summaryText.value = ''
    alert('导入失败：' + errMsg(e))
  } finally {
    busy.value = ''
  }
}

function downloadTemplate() {
  const header =
    '小区,楼栋,单元,房号,面积,物业类型,业主姓名,业主手机号,证件号,业主类型,单价,收费年份,计费周期'
  const rows = [
    '阳光花园,1栋,1单元,101,89.5,住宅,张三,13800000001,,业主,1.2,2026,year',
    '阳光花园,1栋,1单元,101,89.5,住宅,李四,13800000002,,家属,,,',
    '阳光花园,1栋,1单元,102,89.5,住宅,王五,13800000003,,业主,,,',
    '阳光花园,商业区,,A01,80,商铺,赵六,13900000001,,业主,2.5,2026,year',
    '测试小区,1栋,1单元,101,1,住宅,余川,13577683126,,业主,0.01,2026,year',
  ]
  const csv = '\uFEFF' + [header, ...rows].join('\n') + '\n'
  const blob = new Blob([csv], { type: 'text/csv;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = '物业费-批量导入模板.csv'
  a.click()
  URL.revokeObjectURL(url)
}
</script>
