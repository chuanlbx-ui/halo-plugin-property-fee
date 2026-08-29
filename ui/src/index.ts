import { definePlugin } from '@halo-dev/console-shared'
import PropertyList from './views/PropertyList.vue'
import StandardList from './views/StandardList.vue'
import PaymentConfigList from './views/PaymentConfigList.vue'
import ReportView from './views/ReportView.vue'
import { IconPlug } from '@halo-dev/components'
import { markRaw } from 'vue'

export default definePlugin({
  components: {},
  routes: [
    {
      parentName: 'Root',
      route: {
        path: '/property-fee/report',
        name: 'PropertyFeeReport',
        component: ReportView,
        meta: {
          title: '缴费报表',
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
          title: '商户配置',
          searchable: true,
        },
      },
    },
  ],
  extensionPoints: {},
})
