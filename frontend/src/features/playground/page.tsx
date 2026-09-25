import { zodResolver } from '@hookform/resolvers/zod'
import { useEffect, useMemo, useRef, useState } from 'react'
import type { KeyboardEvent } from 'react'
import { useForm, useWatch } from 'react-hook-form'
import { useShallow } from 'zustand/react/shallow'
import * as z from 'zod/v4'
import { GatewayClient } from '../../shared/api/client.js'
import { isStreamingEnabled } from '../../shared/api/client.js'
import { toErrorMessage } from '../../shared/api/client.js'
import { useAuthStore } from '../../shared/auth/store.js'
import { EmptyTrio } from '../../shared/components/EmptyTrio.js'
import { KeySourcePicker, type KeySource } from '../../shared/components/KeySourcePicker.js'
import { RunDetailPanel } from './RunDetailPanel.js'
import { RequestCard } from './RequestCard.js'
import type { StreamSummary } from './SseStreamViewer.js'
import { SseStreamViewer } from './SseStreamViewer.js'
import { ModelSelect } from '../../shared/models/ModelSelect.js'

const schema = z.object({
  model: z.string().min(1, 'Model is required'),
  prompt: z.string().min(1, 'Prompt is required').max(8000, 'Prompt is too long'),
  key: z.string().optional().default(''),
})

/** Sanctioned samples: each fills the prompt box only, never fabricates output. */
const SAMPLES = [
  {
    label: 'Cache outcomes',
    prompt: 'Summarize the three cache outcomes (HIT, MISS, STALE) in one sentence each.',
  },
  {
    label: 'JSON only',
    prompt: 'Reply with JSON only: {"hit": "...", "miss": "...", "stale": "..."}',
  },
  {
    label: 'Stale vs miss',
    prompt: 'Explain when a gateway should serve STALE instead of MISS.',
  },
] as const

type FormData = z.input<typeof schema>

interface RunRecord {
  id: number
  model: string
  prompt: string
}

/**
 * Playground page: credential entry (memory-only) plus live SSE completion.
 *
 * @remarks Proof-type: live centerpiece of the console (one bold element: the stream).
 * Prompt block on top, single output locus below, run history in the rail.
 *
 * @returns The playground screen.
 */
export function PlaygroundPage(): React.JSX.Element {
  const { gatewayKey, setGatewayKey, session } = useAuthStore(
    useShallow((s) => ({
      gatewayKey: s.gatewayKey,
      setGatewayKey: s.setGatewayKey,
      session: s.session,
    })),
  )
  const [runId, setRunId] = useState(0)
  const [submitted, setSubmitted] = useState<FormData | null>(null)
  /**
   * Act-as-self key hash for the submitted run (account mode only).
   * Paste mode sends the pasted key verbatim with no header.
   */
  const [submittedActAsKey, setSubmittedActAsKey] = useState<string | null>(null)
  /**
   * Key source toggle. Sessions default to owned keys (never displayed);
   * guests only ever see the paste input.
   */
  const [keySource, setKeySource] = useState<KeySource>(session === null ? 'paste' : 'account')
  const [ownedKeyId, setOwnedKeyId] = useState('')
  const [staticText, setStaticText] = useState<string | null>(null)
  const [staticError, setStaticError] = useState<string | null>(null)
  /**
   * Static call wall clock. Measured around the request so the detail
   * panel shows a real number even though the backend reports no usage.
   */
  const [staticLatencyMs, setStaticLatencyMs] = useState<number | null>(null)
  /**
   * Final stream facts from the viewer. Null while streaming or before the
   * first run; the detail panel shows em dashes until it lands.
   */
  const [streamSummary, setStreamSummary] = useState<StreamSummary | null>(null)
  const [history, setHistory] = useState<RunRecord[]>([])
  const outputRef = useRef<HTMLElement | null>(null)
  /**
   * Monotonic run counter. History entries must never be created inside a
   * state updater: StrictMode double-invokes updaters in dev and a impure
   * updater appends the same run twice.
   */
  const runCounter = useRef(0)
  const streaming = isStreamingEnabled()

  const {
    register,
    handleSubmit,
    setValue,
    setError,
    control,
    formState: { errors, isSubmitting },
  } = useForm<FormData>({
    resolver: zodResolver(schema),
    mode: 'onSubmit',
    defaultValues: { model: '', prompt: '', key: gatewayKey ?? '' },
  })
  const promptLength = useWatch({ control, name: 'prompt' }).length
  const modelValue = useWatch({ control, name: 'model' })
  const keyValue = useWatch({ control, name: 'key' })
  const overLimit = promptLength > 8000
  const accountMode = session !== null && keySource === 'account'
  const effectiveModelToken = accountMode ? '' : (keyValue ?? '')
  const effectiveModelActAs = accountMode ? ownedKeyId : undefined
  /**
   * Password managers key off focusable password fields. The key input
   * stays readonly until first focus, which keeps managers from claiming
   * it on sight. Typing always works: focus arms the field first.
   */
  const [keyArmed, setKeyArmed] = useState(false)

  const messages = useMemo(
    () => [{ role: 'user' as const, content: submitted?.prompt ?? '' }],
    [submitted],
  )

  const onSubmit = (d: FormData): void => {
    if (accountMode && ownedKeyId === '') {
      setError('model', { type: 'manual', message: 'Select an owned key first.' })
      return
    }
    const pastedKey = (d.key ?? '').trim()
    if (!accountMode && pastedKey === '') {
      setError('key', { type: 'manual', message: 'API key is required' })
      return
    }
    if (!accountMode) setGatewayKey(pastedKey)
    setSubmitted(d)
    setSubmittedActAsKey(accountMode ? ownedKeyId : null)
    runCounter.current += 1
    const id = runCounter.current
    setRunId(id)
    setHistory((h) => [{ id, model: d.model, prompt: d.prompt }, ...h].slice(0, 8))
    setStaticText(null)
    setStaticError(null)
    setStaticLatencyMs(null)
    setStreamSummary(null)
    if (!streaming) {
      const started = performance.now()
      const client = accountMode ? new GatewayClient() : new GatewayClient({ token: pastedKey })
      void client
        .chat(
          { model: d.model, messages: [{ role: 'user', content: d.prompt }] },
          accountMode ? { actAsKey: ownedKeyId } : { ignoreSession: true },
        )
        .then((out) => {
          const first = out.choices[0]
          setStaticText(first === undefined ? '(empty completion)' : first.message.content)
          setStaticLatencyMs(performance.now() - started)
        })
        .catch((e: unknown) => {
          setStaticError(toErrorMessage(e, 'Completion failed.'))
          setStaticLatencyMs(performance.now() - started)
        })
    }
  }

  const reloadRun = (run: RunRecord): void => {
    setValue('model', run.model)
    setValue('prompt', run.prompt)
  }

  /**
   * Fills the prompt box with one sanctioned sample. Output still requires
   * a real send — samples never fabricate a completion.
   *
   * @param prompt - Sample text to load into the prompt box.
   */
  const fillRecipe = (prompt: string): void => {
    setValue('prompt', prompt, { shouldValidate: true })
  }

  // New runs pull the output block into view (instant jump, never smooth:
  // operators re-run constantly and motion must not slow them down).
  useEffect(() => {
    if (runId > 0) outputRef.current?.scrollIntoView({ block: 'nearest' })
  }, [runId])

  /**
   * Submits the form from the keyboard without leaving the prompt box.
   *
   * @param e - Key event on the prompt textarea.
   */
  const submitOnShortcut = (e: KeyboardEvent<HTMLTextAreaElement>): void => {
    if ((e.ctrlKey || e.metaKey) && e.key === 'Enter') {
      e.preventDefault()
      void handleSubmit(onSubmit)(e)
    }
  }

  return (
    <div className="space-y-4">
      <div className="space-y-1">
        <p className="font-mono text-xs text-ink-soft dark:text-parchment-soft">
          <span aria-hidden="true" className="mr-1 text-ember">
            ❯
          </span>
          run
        </p>
        <div className="flex flex-wrap items-center gap-2">
          <h1 className="font-display text-3xl font-medium tracking-tight">Playground</h1>
          <span className="flex-1" />
          <p className="font-mono text-xs text-ink-soft tnum dark:text-parchment-soft">
            model {modelValue || 'unset'} · key{' '}
            {accountMode ? (ownedKeyId ? 'account' : 'missing') : keyValue ? 'set' : 'missing'} ·{' '}
            {streaming ? 'streaming' : 'static'} · {promptLength}/8000
          </p>
        </div>
        <p className="text-sm text-ink-soft dark:text-parchment-soft">
          Send a prompt through the gateway. Tokens, cost, and phase land live below.
        </p>
      </div>
      <div className="grid gap-6 lg:grid-cols-[1fr_280px]">
        <div className="space-y-4">
          <form
            onSubmit={(e) => {
              void handleSubmit(onSubmit)(e)
            }}
            aria-label="Prompt"
            className="rise space-y-3 rounded-xl border border-ink/10 bg-cream p-4 sm:p-5 dark:border-parchment/10 dark:bg-transparent"
          >
            <div className="flex items-baseline justify-between gap-3">
              <p className="font-mono text-[13px] text-ink-soft dark:text-parchment-soft">
                <span aria-hidden="true" className="mr-1 text-ember">
                  ❯
                </span>
                prompt
              </p>
              <p
                className={`font-mono text-xs tnum ${
                  overLimit
                    ? 'text-danger dark:text-danger-soft'
                    : 'text-ink-soft dark:text-parchment-soft'
                }`}
              >
                {promptLength}/8000{overLimit ? ' over limit' : null}
              </p>
            </div>
            <KeySourcePicker
              source={session === null ? 'paste' : keySource}
              onSourceChange={setKeySource}
              selectedKeyId={ownedKeyId}
              onSelectKeyId={setOwnedKeyId}
              idPrefix="pg"
            />
            <div className="grid gap-3 sm:grid-cols-2">
              <div>
                <ModelSelect
                  token={effectiveModelToken}
                  {...(effectiveModelActAs !== undefined && effectiveModelActAs !== ''
                    ? { actAsKey: effectiveModelActAs }
                    : {})}
                  id="pg-model"
                  value={modelValue}
                  onSelect={(v) => {
                    setValue('model', v, { shouldValidate: true, shouldDirty: true })
                  }}
                  invalid={errors.model !== undefined}
                />
                {errors.model === undefined ? null : (
                  <p role="alert" className="mt-1 text-[13px] text-danger dark:text-danger-soft">
                    {errors.model.message}
                  </p>
                )}
              </div>
              {accountMode ? null : (
                <div>
                  <label htmlFor="pg-key" className="mb-1 block text-[13px] font-medium">
                    API key (memory only, never stored)
                  </label>
                  <input
                    id="pg-key"
                    type="password"
                    autoComplete="new-password"
                    data-1p-ignore="true"
                    data-lpignore="true"
                    data-bwignore="true"
                    readOnly={!keyArmed}
                    onFocus={() => {
                      setKeyArmed(true)
                    }}
                    {...register('key')}
                    aria-invalid={errors.key !== undefined}
                    className="w-full rounded-md border border-ink/15 bg-transparent px-3 py-2 font-mono text-sm dark:border-parchment/15"
                  />
                  {errors.key === undefined ? null : (
                    <p role="alert" className="mt-1 text-[13px] text-danger dark:text-danger-soft">
                      {errors.key.message}
                    </p>
                  )}
                </div>
              )}
            </div>
            <div>
              <label htmlFor="pg-prompt" className="mb-1 block text-[13px] font-medium">
                Prompt
              </label>
              <textarea
                id="pg-prompt"
                rows={6}
                {...register('prompt')}
                onKeyDown={submitOnShortcut}
                aria-invalid={errors.prompt !== undefined}
                aria-describedby="pg-shortcut-hint"
                className="w-full rounded-md border border-ink/15 bg-transparent px-3 py-2 text-sm dark:border-parchment/15"
              />
              {errors.prompt === undefined ? null : (
                <p role="alert" className="mt-1 text-[13px] text-danger dark:text-danger-soft">
                  {errors.prompt.message}
                </p>
              )}
            </div>
            <div className="flex items-center justify-between gap-3">
              <p className="min-w-0 flex-1 truncate font-mono text-xs text-ink-soft dark:text-parchment-soft">
                run #{runId + 1} ·{' '}
                {submitted === null ? 'ready' : streaming ? 'streaming' : 'static'}
              </p>
              <p
                id="pg-shortcut-hint"
                className="hidden shrink-0 font-mono text-xs whitespace-nowrap text-ink-soft sm:block dark:text-parchment-soft"
              >
                <kbd>Ctrl</kbd>+<kbd>Enter</kbd> to send
              </p>
              <button
                type="submit"
                disabled={isSubmitting || overLimit}
                className="shrink-0 rounded-md bg-ink px-4 py-2 text-sm font-medium whitespace-nowrap text-paper disabled:cursor-not-allowed disabled:bg-ink-soft disabled:text-paper dark:bg-parchment dark:text-night dark:disabled:bg-parchment-soft dark:disabled:text-night"
              >
                {isSubmitting ? 'Starting…' : streaming ? 'Stream completion' : 'Send completion'}
              </button>
            </div>
          </form>
          <details className="rounded-xl border border-ink/10 bg-cream p-4 dark:border-parchment/10 dark:bg-transparent">
            <summary className="cursor-pointer font-mono text-[13px]">
              Start from a recipe instead
            </summary>
            <div className="mt-2 flex flex-wrap gap-2">
              {SAMPLES.map((sample) => (
                <button
                  key={sample.label}
                  type="button"
                  onClick={() => {
                    fillRecipe(sample.prompt)
                  }}
                  className="rounded-md border border-ink/15 px-3 py-2 text-[13px] dark:border-parchment/15"
                >
                  {sample.label}
                </button>
              ))}
            </div>
            <p className="mt-2 text-[13px] text-ink-soft dark:text-parchment-soft">
              Recipes fill the prompt box only. Output still requires a real send.
            </p>
          </details>
          {submitted === null ? (
            <EmptyTrio
              title="No output yet"
              cue="Pick a model, choose a key, write a prompt. Then send. Tokens, cost, and phase show here as the stream flows. Recipes live under Start from a recipe instead above."
            />
          ) : (
            <section
              ref={outputRef}
              aria-label={`Run ${String(runId)}: ${submitted.model}`}
              className="rise space-y-3 rounded-xl border border-ink/10 bg-cream p-4 sm:p-5 dark:border-parchment/10 dark:bg-transparent"
            >
              <div className="flex flex-wrap items-center gap-3">
                <p className="font-mono text-xs text-ink-soft tnum dark:text-parchment-soft">
                  run #{runId} · {submitted.model} · {streaming ? 'streaming' : 'static'}
                </p>
                <span className="flex-1" />
                <button
                  type="button"
                  onClick={() => {
                    setValue('model', submitted.model)
                    setValue('prompt', submitted.prompt)
                    void handleSubmit(onSubmit)()
                  }}
                  className="rounded-md border border-ink/15 px-3 py-2 text-[13px] dark:border-parchment/15"
                >
                  Rerun
                </button>
              </div>
              {streaming ? (
                <SseStreamViewer
                  key={runId}
                  token={accountMode ? session.accessToken : (submitted.key ?? '')}
                  {...(submittedActAsKey !== null && submittedActAsKey !== ''
                    ? { actAsKey: submittedActAsKey }
                    : {})}
                  model={submitted.model}
                  messages={messages}
                  onSummary={setStreamSummary}
                />
              ) : (
                <div aria-label="Completion result" className="space-y-2">
                  {staticError === null ? null : (
                    <p role="alert" className="text-sm text-danger dark:text-danger-soft">
                      {staticError}
                    </p>
                  )}
                  {staticText === null ? null : (
                    <div
                      role="log"
                      aria-live="polite"
                      aria-label="Non-streamed completion"
                      className="min-h-32 font-mono text-sm whitespace-pre-wrap"
                    >
                      {staticText}
                    </div>
                  )}
                </div>
              )}
              <div className="grid gap-4 lg:grid-cols-2">
                <RequestCard model={submitted.model} />
                <RunDetailPanel
                  detail={
                    streaming
                      ? {
                          runId,
                          model: submitted.model,
                          streaming: true,
                          status:
                            streamSummary === null
                              ? 'running'
                              : streamSummary.phase === 'done'
                                ? 'done'
                                : 'error',
                          ...(streamSummary === null
                            ? {}
                            : {
                                latencyMs: streamSummary.durationMs,
                                frames: streamSummary.frames,
                                cacheTier: streamSummary.cacheTier,
                                similarity: streamSummary.similarity,
                                age: streamSummary.age,
                                ...(streamSummary.error === undefined
                                  ? {}
                                  : { error: streamSummary.error }),
                              }),
                        }
                      : {
                          runId,
                          model: submitted.model,
                          streaming: false,
                          status:
                            staticError !== null
                              ? 'error'
                              : staticText !== null
                                ? 'done'
                                : 'running',
                          ...(staticLatencyMs === null ? {} : { latencyMs: staticLatencyMs }),
                          ...(staticError === null ? {} : { error: staticError }),
                        }
                  }
                />
              </div>
            </section>
          )}
        </div>
        <aside aria-label="Run history" className="space-y-3">
          <h2 className="font-mono text-xs text-ink-soft dark:text-parchment-soft">run history</h2>
          {history.length === 0 ? (
            <div className="rounded-xl border border-ink/10 bg-cream p-4 dark:border-parchment/10 dark:bg-transparent">
              <ul className="space-y-2 text-[13px] text-ink-soft dark:text-parchment-soft">
                <li>Fill the prompt, pick a model, send.</li>
                <li>Paste a key once. It lives in memory only.</li>
                <li>Press Ctrl+K to jump anywhere.</li>
                <li>Past runs land here for one-click reload.</li>
              </ul>
            </div>
          ) : (
            <ol className="space-y-2">
              {history.map((run) => (
                <li key={run.id}>
                  <button
                    type="button"
                    onClick={() => {
                      reloadRun(run)
                    }}
                    className="lift w-full rounded-lg border border-ink/10 bg-cream p-3 text-left dark:border-parchment/10 dark:bg-transparent"
                  >
                    <p className="font-mono text-xs text-ink-soft dark:text-parchment-soft">
                      run #{run.id} · {run.model}
                    </p>
                    <p className="mt-1 truncate text-[13px]">{run.prompt}</p>
                  </button>
                </li>
              ))}
            </ol>
          )}
        </aside>
      </div>
    </div>
  )
}
