import { defineConfig } from 'vite';
import tailwindcss from '@tailwindcss/vite';

export default defineConfig({
  plugins: [tailwindcss()],
  build: {
    outDir: '../src/main/resources/static/assets',
    emptyOutDir: true,
    manifest: false,
    rollupOptions: {
      input: 'src/main.ts',
      output: {
        entryFileNames: 'app.js',
        assetFileNames: asset => asset.name?.endsWith('.css') ? 'app.css' : '[name][extname]'
      }
    }
  }
});
