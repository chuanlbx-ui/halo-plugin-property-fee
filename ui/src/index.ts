import { definePlugin } from '@halo-dev/console-shared'
import { markRaw } from 'vue'
import PropertyHome from './views/PropertyHome.vue'
import PropertyList from './views/PropertyList.vue'
import CommunityList from './views/CommunityList.vue'
import StandardList from './views/StandardList.vue'
import PaymentConfigList from './views/PaymentConfigList.vue'
import ReportView from './views/ReportView.vue'
import SystemConfigView from './views/SystemConfigView.vue'
import { IconPlug } from '@halo-dev/components'

// ⚠️ Halo 控制台只会加载插件的 main.js（不会加载 style.css）。
// bundler-kit 以 IIFE 格式构建时会把 CSS 提取成独立 style.css（孤儿文件），
// 因此插件自身的补充样式必须用 ?inline 导入并手动注入 <style>，
// 否则页面在控制台中会完全裸奔。
import pluginCss from './assets/plugin.css?inline'

if (typeof document !== 'undefined') {
  const styleEl = document.createElement('style')
  styleEl.setAttribute('data-property-fee-plugin', 'true')
  styleEl.textContent = pluginCss
  document.head.appendChild(styleEl)
}

export default definePlugin({
  components: {},
  routes: [
    {
      parentName: 'Root',
      route: {
        path: '/property-fee',
        name: 'PropertyFeeHome',
        component: PropertyHome,
        meta: {
          title: '物业费管理',
          searchable: true,
          menu: {
            name: '物业费管理',
            group: '内容',
            icon: markRaw(IconPlug),
            priority: 0,
          },
        },
      },
    },
    {
      parentName: 'Root',
      route: {
        path: '/property-fee/report',
        name: 'PropertyFeeReport',
        component: ReportView,
        meta: {
          title: '缴费报表',
          searchable: true,
        },
      },
    },
    {
      parentName: 'Root',
      route: {
        path: '/property-fee/properties',
        name: 'PropertyFeeProperties',
        component: PropertyList,
        meta: {
          title: '房屋管理',
          searchable: true,
        },
      },
    },
    {
      parentName: 'Root',
      route: {
        path: '/property-fee/communities',
        name: 'PropertyFeeCommunities',
        component: CommunityList,
        meta: {
          title: '小区配置',
          searchable: true,
        },
      },
    },
    {
      parentName: 'Root',
      route: {
        path: '/property-fee/standards',
        name: 'PropertyFeeStandards',
        component: StandardList,
        meta: {
          title: '收费标准',
          searchable: true,
        },
      },
    },
    {
      parentName: 'Root',
      route: {
        path: '/property-fee/payment-configs',
        name: 'PropertyFeeConfigs',
        component: PaymentConfigList,
        meta: {
          title: '支付渠道配置',
          searchable: true,
        },
      },
    },
    {
      parentName: 'Root',
      route: {
        path: '/property-fee/system-config',
        name: 'PropertyFeeSystemConfig',
        component: SystemConfigView,
        meta: {
          title: '系统配置',
          searchable: true,
        },
      },
    },
  ],
  extensionPoints: {},
})
