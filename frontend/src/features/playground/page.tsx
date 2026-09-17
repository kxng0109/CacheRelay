import { zodResolver } from '@hookform/resolvers/zod'
import { useMemo, useState } from 'react'
import { useForm } from 'react-hook-form'
import { useShallow } from 'zustand/react/shallow'
import * as z from 'zod/v4'
import { useAuthStore } from '../../shared/auth/store.js'
import { SseStreamViewer } from './SseStreamViewer.js'

const schema = z.object({
  model: z.string().min(1, 'Model is required'),
  prompt: z.string().min(1, 'Prompt is required').max(8000, 'Prompt is too long'),
  key: z.string().min(1, 'API key is required'),
})

type FormData = z.infer<typeof schema>

/**
 * Playground page: credential entry (memory-only) plus live SSE completion.
 *
 * @remarks Proof-type: live centerpiece of the console (one bold element: the stream).
 *
 * @returns The playground screen.
 */
export function PlaygroundPage(): React.JSX.Element {
  const { gatewayKey, setGatewayKey } = useAuthStore(
    useShallow((s) => ({ gatewayKey: s.gatewayKey, setGatewayKey: s.setGatewayKey })),
  )
  const [runId, setRunId] = useState(0)
  const [submitted, setSubmitted] = useState<FormData | null>(null)

  const {
    register,
    handleSubmit,
    formState: { errors, isSubmitting },
  } = useForm<FormData>({
    resolver: zodResolver(schema),
    mode: 'onSubmit',
    defaultValues: { model: 'gpt-4o-mini', prompt: '', key: gatewayKey ?? '' },
  })

  const messages = useMemo(
    () => [{ role: 'user' as const, content: submitted?.prompt ?? '' }],
    [submitted],
  )

  const onSubmit = (d: FormData): void => {
    setGatewayKey(d.key)
    setSubmitted(d)
    setRunId((n) => n + 1)
  }

  return (
    <div className="space-y-4">
      <h1 className="text-xl font-semibold tracking-tight">Playground</h1>
      <form
        onSubmit={(e) => {
          void handleSubmit(onSubmit)(e)
        }}
        className="space-y-3 rounded-lg border border-ink/10 p-4 dark:border-parchment/10"
      >
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
            className="w-full rounded-md border border-ink/15 bg-transparent px-3 py-2 text-sm dark:border-parchment/15"
          />
          {errors.key === undefined ? null : (
            <p role="alert" className="mt-1 text-xs text-danger">
              {errors.key.message}
            </p>
          )}
        </div>
        <div>
          <label htmlFor="pg-model" className="mb-1 block text-xs font-medium">
            Model
          </label>
          <input
            id="pg-model"
            {...register('model')}
            aria-invalid={errors.model !== undefined}
            className="w-full rounded-md border border-ink/15 bg-transparent px-3 py-2 text-sm dark:border-parchment/15"
          />
          {errors.model === undefined ? null : (
            <p role="alert" className="mt-1 text-xs text-danger">
              {errors.model.message}
            </p>
          )}
        </div>
        <div>
          <label htmlFor="pg-prompt" className="mb-1 block text-xs font-medium">
            Prompt
          </label>
          <textarea
            id="pg-prompt"
            rows={4}
            {...register('prompt')}
            aria-invalid={errors.prompt !== undefined}
            className="w-full rounded-md border border-ink/15 bg-transparent px-3 py-2 text-sm dark:border-parchment/15"
          />
          {errors.prompt === undefined ? null : (
            <p role="alert" className="mt-1 text-xs text-danger">
              {errors.prompt.message}
            </p>
          )}
        </div>
        <button
          type="submit"
          disabled={isSubmitting}
          className="rounded-md bg-ember px-4 py-2 text-sm font-medium text-white disabled:opacity-50"
        >
          {isSubmitting ? 'Starting…' : 'Stream completion'}
        </button>
      </form>
      {submitted === null ? null : (
        <SseStreamViewer
          key={runId}
          token={submitted.key}
          model={submitted.model}
          messages={messages}
        />
      )}
    </div>
  )
}
