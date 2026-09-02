import { defineConfig } from 'vitest/config'

// 只测纯逻辑模块，用 node 环境即可，不拉 jsdom
export default defineConfig({
  test: {
    environment: 'node',
    include: ['src/**/__tests__/*.spec.js']
  }
})
