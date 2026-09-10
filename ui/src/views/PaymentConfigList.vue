<template>
  <div class="pf-view">
    <p class="pf-hint">
      每个小区可绑定多个支付渠道，前台缴费时自动列出可用渠道。微信渠道需填写商户号、APIv3 密钥与商户私钥。
    </p>

    <!-- 工具栏 -->
    <div class="pf-toolbar">
      <div class="pf-toolbar__fields">
        <div class="pf-filter">
          <FormKit v-model="keyword" type="text" placeholder="搜索小区 / 渠道" />
        </div>
      </div>
      <div class="pf-toolbar__actions">
        <VButton :loading="loading" @click="load">
          <template #icon><IconRefreshLine /></template>
          刷新
        </VButton>
        <VButton type="primary" @click="openAdd">
          <template #icon><IconAddCircle /></template>
          新增渠道
        </VButton>
      </div>
    </div>

    <VLoading v-if="loading" />

    <VEmpty
      v-else-if="!filtered.length"
      title="暂无支付渠道"
      message="为小区绑定微信支付或线下收款渠道，前台缴费时才能完成支付。"
    >
      <template #actions>
        <VButton type="primary" @click="openAdd">新增渠道</VButton>
      </template>
    </VEmpty>

    <div v-else class="pf-list">
      <VEntityContainer>
        <VEntity v-for="c in filtered" :key="c.metadata.name">
          <template #start>
            <VEntityField :title="c.spec.community || '-'" :width="200" />
            <VEntityField :width="150">
              <template #title>
                <VTag :theme="channelTheme(c.spec.channelType)">{{ typeName(c.spec.channelType) }}</VTag>
              </template>
            </VEntityField>
            <VEntityField
              :title="c.spec.channelName || typeName(c.spec.channelType)"
              description="渠道名称"
              :width="180"
            />
            <VEntityField :title="c.spec.mchId || '-'" description="商户号" :width="160" />
          </template>

          <template #end>
            <VEntityField :width="90">
              <template #description>
                <VStatusDot v-if="c.spec.isDefault" state="success" text="默认" />
                <span v-else class="pf-inline-state">—</span>
              </template>
            </VEntityField>
            <VEntityField :width="100">
              <template #description>
                <VStatusDot
                  :state="c.spec.enabled === false ? 'error' : 'success'"
                  :text="c.spec.enabled === false ? '停用' : '启用'"
                />
              </template>
            </VEntityField>
          </template>

          <template #dropdownItems>
            <VDropdownItem @click="openEdit(c)">编辑</VDropdownItem>
            <VDropdownDivider />
            <VDropdownItem type="danger" @click="remove(c)">删除</VDropdownItem>
          </template>
        </VEntity>
      </VEntityContainer>
    </div>

    <!-- 新增 / 编辑支付渠道 -->
    <VModal
      v-model:visible="showAdd"
      :title="editing ? '编辑支付渠道' : '新增支付渠道'"
      :width="720"
      mount-to-body
      layer-closable
      @close="close"
    >
      <FormKit ref="formRef" id="pf-payment-form" type="form" :actions="false" @submit="onFormSubmit">
        <div class="pf-form-grid">
          <div>
            <FormKit
              v-model="form.community"
              name="community"
              label="小区名称"
              type="text"
              placeholder="如：阳光花园"
              validation="required"
            />
          </div>
          <div>
            <FormKit
              v-model="form.channelType"
              name="channelType"
              label="渠道类型"
              type="select"
              :options="channelTypeOptions"
              validation="required"
              @input="onTypeChange"
            />
          </div>
          <div class="pf-form-grid__full">
            <FormKit
              v-model="form.channelName"
              name="channelName"
              label="渠道名称（前台展示用）"
              type="text"
              placeholder="如：微信扫码 / 线下转账"
            />
          </div>

          <!-- 微信渠道 -->
          <template v-if="isWechat">
            <div class="pf-form-grid__full">
              <FormKit v-model="form.appId" name="appId" label="微信公众号 AppID" type="text" />
            </div>
            <div>
              <FormKit
                v-model="form.mchId"
                name="mchId"
                label="微信商户号"
                type="text"
                placeholder="mch_id"
                validation="required"
              />
            </div>
            <div>
              <FormKit v-model="form.mchSerialNo" name="mchSerialNo" label="商户证书序列号" type="text" />
            </div>
            <div class="pf-form-grid__full">
              <FormKit
                v-model="form.apiV3Key"
                name="apiV3Key"
                label="APIv3 密钥（32 位）"
                type="text"
                placeholder="APIv3 Key"
              />
            </div>
            <div class="pf-form-grid__full">
              <FormKit
                v-model="form.mchPrivateKey"
                name="mchPrivateKey"
                label="商户私钥 PEM 内容（apiclient_key.pem 全文）"
                type="textarea"
                rows="4"
              />
            </div>
          </template>

          <!-- 线下收款 -->
          <template v-if="form.channelType === 'offline'">
            <div class="pf-form-grid__full">
              <FormKit
                v-model="form.offlineInstruction"
                name="offlineInstruction"
                label="线下收款说明（前台缴费时展示）"
                type="textarea"
                rows="3"
                placeholder="如：现金 / 银行转账 户名：XXX 账号：XXXX"
              />
            </div>
          </template>

          <div>
            <FormKit
              v-model="form.notifyUrl"
              name="notifyUrl"
              label="支付回调地址（留空自动生成）"
              type="text"
              placeholder="https://…"
            />
          </div>
          <div>
            <FormKit v-model="form.remark" name="remark" label="备注（选填）" type="text" />
          </div>
          <div>
            <FormKit v-model="form.enabled" name="enabled" label="启用该渠道" type="checkbox" />
          </div>
          <div>
            <FormKit v-model="form.isDefault" name="isDefault" label="设为默认渠道" type="checkbox" />
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

const loading = ref(false)
const saving = ref(false)
const keyword = ref('')

const items = ref<any[]>([])
const showAdd = ref(false)
const editing = ref<any>(null)
const formRef = ref<any>(null)

/** 只保留微信 Native / 微信 JSAPI / 线下收款三种渠道（支付宝渠道已下线） */
const channelTypeOptions = [
  { label: '微信扫码支付（Native）', value: 'wechat_native' },
  { label: '微信公众号支付（JSAPI）', value: 'wechat_jsapi' },
  { label: '线下收款（现金 / 转账）', value: 'offline' },
]

const emptyForm = () => ({
  community: '',
  channelType: 'wechat_native',
  channelName: '',
  enabled: true,
  isDefault: false,
  appId: '',
  mchId: '',
  apiV3Key: '',
  mchSerialNo: '',
  mchPrivateKey: '',
  notifyUrl: '',
  offlineInstruction: '',
  remark: '',
})
const form = ref(emptyForm())

const isWechat = computed(
  () => form.value.channelType === 'wechat_native' || form.value.channelType === 'wechat_jsapi'
)

const filtered = computed(() =>
  items.value.filter((c) => {
    if (!keyword.value) return true
    const kw = keyword.value.toLowerCase()
    return (
      String(c.spec?.community || '').toLowerCase().includes(kw) ||
      String(c.spec?.channelName || '').toLowerCase().includes(kw) ||
      String(typeName(c.spec?.channelType)).toLowerCase().includes(kw)
    )
  })
)

function onTypeChange(value?: unknown) {
  if (!form.value.channelName) {
    form.value.channelName = typeName(String(value ?? form.value.channelType))
  }
}

function openAdd() {
  editing.value = null
  form.value = emptyForm()
  showAdd.value = true
}

function openEdit(c: any) {
  editing.value = c
  form.value = { ...emptyForm(), ...JSON.parse(JSON.stringify(c.spec || {})) }
  showAdd.value = true
}

function close() {
  showAdd.value = false
  editing.value = null
}

async function load() {
  loading.value = true
  try {
    const res = await axios.get(`${API_BASE}/paymentconfigs`)
    items.value = res.data.items || []
  } catch (e) {
    Toast.error(errMsg(e, '支付渠道加载失败'))
  } finally {
    loading.value = false
  }
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
  if (!form.value.community) {
    Toast.warning('请填写小区名称')
    return
  }
  if (isWechat.value && !form.value.mchId) {
    Toast.warning('微信渠道请填写商户号')
    return
  }

  const spec: any = { ...form.value }
  // 清理与当前渠道类型无关的字段，避免把无用凭据写入后端
  if (!isWechat.value) {
    delete spec.appId
    delete spec.apiV3Key
    delete spec.mchSerialNo
    delete spec.mchPrivateKey
    if (form.value.channelType === 'offline') delete spec.mchId
  }
  if (form.value.channelType !== 'offline') delete spec.offlineInstruction

  saving.value = true
  try {
    if (editing.value) {
      await axios.put(`${API_BASE}/paymentconfigs/${editing.value.metadata.name}`, {
        apiVersion: API_VERSION,
        kind: 'PaymentConfig',
        metadata: {
          name: editing.value.metadata.name,
          version: editing.value.metadata.version,
        },
        spec,
      })
    } else {
      await axios.post(`${API_BASE}/paymentconfigs`, {
        apiVersion: API_VERSION,
        kind: 'PaymentConfig',
        spec,
      })
    }
    Toast.success(editing.value ? '支付渠道已更新' : '支付渠道已创建')
    close()
    await load()
  } catch (e) {
    Toast.error(errMsg(e, '保存失败'))
  } finally {
    saving.value = false
  }
}

function remove(c: any) {
  Dialog.warning({
    title: '删除支付渠道',
    description: `确认删除「${c.spec?.community || ''}」的 ${c.spec?.channelName || typeName(c.spec?.channelType)} 渠道？`,
    confirmText: '删除',
    onConfirm: async () => {
      try {
        await axios.delete(`${API_BASE}/paymentconfigs/${c.metadata.name}`)
        Toast.success('支付渠道已删除')
        await load()
      } catch (e) {
        Toast.error(errMsg(e, '删除失败'))
      }
    },
  })
}

function typeName(t?: string) {
  switch (t) {
    case 'wechat_native':
      return '微信扫码'
    case 'wechat_jsapi':
      return '公众号支付'
    case 'offline':
      return '线下收款'
    default:
      return t ? '未知渠道' : '-'
  }
}

/** 渠道标签主题：微信渠道 primary，线下渠道 default */
function channelTheme(t?: string): 'default' | 'primary' | 'secondary' | 'danger' {
  return t === 'wechat_native' || t === 'wechat_jsapi' ? 'primary' : 'default'
}

onMounted(load)
</script>
