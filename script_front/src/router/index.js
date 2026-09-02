import { createRouter, createWebHistory } from 'vue-router'
import RuleListView from '@/views/RuleListView.vue'
import RuleWorkbenchView from '@/views/RuleWorkbenchView.vue'

const routes = [
  { path: '/', name: 'rule-list', component: RuleListView },
  { path: '/rule/:id', name: 'rule-workbench', component: RuleWorkbenchView, props: true }
]

export default createRouter({ history: createWebHistory(), routes })
