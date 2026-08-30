<template>
  <Teleport to="body">
    <Transition name="ui-fade">
      <div v-if="visible" class="mask ui-drawer-mask" @click="visible = false" />
    </Transition>
    <Transition name="ui-slide">
      <aside v-if="visible" class="drawer" :style="{ width: size }">
        <div class="dr-head">
          <b>{{ title }}</b>
          <span class="x" title="关闭" @click="visible = false">✕</span>
        </div>
        <div class="dr-body">
          <slot />
        </div>
        <div v-if="$slots.footer" class="dr-foot">
          <slot name="footer" />
        </div>
      </aside>
    </Transition>
  </Teleport>
</template>

<script setup lang="ts">
withDefaults(
  defineProps<{
    title?: string
    size?: string
  }>(),
  { title: '', size: '460px' },
)

const visible = defineModel<boolean>('visible', { default: false })
</script>
