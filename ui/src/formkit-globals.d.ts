/**
 * FormKit 由 Halo 控制台在应用启动时全局注册（见 Halo 官方文档
 * 《插件设置与表单组件》：「FormKit 由 Halo 全局注册，插件不需要再次
 * 安装、初始化或自定义一套基础输入样式」），因此插件内不引入
 * @formkit/vue 依赖，只在本文件中补充全局组件类型，供 vue-tsc 识别。
 */
declare module 'vue' {
  export interface GlobalComponents {
    FormKit: any
    FormKitSchema: any
  }
}

export {}
