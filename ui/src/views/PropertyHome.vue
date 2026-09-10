<template>
  <div class="pf-view">
    <VPageHeader title="物业费管理">
      <template #icon>
        <IconPlug />
      </template>
      <template #actions>
        <VButton @click="openFrontPage">
          <template #icon>
            <IconExternalLinkLine />
          </template>
          前台缴费页
        </VButton>
      </template>
    </VPageHeader>

    <VTabbar :active-id="active" :items="tabItems" @change="onTabChange" />

    <component :is="current" />
  </div>
</template>

<script setup lang="ts">
import { computed, markRaw, onMounted, ref } from 'vue'
import { VButton, VPageHeader, VTabbar } from '@halo-dev/components'
import { IconExternalLinkLine, IconPlug } from '@halo-dev/components'
import axios, { API_BASE, API_VERSION } from '@/utils/api'
import ReportView from './ReportView.vue'
import PropertyList from './PropertyList.vue'
import CommunityList from './CommunityList.vue'
import StandardList from './StandardList.vue'
import PaymentConfigList from './PaymentConfigList.vue'
import SystemConfigView from './SystemConfigView.vue'

type TabKey = 'report' | 'properties' | 'communities' | 'standards' | 'configs' | 'sys'

// VTabbar 的 items 只接受 { [key: string]: string }，因此标签与组件分开维护
const tabItems = [
  { id: 'report', label: '缴费报表' },
  { id: 'properties', label: '房屋管理' },
  { id: 'communities', label: '小区配置' },
  { id: 'standards', label: '收费标准' },
  { id: 'configs', label: '支付渠道' },
  { id: 'sys', label: '系统配置' },
]

const tabs: Record<TabKey, any> = {
  report: markRaw(ReportView),
  properties: markRaw(PropertyList),
  communities: markRaw(CommunityList),
  standards: markRaw(StandardList),
  configs: markRaw(PaymentConfigList),
  sys: markRaw(SystemConfigView),
}

const active = ref<TabKey>('report')
const current = computed(() => tabs[active.value] || tabs.report)

function onTabChange(id: string | number) {
  active.value = id as TabKey
}

/**
 * 前台缴费页地址：优先使用系统配置中的自定义地址，
 * 未配置时回退到插件自带的前台页面（任何 Halo 部署均可直接访问）。
 */
const frontUrl = ref(`${API_VERSION}/pages/property-fee`)

async function loadFrontUrl() {
  const fallback = `${window.location.origin}/apis/api.${API_VERSION}/pages/property-fee`
  try {
    const res = await axios.get(`${API_BASE}/systemconfig`)
    const configured = (res.data?.spec?.frontUrl || '').trim()
    frontUrl.value = configured || fallback
  } catch {
    frontUrl.value = fallback
  }
}

function openFrontPage() {
  window.open(frontUrl.value, '_blank', 'noopener')
}

onMounted(loadFrontUrl)
</script>
