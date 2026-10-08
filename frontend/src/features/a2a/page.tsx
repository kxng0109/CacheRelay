import { useRef, useState } from 'react'
import { useShallow } from 'zustand/react/shallow'
import { ApiError, GatewayClient, keyFingerprint } from '../../shared/api/client.js'
import { toErrorMessage } from '../../shared/api/client.js'
import { SseHandshakeError, openSseStream } from '../../shared/sse/client.js'
import { useAuthStore } from '../../shared/auth/store.js'
import { Select } from '../../shared/components/Select.js'
import type { A2aAgentCard } from '../../shared/api/types.js'

const METHODS = ['message/send', 'message/stream', 'tasks/get', 'tasks/cancel'] as const

/**
 * A2A agent surface: card viewer plus JSON-RPC invoke.
 *
 * @remarks Backend truth (`A2aProxyController`): card reads are
 * key-gated with RBAC (unknown/disabled/denied answer indistinguishable
 * 404); invoke accepts exactly four methods with unknown methods
 * answering `-32601`. Card URLs arrive rewritten to the public base.
 * Paste-key first like the MCP catalog: no session key is ever a
 * virtual key.
 *
 * @returns The A2A screen.
 */
export function A2aPage(): React.JSX.Element {
  const { gatewayKey } = useAuthStore(useShallow((s) => ({ gatewayKey: s.gatewayKey })))
  const [agent, setAgent] = useState('')
  const [loadedAgent, setLoadedAgent] = useState<string | null>(null)
  const [card, setCard] = useState<A2aAgentCard | null>(null)
  const [cardError, setCardError] = useState<string | null>(null)
  const [cardBusy, setCardBusy] = useState(false)
  const [method, setMethod] = useState<string>('message/send')
  const [version, setVersion] = useState('')
  const [paramsText, setParamsText] = useState('{}')
  const [result, setResult] = useState<string | null>(null)
  const [frames, setFrames] = useState(0)
  const [problem, setProblem] = useState<string | null>(null)
  const [invokeBusy, setInvokeBusy] = useState(false)
  const stopRef = useRef<AbortController | null>(null)
  const userStoppedRef = useRef(false)

  const loadCard = (): void => {
    const name = agent.trim()
    if (name.length === 0 || gatewayKey === null) return
    setCardError(null)
    setCard(null)
    setCardBusy(true)
    const client = new GatewayClient({ token: gatewayKey })
    void client
      .a2aCard(name, { ignoreSession: true })
      .then((out) => {
        setCard(out)
        setLoadedAgent(name)
      })
      .catch((e: unknown) => {
        setCardError(toErrorMessage(e, 'Agent card load failed.'))
      })
      .finally(() => {
        setCardBusy(false)
      })
  }

  const describeFailure = (e: unknown): string => {
    const base = toErrorMessage(e, 'Agent invocation failed.')
    const wait =
      e instanceof ApiError
        ? e.rateLimit.retryAfter
        : e instanceof SseHandshakeError
          ? e.retryAfter
          : null
    return wait === null ? base : `${base} (retry after ${String(wait)}s)`
  }

  const stopStream = (): void => {
    userStoppedRef.current = true
    stopRef.current?.abort()
  }

  const invoke = (): void => {
    const name = (loadedAgent ?? agent).trim()
    if (name.length === 0 || gatewayKey === null) return
    setResult(null)
    setFrames(0)
    setProblem(null)
    let params: Record<string, unknown>
    try {
      const parsed: unknown = JSON.parse(paramsText)
      if (typeof parsed !== 'object' || parsed === null || Array.isArray(parsed)) {
        setProblem('Params must be a JSON object.')
        return
      }
      params = parsed as Record<string, unknown>
    } catch {
      setProblem('Params must be valid JSON.')
      return
    }
    const pin = version.trim()
    setInvokeBusy(true)
    if (method === 'message/stream') {
      const ctrl = new AbortController()
      stopRef.current = ctrl
      userStoppedRef.current = false
      const client = new GatewayClient({ token: gatewayKey })
      const req = client.a2aStreamRequest(name, params, pin.length === 0 ? undefined : pin)
      const collected: unknown[] = []
      void openSseStream({
        url: req.url,
        method: 'POST',
        headers: req.headers,
        body: req.body,
        signal: ctrl.signal,
        maxRetries: 2,
        onMessage: (data) => {
          try {
            collected.push(JSON.parse(data) as unknown)
          } catch {
            collected.push(data)
          }
          setFrames(collected.length)
        },
        onDone: () => {
          setResult(JSON.stringify(collected, null, 2))
          setInvokeBusy(false)
        },
        onIncomplete: (e, delivered) => {
          setProblem(
            `Stream truncated after ${String(delivered)} frames. Kept what arrived — retry starts a new run. (${e.message})`,
          )
          setInvokeBusy(false)
        },
        onError: (e) => {
          if (userStoppedRef.current) {
            setInvokeBusy(false)
            return
          }
          setProblem(describeFailure(e))
          setInvokeBusy(false)
        },
      })
      return
    }
    const client = new GatewayClient({ token: gatewayKey })
    void client
      .a2aInvoke(
        name,
        method,
        params,
        pin.length === 0 ? { ignoreSession: true } : { ignoreSession: true, a2aVersion: pin },
      )
      .then((out) => {
        setResult(JSON.stringify(out, null, 2))
      })
      .catch((e: unknown) => {
        setProblem(describeFailure(e))
      })
      .finally(() => {
        setInvokeBusy(false)
      })
  }

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center gap-2">
        <h1 className="font-display text-2xl font-medium tracking-tight">A2A agents</h1>
        <span className="rounded-full border border-ink/15 px-2 py-0.5 font-mono text-xs tnum dark:border-parchment/15">
          card:{card === null ? 'none' : 'live'}
        </span>
        <span className="flex-1" />
      </div>
      {gatewayKey === null ? (
        <p className="text-sm text-ink-soft dark:text-parchment-soft">
          Paste a gateway key on the Playground screen to probe agent cards.
        </p>
      ) : (
        <div className="space-y-4">
          <div className="flex flex-wrap items-end gap-2">
            <div>
              <label htmlFor="a2a-agent" className="mb-1 block text-[13px] font-medium">
                Agent
              </label>
              <input
                id="a2a-agent"
                value={agent}
                autoComplete="off"
                onChange={(e) => {
                  setAgent(e.target.value)
                }}
                placeholder="helper"
                className="w-48 rounded-md border border-ink/15 bg-transparent px-3 py-2 font-mono text-sm dark:border-parchment/15"
              />
            </div>
            <button
              type="button"
              disabled={cardBusy || agent.trim().length === 0}
              onClick={loadCard}
              className="rounded-md border border-ink/15 px-3 py-2 text-[13px] disabled:cursor-not-allowed dark:border-parchment/15"
            >
              Load card
            </button>
            <p className="font-mono text-xs text-ink-soft dark:text-parchment-soft">
              key:{keyFingerprint(gatewayKey)}
            </p>
          </div>
          {cardError === null ? null : (
            <p role="alert" className="text-sm text-danger dark:text-danger-soft">
              {cardError}
            </p>
          )}
          {card === null ? null : (
            <div className="space-y-2 rounded-lg border border-ink/10 p-3 dark:border-parchment/10">
              <p className="font-mono text-[13px]">{card.name}</p>
              <p className="text-[13px] text-ink-soft dark:text-parchment-soft">
                {card.description ?? 'No description.'}
              </p>
              <p className="font-mono text-xs text-ink-soft dark:text-parchment-soft">
                {card.protocolVersion} · {card.url}
              </p>
              <div className="grid gap-2 sm:grid-cols-[minmax(0,10rem)_minmax(0,8rem)_minmax(0,1fr)_auto]">
                <div>
                  <Select
                    id="a2a-method"
                    label="Method"
                    value={method}
                    options={METHODS.map((m) => ({ value: m, label: m }))}
                    onChange={setMethod}
                  />
                </div>
                <div>
                  <label htmlFor="a2a-version" className="mb-1 block text-[13px] font-medium">
                    Version pin (optional)
                  </label>
                  <input
                    id="a2a-version"
                    value={version}
                    autoComplete="off"
                    onChange={(e) => {
                      setVersion(e.target.value)
                    }}
                    placeholder="0.3"
                    className="w-full rounded-md border border-ink/15 bg-transparent px-3 py-2 font-mono text-sm dark:border-parchment/15"
                  />
                </div>
                <div>
                  <label htmlFor="a2a-params" className="mb-1 block text-[13px] font-medium">
                    Params (JSON object)
                  </label>
                  <textarea
                    id="a2a-params"
                    rows={3}
                    value={paramsText}
                    onChange={(e) => {
                      setParamsText(e.target.value)
                    }}
                    className="w-full rounded-md border border-ink/15 bg-transparent px-3 py-2 font-mono text-xs dark:border-parchment/15"
                  />
                </div>
                <div className="flex items-end gap-2">
                  <button
                    type="button"
                    disabled={invokeBusy}
                    onClick={invoke}
                    className="rounded-md border border-ink/15 px-3 py-2 text-[13px] disabled:cursor-not-allowed dark:border-parchment/15"
                  >
                    Send message
                  </button>
                  {invokeBusy && method === 'message/stream' ? (
                    <button
                      type="button"
                      onClick={stopStream}
                      className="rounded-md border border-ink/15 px-3 py-2 text-[13px] dark:border-parchment/15"
                    >
                      Stop
                    </button>
                  ) : null}
                </div>
              </div>
              {method === 'message/stream' && (invokeBusy || frames > 0) ? (
                <p
                  role="status"
                  className="font-mono text-xs text-ink-soft tnum dark:text-parchment-soft"
                >
                  {frames} frames{invokeBusy ? ' — streaming…' : ''}
                </p>
              ) : null}
              {result === null ? null : (
                <pre className="max-h-64 overflow-auto rounded-md border border-ink/10 p-2 font-mono text-xs whitespace-pre-wrap dark:border-parchment/10">
                  {result}
                </pre>
              )}
              {problem === null ? null : (
                <p role="alert" className="text-[13px] text-danger dark:text-danger-soft">
                  {problem}
                </p>
              )}
            </div>
          )}
        </div>
      )}
    </div>
  )
}
