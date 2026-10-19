import { defineConfig } from 'vitest/config'
import { loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'
import { VitePWA } from 'vite-plugin-pwa'
import { fileURLToPath } from 'node:url'

export default defineConfig(({ mode }) => {
  const production = mode === 'production' || mode === 'production.uat'
  const env = loadEnv(mode, process.cwd(), 'VITE_')
  const apiTarget = env.VITE_APP_URL

  return {
    base: production ? './' : '/',
    plugins: [
      vue(),
      VitePWA({
        registerType: 'prompt',
        injectRegister: false,
        filename: 'service-worker.js',
        manifestFilename: 'manifest.json',
        manifest: {
          name: '苍穹外卖',
          short_name: 'Vue Ts Admin',
          icons: [
            {
              src: './img/icons/android-chrome-192x192.png',
              sizes: '192x192',
              type: 'image/png'
            },
            {
              src: './img/icons/android-chrome-512x512.png',
              sizes: '512x512',
              type: 'image/png'
            }
          ],
          start_url: './index.html',
          display: 'standalone',
          background_color: '#fff',
          theme_color: '#4DBA87'
        },
        workbox: {
          navigateFallback: 'index.html',
          maximumFileSizeToCacheInBytes: 3 * 1024 * 1024
        }
      })
    ],
    resolve: {
      alias: {
        '@': fileURLToPath(new URL('./src', import.meta.url))
      }
    },
    css: {
      preprocessorOptions: {
        scss: {
          api: 'modern-compiler',
          additionalData: (source, filename) => {
            if (filename.endsWith('_variables.scss') || filename.endsWith('_mixins.scss')) {
              return source
            }
            return `@use "@/styles/_variables.scss" as *; @use "@/styles/_mixins.scss" as *; ${source}`
          }
        }
      }
    },
    server: {
      host: '0.0.0.0',
      port: 8888,
      open: true,
      proxy: apiTarget
        ? {
            '/api': {
              target: apiTarget,
              ws: false,
              secure: false,
              changeOrigin: true,
              rewrite: (requestPath) => requestPath.replace(/^\/api/, '')
            }
          }
        : undefined
    },
    test: {
      environment: 'jsdom',
      globals: true,
      include: ['tests/unit/**/*.spec.ts']
    }
  }
})
