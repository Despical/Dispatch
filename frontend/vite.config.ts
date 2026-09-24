import { defineConfig } from 'vite';
import tailwindcss from '@tailwindcss/vite';

export default defineConfig({
  plugins: [tailwindcss()],
  build: {
    outDir: '../src/main/resources/static/assets',
    emptyOutDir: true,
    manifest: false,
    rollupOptions: {
      input: {
        app: 'src/main.ts',
        'public-styles': 'src/public-styles.css'
      },
      output: {
        entryFileNames: 'app.js',
        assetFileNames: asset => asset.name === 'public-styles.css' ? 'public.css'
          : asset.name?.endsWith('.css') ? 'app.css' : '[name][extname]'
      }
    }
  }
});
