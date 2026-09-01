<template>
  <div>
    <!-- 顶部 Tab 导航 -->
    <div style="display: flex; align-items: center; gap: 8px; margin-bottom: 16px; border-bottom: 2px solid #e8edf5; padding-bottom: 0">
      <h2 style="margin: 0 24px 0 0; font-size: 18px; color: #0a2a5e; white-space: nowrap">🏘️ 物业费管理</h2>
      <button
        v-for="t in tabs"
        :key="t.key"
        :style="tabStyle(t.key)"
        @click="switchTab(t.key)"
      >
        {{ t.label }}
      </button>
      <div style="flex: 1"></div>
      <span style="font-size: 12px; color: #99a3b3; white-space: nowrap">{{ communityCount }} 个小区 · {{ propertyCount }} 户</span>
    </div>

    <!-- Tab 内容 -->
    <component :is="current" />
  </div>
</template>

<script setup lang="ts">
import { ref, computed, markRaw, onMounted } from 'vue'
import axios from 'axios'
import ReportView from './ReportView.vue'
import PropertyList from './PropertyList.vue'
import StandardList from './StandardList.vue'
import PaymentConfigList from './PaymentConfigList.vue'

const API_BASE = '/apis/console.api.propertyfee.halo.run/v1alpha1'
const tabs = [
  { key: 'report', label: '📊 缴费报表', comp: markRaw(ReportView) },
  { key: 'properties', label: '🏠 房屋管理', comp: markRaw(PropertyList) },
  { key: 'standards', label: '💰 收费标准', comp: markRaw(StandardList) },
  { key: 'configs', label: '💳 商户配置', comp: markRaw(PaymentConfigList) },
]
const active = ref('report')
const current = computed(() => tabs.find((t) => t.key === active.value)?.comp || ReportView)

function tabStyle(key: string) {
  const on = active.value === key
  return {
    padding: '10px 18px',
    border: 'none',
    background: on ? '#0a2a5e' : 'transparent',
    color: on ? '#fff' : '#5a6478',
    borderRadius: '8px 8px 0 0',
    cursor: 'pointer',
    fontSize: '14px',
    fontWeight: on ? 600 : 400,
  }
}

function switchTab(key: string) {
  active.value = key
}

const communityCount = ref(0)
const propertyCount = ref(0)
async function loadStats() {
  try {
    const res = await axios.get(`${API_BASE}/properties`)
    const items = res.data.items || []
    propertyCount.value = items.length
    communityCount.value = new Set(items.map((p: any) => p.spec?.community).filter(Boolean)).size
  } catch (e) {
    // 静默失败，不阻塞页面
  }
}
onMounted(loadStats)
</script>
