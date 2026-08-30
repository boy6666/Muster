<template>
  <Teleport to="body">
    <Transition name="ui-fade">
      <div v-if="visible" class="mask ui-modal-mask" @click="visible = false">
        <div class="panel corner ui-modal" :class="modalClass" :style="{ width }" @click.stop>
          <div class="ui-modal-head">
            <div class="ui-modal-title">{{ title }}</div>
            <span class="ui-modal-close" title="关闭" @click="visible = false">×</span>
          </div>
          <div class="ui-modal-body">
            <slot />
          </div>
          <div v-if="$slots.footer" class="ui-modal-foot">
            <slot name="footer" />
          </div>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<script setup lang="ts">
withDefaults(
  defineProps<{
    title?: string
    width?: string
    modalClass?: string
  }>(),
  { title: '', width: '520px', modalClass: '' },
)

const visible = defineModel<boolean>('visible', { default: false })
</script>
