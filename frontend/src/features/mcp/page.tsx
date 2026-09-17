/**
 * MCP tool catalog: honestly suspended.
 *
 * @remarks
 * Proof-type: boundary. The backend answers every `/v1/mcp/**` route with
 * 403 (`BACKEND_API_REFERENCE.md` §10) until the defect is fixed, so this
 * screen performs zero live calls and says so. Shipping a fabricated catalog
 * would violate the no-fabricated-data rule.
 *
 * @returns The suspended-catalog boundary state.
 */
export function McpPage(): React.JSX.Element {
  return (
    <div className="space-y-4">
      <h1 className="text-xl font-semibold tracking-tight">MCP tools</h1>
      <div role="status" className="rounded-lg border border-warn/40 p-4">
        <p className="text-sm font-medium">■ Tool catalog suspended upstream (HTTP 403).</p>
        <p className="mt-1 text-sm opacity-70">
          The gateway refuses all MCP routes pending a backend fix. This console will not invent
          tools. Track the defect in the backend reference, section 10, then refresh.
        </p>
      </div>
    </div>
  )
}
