<script setup lang="ts">
defineProps<{ name: string; size?: number }>()

const paths: Record<string, string> = {
  campus: 'M3 21h18M5 21V9l7-5 7 5v12M9 21v-5h6v5M9 10h.01M15 10h.01M9 13h.01M15 13h.01M12 4V2',
  grid: 'M3 3h7v7H3zM14 3h7v7h-7zM3 14h7v7H3zM14 14h7v7h-7z',
  calendar: 'M8 2v4M16 2v4M3 10h18M5 4h14a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2M8 14h3M8 18h6',
  spark: 'm12 3 2.5 6.5L21 12l-6.5 2.5L12 21l-2.5-6.5L3 12l6.5-2.5L12 3ZM20 2v4M18 4h4',
  clipboard: 'M9 4H6a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V6a2 2 0 0 0-2-2h-3M9 2h6v4H9zM8 12h8M8 16h5',
  wait: 'M12 8v5l3 2M21 12a9 9 0 1 1-3-6.7M21 3v5h-5',
  bell: 'M18 8a6 6 0 0 0-12 0c0 7-3 7-3 9h18c0-2-3-2-3-9M10 21h4',
  approval: 'M14 3H5v18h14V9M14 3v6h5M14 3l5 6M8 15l2 2 5-5',
  sliders: 'M4 3v7M4 14v7M12 3v12M12 19v2M20 3v2M20 9v12M1 10h6M9 15h6M17 5h6',
  upload: 'M12 16V3M7 8l5-5 5 5M4 15v6h16v-6',
  users: 'M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2M9 3a4 4 0 1 0 0 8 4 4 0 0 0 0-8M17 4a4 4 0 0 1 0 7M22 21v-2a4 4 0 0 0-3-3.9',
  delivery: 'M3 5h18v14H3zM3 5l9 7 9-7M7 16h4',
  logout: 'M9 4H3v16h6M9 12h12M17 8l4 4-4 4',
  arrow: 'M4 12h16M14 6l6 6-6 6',
  menu: 'M4 6h16M4 12h16M4 18h16',
  pin: 'M20 10c0 6-8 12-8 12S4 16 4 10a8 8 0 1 1 16 0ZM12 7a3 3 0 1 0 0 6 3 3 0 0 0 0-6',
  equipment: 'M3 4h18v13H3zM8 21h8M12 17v4M7 8h5M7 12h9',
  search: 'M10.5 3a7.5 7.5 0 1 0 0 15 7.5 7.5 0 0 0 0-15M16 16l5 5',
  plus: 'M12 5v14M5 12h14',
  check: 'M5 12l4 4L19 6',
  chevron: 'm9 6 6 6-6 6',
  info: 'M12 11v6M12 7h.01M21 12a9 9 0 1 1-18 0 9 9 0 0 1 18 0',
}
</script>

<template>
  <svg :width="size || 20" :height="size || 20" viewBox="0 0 24 24" fill="none"
       stroke="currentColor" stroke-width="1.65" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
    <path :d="paths[name] || paths.grid"/>
  </svg>
</template>
