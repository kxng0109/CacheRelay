import { zodResolver } from '@hookform/resolvers/zod'
import { useState } from 'react'
import { useForm, useWatch } from 'react-hook-form'
import { useShallow } from 'zustand/react/shallow'
import * as z from 'zod/v4'
import { GatewayClient } from '../../shared/api/client.js'
import { toErrorMessage } from '../../shared/api/client.js'
import { useAuthStore } from '../../shared/auth/store.js'
import { ModelSelect } from '../../shared/models/ModelSelect.js'
import { InspectorShell } from '../../shared/components/InspectorShell.js'

const schema = z.object({
  model: z.string().min(1, 'Model is required'),
  input: z.string().min(1, 'Input text is required').max(8000, 'Input is too long'),
  key: z.string().min(1, 'API key is required'),
})

type FormData = z.infer<typeof schema>

/** Sanctioned sample: fills the input box only, never fabricates vectors. */
const SAMPLE_INPUT = 'CacheRelay routes every request through admission, cache, and router stages.'

interface UsageRecord {
  id: number
  at: string
  model: string
  chars: number
  vecs: number | null
  dims: number | null
  status: 'ok' | 'fail'
  error: string | null
  input: string
}

/**
 * Embeddings console: vectorize text, track session usage, inspect runs.
 *
 * @remarks Proof-type: live (real `POST /v1/embeddings`). Pane header
 * carries the model chip and session counters; every submitted request
 * lands in the in-session usage table (newest first) with a drill-down
 * inspector. History never leaves memory.
 *
 * @returns The embeddings screen.
 */
export function EmbeddingsPage(): React.JSX.Element {
  const { gatewayKey, setGatewayKey } = useAuthStore(
    useShallow((s) => ({ gatewayKey: s.gatewayKey, setGatewayKey: s.setGatewayKey })),
  )
  const [error, setError] = useState<string | null>(null)
  const [runs, setRuns] = useState<UsageRecord[]>([])
  const [filter, setFilter] = useState('')
  const [selected, setSelected] = useState<number | null>(null)
  const [lastModel, setLastModel] = useState<string | null>(null)
  /**
   * Password managers key off focusable password fields. The key input
   * stays readonly until first focus, which keeps managers from claiming
   * it on sight. Typing always works: focus arms the field first.
   */
  const [keyArmed, setKeyArmed] = useState(false)

  const {
    register,
    handleSubmit,
    setValue,
    control,
    formState: { errors, isSubmitting },
  } = useForm<FormData>({
    resolver: zodResolver(schema),
    mode: 'onSubmit',
    defaultValues: { model: '', input: '', key: gatewayKey ?? '' },
  })
  const inputLength = useWatch({ control, name: 'input' }).length
  const keyValue = useWatch({ control, name: 'key' })
  const modelValue = useWatch({ control, name: 'model' })
  const overLimit = inputLength > 8000

  const onSubmit = async (d: FormData): Promise<void> => {
    setGatewayKey(d.key)
    setError(null)
    try {
      const out = await new GatewayClient({ token: d.key }).embeddings({
        model: d.model,
        input: d.input,
      })
      const first = out.data[0]
      setLastModel(d.model)
      setRuns((prev) =>
        [
          {
            id: Date.now(),
            at: new Date().toISOString(),
            model: d.model,
            chars: d.input.length,
            vecs: out.data.length,
            dims: first === undefined ? 0 : first.embedding.length,
            status: 'ok' as const,
            error: null,
            input: d.input,
          },
          ...prev,
        ].slice(0, 50),
      )
    } catch (e) {
      const message = toErrorMessage(e, 'Embedding request failed.')
      setError(message)
      setLastModel(d.model)
      setRuns((prev) =>
        [
          {
            id: Date.now(),
            at: new Date().toISOString(),
            model: d.model,
            chars: d.input.length,
            vecs: null,
            dims: null,
            status: 'fail' as const,
            error: message,
            input: d.input,
          },
          ...prev,
        ].slice(0, 50),
      )
    }
  }

  const query = filter.trim().toLowerCase()
  const visible = runs.filter((r) => query.length === 0 || r.model.toLowerCase().includes(query))
  const inspected = runs.find((r) => r.id === selected) ?? null

  /**
   * Fills the input box with the sanctioned sample. Vectors still require
   * a real submission — the sample never fabricates a run.
   */
  const fillSample = (): void => {
    setValue('input', SAMPLE_INPUT, { shouldValidate: true })
  }

  /**
   * Retries the last submission with the current form values.
   */
  const retrySubmit = (): void => {
    void handleSubmit(onSubmit)()
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
          <h1 className="font-display text-3xl font-medium tracking-tight">Embeddings</h1>
          {lastModel === null ? null : (
            <span className="rounded-full border border-ink/15 px-2 py-0.5 font-mono text-xs dark:border-parchment/15">
              model:{lastModel}
            </span>
          )}
          <span className="flex-1" />
          <p className="font-mono text-[13px] text-ink-soft tnum dark:text-parchment-soft">
            vectors:{runs.filter((r) => r.status === 'ok').reduce((n, r) => n + (r.vecs ?? 0), 0)}{' '}
            runs:{runs.length}
          </p>
        </div>
        <p className="text-sm text-ink-soft dark:text-parchment-soft">
          Turn text into vectors. Usage builds below as runs complete.
        </p>
      </div>
      {error === null ? null : (
        <div
          role="alert"
          className="rounded-lg border border-ink/10 bg-cream p-3 dark:border-parchment/10 dark:bg-transparent"
        >
          <p className="text-sm text-danger dark:text-danger-soft">
            Embedding request failed: {error}
          </p>
          <button
            type="button"
            onClick={retrySubmit}
            className="mt-2 rounded-md border border-ink/15 px-3 py-2 text-[13px] dark:border-parchment/15"
          >
            Retry
          </button>
        </div>
      )}
      <div className="grid gap-6 lg:grid-cols-[1fr_280px]">
        <div className="space-y-4">
          <form
            onSubmit={(e) => {
              void handleSubmit(onSubmit)(e)
            }}
            aria-label="Embed"
            className="space-y-3 rounded-xl border border-ink/10 bg-cream p-4 dark:border-parchment/10 dark:bg-transparent"
          >
            <div className="grid gap-3 sm:grid-cols-2">
              <div>
                <label htmlFor="emb-model" className="mb-1 block text-[13px] font-medium">
                  Model
                </label>
                <ModelSelect
                  token={keyValue}
                  id="emb-model"
                  registration={register('model')}
                  value={modelValue}
                  invalid={errors.model !== undefined}
                />
                {errors.model === undefined ? null : (
                  <p role="alert" className="mt-1 text-[13px] text-danger dark:text-danger-soft">
                    {errors.model.message}
                  </p>
                )}
              </div>
              <div>
                <label htmlFor="emb-key" className="mb-1 block text-[13px] font-medium">
                  API key (memory only, never stored)
                </label>
                <input
                  id="emb-key"
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
            </div>
            <div>
              <div className="mb-1 flex items-baseline justify-between gap-3">
                <label htmlFor="emb-input" className="block text-[13px] font-medium">
                  Input text
                </label>
                <p
                  className={`font-mono text-xs tnum ${
                    overLimit
                      ? 'text-danger dark:text-danger-soft'
                      : 'text-ink-soft dark:text-parchment-soft'
                  }`}
                >
                  {inputLength}/8000{overLimit ? ' over limit' : null}
                </p>
              </div>
              <textarea
                id="emb-input"
                rows={4}
                {...register('input')}
                aria-invalid={errors.input !== undefined || overLimit}
                className="w-full rounded-md border border-ink/15 bg-transparent px-3 py-2 text-sm dark:border-parchment/15"
              />
              {overLimit ? (
                <p role="alert" className="mt-1 text-[13px] text-danger dark:text-danger-soft">
                  Input is over the 8000 character limit. Shorten it to send.
                </p>
              ) : null}
              {errors.input === undefined ? null : (
                <p role="alert" className="mt-1 text-[13px] text-danger dark:text-danger-soft">
                  {errors.input.message}
                </p>
              )}
            </div>
            <div className="flex items-center justify-between gap-3">
              <p className="min-w-0 flex-1 truncate font-mono text-xs text-ink-soft dark:text-parchment-soft">
                run #{runs.length + 1}
              </p>
              <button
                type="submit"
                disabled={isSubmitting || overLimit}
                className="shrink-0 rounded-md bg-ink px-4 py-2 text-sm font-medium whitespace-nowrap text-paper disabled:cursor-not-allowed disabled:bg-ink-soft disabled:text-paper dark:bg-parchment dark:text-night dark:disabled:bg-parchment-soft dark:disabled:text-night"
              >
                {isSubmitting ? 'Embedding…' : 'Create embeddings'}
              </button>
            </div>
          </form>
          <div className="flex flex-wrap items-center gap-2">
            <label htmlFor="emb-filter" className="sr-only">
              Filter usage by model
            </label>
            <input
              id="emb-filter"
              type="search"
              value={filter}
              placeholder="Filter"
              onChange={(e) => {
                setFilter(e.target.value)
              }}
              className="w-48 rounded-md border border-ink/15 bg-transparent px-3 py-2 text-[13px] dark:border-parchment/15"
            />
          </div>
          {runs.length === 0 ? (
            <div className="rise rounded-xl border border-dashed border-ink/20 p-6 text-center sm:p-8 dark:border-parchment/20">
              <p className="font-display text-xl font-medium tracking-tight">No runs yet</p>
              <p className="mx-auto mt-1 max-w-md text-[13px] text-ink-soft dark:text-parchment-soft">
                Paste a key, pick a model, submit text. Vectors and usage land here.
              </p>
              <button
                type="button"
                onClick={fillSample}
                className="mt-3 rounded-md border border-ink/15 px-3 py-2 text-[13px] dark:border-parchment/15"
              >
                Fill sample text
              </button>
            </div>
          ) : visible.length === 0 ? (
            <p className="text-sm text-ink-soft dark:text-parchment-soft">
              No runs match this filter.
            </p>
          ) : (
            <table className="w-full text-left text-sm">
              <caption className="sr-only">Session embedding usage</caption>
              <thead className="sticky top-0 bg-paper dark:bg-night">
                <tr className="font-mono text-xs text-ink-soft dark:text-parchment-soft">
                  <th scope="col" className="py-2 pr-3 font-medium">
                    Time
                  </th>
                  <th scope="col" className="py-2 pr-3 font-medium">
                    Model
                  </th>
                  <th scope="col" className="py-2 pr-3 text-right font-medium">
                    Chars
                  </th>
                  <th scope="col" className="py-2 pr-3 text-right font-medium">
                    Vectors
                  </th>
                  <th scope="col" className="py-2 pr-3 text-right font-medium">
                    Dims
                  </th>
                  <th scope="col" className="py-2 text-right font-medium">
                    Status
                  </th>
                </tr>
              </thead>
              <tbody>
                {visible.map((r) => (
                  <tr
                    key={r.id}
                    tabIndex={0}
                    aria-selected={r.id === selected}
                    onClick={() => {
                      setSelected(r.id === selected ? null : r.id)
                    }}
                    onKeyDown={(event) => {
                      if (event.key === 'Enter' || event.key === ' ') {
                        event.preventDefault()
                        setSelected(r.id === selected ? null : r.id)
                      }
                    }}
                    className={`cursor-pointer border-t border-ink/10 dark:border-parchment/10 ${
                      r.id === selected ? 'bg-ink/4 dark:bg-parchment/6' : ''
                    }`}
                  >
                    <td className="py-2 pr-3 font-mono text-[13px] tnum">{r.at}</td>
                    <td className="max-w-44 truncate py-2 pr-3 font-mono text-[13px]">{r.model}</td>
                    <td className="py-2 pr-3 text-right text-[13px] tnum">{r.chars}</td>
                    <td className="py-2 pr-3 text-right text-[13px] tnum">{r.vecs ?? '—'}</td>
                    <td className="py-2 pr-3 text-right text-[13px] tnum">{r.dims ?? '—'}</td>
                    <td className="py-2 text-right text-[13px]">
                      {r.status === 'ok' ? '● ok' : '■ fail'}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </div>
        <aside aria-label="Run inspector" className="space-y-3">
          {inspected === null ? (
            <p className="text-[13px] text-ink-soft dark:text-parchment-soft">
              Select a run to inspect vectors and errors.
            </p>
          ) : (
            <InspectorShell
              title={`run ${inspected.at}`}
              onClose={() => {
                setSelected(null)
              }}
            >
              <dl className="space-y-2 text-[13px]">
                <div className="flex justify-between gap-3">
                  <dt className="text-ink-soft dark:text-parchment-soft">Status</dt>
                  <dd>{inspected.status === 'ok' ? '● ok' : '■ fail'}</dd>
                </div>
                <div className="flex justify-between gap-3">
                  <dt className="text-ink-soft dark:text-parchment-soft">Model</dt>
                  <dd className="font-mono">{inspected.model}</dd>
                </div>
                <div className="flex justify-between gap-3">
                  <dt className="text-ink-soft dark:text-parchment-soft">Chars / vectors / dims</dt>
                  <dd className="tnum">
                    {inspected.chars} / {inspected.vecs ?? '—'} / {inspected.dims ?? '—'}
                  </dd>
                </div>
                {inspected.error === null ? null : (
                  <div className="flex justify-between gap-3">
                    <dt className="text-ink-soft dark:text-parchment-soft">Error</dt>
                    <dd className="text-danger dark:text-danger-soft">{inspected.error}</dd>
                  </div>
                )}
              </dl>
              <div>
                <p className="mb-1 text-[13px] text-ink-soft dark:text-parchment-soft">
                  Input excerpt
                </p>
                <pre className="max-h-40 overflow-auto rounded-md border border-ink/10 p-2 font-mono text-xs whitespace-pre-wrap dark:border-parchment/10">
                  {inspected.input.slice(0, 500)}
                </pre>
              </div>
            </InspectorShell>
          )}
        </aside>
      </div>
    </div>
  )
}
