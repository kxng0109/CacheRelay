import * as echarts from 'echarts/core'
import { BarChart, LineChart } from 'echarts/charts'
import {
  DataZoomComponent,
  GridComponent,
  LegendComponent,
  TooltipComponent,
} from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'

/**
 * Minimal tree-shaken ECharts registration.
 *
 * @remarks
 * Importing `echarts/core` plus only the charts/components the SPA renders
 * keeps the vendor chunk near ~150KB instead of the ~1MB full bundle.
 * Feature routes lazy-load their charts so the initial JS stays in budget.
 */
echarts.use([
  LineChart,
  BarChart,
  GridComponent,
  TooltipComponent,
  LegendComponent,
  DataZoomComponent,
  CanvasRenderer,
])

export { echarts }
export type { EChartsOption } from 'echarts'
