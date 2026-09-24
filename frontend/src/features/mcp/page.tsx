import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { useShallow } from 'zustand/react/shallow'
import { GatewayClient } from '../../shared/api/client.js'
import { useAuthStore } from '../../shared/auth/store.js'
import { InspectorShell } from '../../shared/components/InspectorShell.js'

function permissionBadges(
  annotations: {
    readOnlyHint?: boolean
    destructiveHint?: boolean
    idempotentHint?: boolean
    openWorldHint?: boolean
  } | null,
): string[] {
  if (annotations === null) return []
  const out: string[] = []
  if (annotations.readOnlyHint === true) out.push('read')
  if (annotations.destructiveHint === true) out.push('destructive')
  if (annotations.idempotentHint === true) out.push('idempotent')
  if (annotations.openWorldHint === true) out.push('network')
  return out
}

/**
 * MCP tool catalog: live JSON-RPC tools/list with honest suspension.
 *
 * @remarks Proof-type: live when the gateway serves it, boundary when it
 * 403s. The table skeleton exists in both states so healing causes zero
 * reflow; nothing is ever invented. Row selection is local UI state.
 *
 * @returns The MCP catalog screen.
 */
export function McpPage(): React.JSX.Element {
  const { gatewayKey } = useAuthStore(useShallow((s) => ({ gatewayKey: s.gatewayKey })))
  const [filter, setFilter] = useState('')
  const [selected, setSelected] = useState<string | null>(null)
  const qc = useQueryClient()

  const catalog = useQuery({
    queryKey: ['mcp-tools'],
    queryFn: ({ signal }) => new GatewayClient({ token: gatewayKey ?? '' }).mcpTools({ signal }),
    enabled: gatewayKey !== null,
  })

  const tools = catalog.data !== undefined && 'tools' in catalog.data ? catalog.data.tools : []
  const suspended = catalog.data !== undefined && 'suspended' in catalog.data
  const query = filter.trim().toLowerCase()
  const visible = tools.filter(
    (t) =>
      query.length === 0 ||
      t.name.toLowerCase().includes(query) ||
      (t.description ?? '').toLowerCase().includes(query),
  )
  const inspected = tools.find((t) => t.name === selected) ?? null

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center gap-2">
        <h1 className="font-display text-2xl font-medium tracking-tight">MCP tools</h1>
        <span className="rounded-full border border-ink/15 px-2 py-0.5 font-mono text-xs tnum dark:border-parchment/15">
          tools:{tools.length}
        </span>
        <span
          className={`rounded-full border px-2 py-0.5 font-mono text-xs ${
            suspended
              ? 'border-warn/40 text-warn dark:text-warn-soft'
              : 'border-ink/15 dark:border-parchment/15'
          }`}
        >
          catalog:{suspended ? 'suspended' : 'live'}
        </span>
        <span className="flex-1" />
        <button
          type="button"
          onClick={() => {
            void qc.invalidateQueries({ queryKey: ['mcp-tools'] })
          }}
          className="rounded-md border border-ink/15 px-3 py-2 text-[13px] dark:border-parchment/15"
        >
          Retry
        </button>
      </div>
      {suspended ? (
        <div role="status" className="rounded-lg border border-warn/40 p-4">
          <p className="text-sm font-medium">■ Tool catalog suspended upstream (HTTP 403).</p>
          <p className="mt-1 text-sm text-ink-soft dark:text-parchment-soft">
            The gateway refuses MCP routes pending a backend fix. This console will not invent
            tools.
          </p>
        </div>
      ) : null}
      {gatewayKey === null ? (
        <p className="text-sm text-ink-soft dark:text-parchment-soft">
          Paste a gateway key on the Playground screen to probe the live catalog.
        </p>
      ) : catalog.isPending ? (
        <p role="status" className="text-sm">
          Loading tool catalog…
        </p>
      ) : catalog.error instanceof Error ? (
        <p role="alert" className="text-sm text-danger dark:text-danger-soft">
          {catalog.error.message}
        </p>
      ) : (
        <div className="space-y-6">
          <div className="space-y-4">
            <div>
              <label htmlFor="mcp-filter" className="sr-only">
                Filter tools
              </label>
              <input
                id="mcp-filter"
                type="search"
                value={filter}
                placeholder="Filter"
                onChange={(e) => {
                  setFilter(e.target.value)
                }}
                className="w-48 rounded-md border border-ink/15 bg-transparent px-3 py-2 text-[13px] dark:border-parchment/15"
              />
            </div>
            {visible.length === 0 ? (
              <p className="text-sm text-ink-soft dark:text-parchment-soft">
                {tools.length === 0
                  ? 'No tools returned. The catalog is empty.'
                  : 'No tools match this filter.'}
              </p>
            ) : (
              <table className="w-full text-left text-sm">
                <caption className="sr-only">MCP tool catalog</caption>
                <thead>
                  <tr className="font-mono text-xs text-ink-soft dark:text-parchment-soft">
                    <th scope="col" className="py-2 pr-3 font-medium">
                      Tool
                    </th>
                    <th scope="col" className="py-2 pr-3 font-medium">
                      Description
                    </th>
                    <th scope="col" className="py-2 text-right font-medium">
                      Permissions
                    </th>
                  </tr>
                </thead>
                <tbody>
                  {visible.map((t) => (
                    <tr
                      key={t.name}
                      tabIndex={0}
                      aria-selected={t.name === selected}
                      onClick={() => {
                        setSelected(t.name === selected ? null : t.name)
                      }}
                      onKeyDown={(event) => {
                        if (event.key === 'Enter' || event.key === ' ') {
                          event.preventDefault()
                          setSelected(t.name === selected ? null : t.name)
                        }
                      }}
                      className={`cursor-pointer border-t border-ink/10 dark:border-parchment/10 ${
                        t.name === selected ? 'bg-ink/4 dark:bg-parchment/6' : ''
                      }`}
                    >
                      <td className="max-w-48 truncate py-2 pr-3 font-mono text-[13px]">
                        {t.name}
                      </td>
                      <td className="max-w-96 truncate py-2 pr-3 text-[13px]">
                        {t.description ?? 'n/a'}
                      </td>
                      <td className="py-2 text-right text-[13px]">
                        {permissionBadges(t.annotations).join(' · ') || 'none'}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </div>
          {inspected === null ? (
            <p className="text-[13px] text-ink-soft dark:text-parchment-soft">
              Select a tool to inspect its schema.
            </p>
          ) : (
            <InspectorShell
              label="Tool inspector"
              title={inspected.name}
              onClose={() => {
                setSelected(null)
              }}
            >
              <p className="text-[13px] text-ink-soft dark:text-parchment-soft">
                {inspected.description ?? 'No description.'}
              </p>
              <p className="text-[13px]">
                Permissions: {permissionBadges(inspected.annotations).join(' · ') || 'none'}
              </p>
              <div>
                <p className="mb-1 text-[13px] text-ink-soft dark:text-parchment-soft">
                  Input schema
                </p>
                <pre className="max-h-64 overflow-auto rounded-md border border-ink/10 p-2 font-mono text-xs whitespace-pre-wrap dark:border-parchment/10">
                  {JSON.stringify(inspected.inputSchema, null, 2)}
                </pre>
              </div>
            </InspectorShell>
          )}
        </div>
      )}
    </div>
  )
}
