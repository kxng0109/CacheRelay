import { zodResolver } from '@hookform/resolvers/zod'
import { useMemo, useState } from 'react'
import { useForm, useWatch } from 'react-hook-form'
import { useShallow } from 'zustand/react/shallow'
import * as z from 'zod/v4'
import { GatewayClient } from '../../shared/api/client.js'
import { isStreamingEnabled } from '../../shared/api/client.js'
import { toErrorMessage } from '../../shared/api/client.js'
import { useAuthStore } from '../../shared/auth/store.js'
import { SseStreamViewer } from './SseStreamViewer.js'

const schema = z.object({
  model: z.string().min(1, 'Model is required'),
  prompt: z.string().min(1, 'Prompt is required').max(8000, 'Prompt is too long'),
  key: z.string().min(1, 'API key is required'),
})

type FormData = z.infer<typeof schema>

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
  const { gatewayKey, setGatewayKey } = useAuthStore(
    useShallow((s) => ({ gatewayKey: s.gatewayKey, setGatewayKey: s.setGatewayKey })),
  )
  const [runId, setRunId] = useState(0)
  const [submitted, setSubmitted] = useState<FormData | null>(null)
  const [staticText, setStaticText] = useState<string | null>(null)
  const [staticError, setStaticError] = useState<string | null>(null)
  const [history, setHistory] = useState<RunRecord[]>([])
  const streaming = isStreamingEnabled()

  const {
    register,
    handleSubmit,
    setValue,
    control,
    formState: { errors, isSubmitting },
  } = useForm<FormData>({
    resolver: zodResolver(schema),
    mode: 'onSubmit',
    defaultValues: { model: 'gpt-56-luna', prompt: '', key: gatewayKey ?? '' },
  })
  const promptLength = useWatch({ control, name: 'prompt' }).length

  const messages = useMemo(
    () => [{ role: 'user' as const, content: submitted?.prompt ?? '' }],
    [submitted],
  )

  const onSubmit = (d: FormData): void => {
    setGatewayKey(d.key)
    setSubmitted(d)
    setRunId((n) => {
      setHistory((h) => [{ id: n + 1, model: d.model, prompt: d.prompt }, ...h].slice(0, 8))
      return n + 1
    })
    setStaticText(null)
    setStaticError(null)
    if (!streaming) {
      void new GatewayClient({ token: d.key })
        .chat({ model: d.model, messages: [{ role: 'user', content: d.prompt }] })
        .then((out) => {
          const first = out.choices[0]
          setStaticText(first === undefined ? '(empty completion)' : first.message.content)
        })
        .catch((e: unknown) => {
          setStaticError(toErrorMessage(e, 'Completion failed.'))
        })
    }
  }

  const reloadRun = (run: RunRecord): void => {
    setValue('model', run.model)
    setValue('prompt', run.prompt)
  }

  return (
    <div className="space-y-4">
      <h1 className="font-display text-2xl font-medium tracking-tight">Playground</h1>
      <div className="grid gap-6 lg:grid-cols-[1fr_280px]">
        <div className="space-y-4">
          <form
            onSubmit={(e) => {
              void handleSubmit(onSubmit)(e)
            }}
            aria-label="Prompt"
            className="space-y-3 rounded-xl border border-ink/10 bg-cream p-4 dark:border-parchment/10 dark:bg-transparent"
          >
            <div className="flex items-baseline justify-between gap-3">
              <p className="font-mono text-xs text-ink-soft dark:text-parchment-soft">
                <span aria-hidden="true" className="mr-1 text-ember">
                  ❯
                </span>
                prompt
              </p>
              <p className="font-mono text-[11px] text-ink-soft tnum dark:text-parchment-soft">
                {promptLength}/8000
              </p>
            </div>
            <div className="grid gap-3 sm:grid-cols-2">
              <div>
                <label htmlFor="pg-model" className="mb-1 block text-xs font-medium">
                  Model
                </label>
                <input
                  id="pg-model"
                  {...register('model')}
                  aria-invalid={errors.model !== undefined}
                  className="w-full rounded-md border border-ink/15 bg-transparent px-3 py-2 font-mono text-sm dark:border-parchment/15"
                />
                {errors.model === undefined ? null : (
                  <p role="alert" className="mt-1 text-xs text-danger dark:text-danger-soft">
                    {errors.model.message}
                  </p>
                )}
              </div>
              <div>
                <label htmlFor="pg-key" className="mb-1 block text-xs font-medium">
                  API key (memory only, never stored)
                </label>
                <input
                  id="pg-key"
                  type="password"
                  autoComplete="off"
                  {...register('key')}
                  aria-invalid={errors.key !== undefined}
                  className="w-full rounded-md border border-ink/15 bg-transparent px-3 py-2 font-mono text-sm dark:border-parchment/15"
                />
                {errors.key === undefined ? null : (
                  <p role="alert" className="mt-1 text-xs text-danger dark:text-danger-soft">
                    {errors.key.message}
                  </p>
                )}
              </div>
            </div>
            <div>
              <label htmlFor="pg-prompt" className="mb-1 block text-xs font-medium">
                Prompt
              </label>
              <textarea
                id="pg-prompt"
                rows={6}
                {...register('prompt')}
                aria-invalid={errors.prompt !== undefined}
                className="w-full rounded-md border border-ink/15 bg-transparent px-3 py-2 text-sm dark:border-parchment/15"
              />
              {errors.prompt === undefined ? null : (
                <p role="alert" className="mt-1 text-xs text-danger dark:text-danger-soft">
                  {errors.prompt.message}
                </p>
              )}
            </div>
            <div className="flex items-center justify-between gap-3">
              <p className="font-mono text-[11px] text-ink-soft dark:text-parchment-soft">
                run #{runId + 1} · {streaming ? 'streaming' : 'static'}
              </p>
              <button
                type="submit"
                disabled={isSubmitting}
                className="rounded-md bg-ink px-4 py-2 text-sm font-medium text-paper disabled:opacity-50 dark:bg-parchment dark:text-night"
              >
                {isSubmitting ? 'Starting…' : streaming ? 'Stream completion' : 'Send completion'}
              </button>
            </div>
          </form>
          {submitted === null ? (
            <div className="rounded-xl border border-dashed border-ink/20 p-6 text-center dark:border-parchment/20">
              <p className="font-display text-xl font-medium tracking-tight">No output yet</p>
              <p className="mt-1 text-xs text-ink-soft dark:text-parchment-soft">
                Submit a prompt above. The stream lands here with tokens, cost, and phase.
              </p>
            </div>
          ) : streaming ? (
            <SseStreamViewer
              key={runId}
              token={submitted.key}
              model={submitted.model}
              messages={messages}
            />
          ) : (
            <section aria-label="Completion result" className="space-y-2">
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
            </section>
          )}
        </div>
        <aside aria-label="Run history" className="space-y-3">
          <h2 className="font-mono text-[11px] text-ink-soft dark:text-parchment-soft">
            run history
          </h2>
          {history.length === 0 ? (
            <ul className="space-y-2 text-xs text-ink-soft dark:text-parchment-soft">
              <li>Fill the prompt, pick a model, send.</li>
              <li>Paste a key once — it lives in memory only.</li>
              <li>Press Ctrl+K to jump anywhere.</li>
              <li>Past runs land here for one-click reload.</li>
            </ul>
          ) : (
            <ol className="space-y-2">
              {history.map((run) => (
                <li key={run.id}>
                  <button
                    type="button"
                    onClick={() => {
                      reloadRun(run)
                    }}
                    className="w-full rounded-lg border border-ink/10 bg-cream p-3 text-left dark:border-parchment/10 dark:bg-transparent"
                  >
                    <p className="font-mono text-[11px] text-ink-soft dark:text-parchment-soft">
                      run #{run.id} · {run.model}
                    </p>
                    <p className="mt-1 truncate text-xs">{run.prompt}</p>
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
