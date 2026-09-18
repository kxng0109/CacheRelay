import { zodResolver } from '@hookform/resolvers/zod'
import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { useShallow } from 'zustand/react/shallow'
import * as z from 'zod/v4'
import { GatewayClient } from '../../shared/api/client.js'
import { toErrorMessage } from '../../shared/api/client.js'
import { useAuthStore } from '../../shared/auth/store.js'

const schema = z.object({
  model: z.string().min(1, 'Model is required'),
  input: z.string().min(1, 'Input text is required').max(8000, 'Input is too long'),
  key: z.string().min(1, 'API key is required'),
})

type FormData = z.infer<typeof schema>

/**
 * Embeddings console: vectorize text and inspect dimensions.
 *
 * @remarks Proof-type: live (real `POST /v1/embeddings`).
 *
 * @returns The embeddings screen.
 */
export function EmbeddingsPage(): React.JSX.Element {
  const { gatewayKey, setGatewayKey } = useAuthStore(
    useShallow((s) => ({ gatewayKey: s.gatewayKey, setGatewayKey: s.setGatewayKey })),
  )
  const [dims, setDims] = useState<number | null>(null)
  const [count, setCount] = useState<number | null>(null)
  const [error, setError] = useState<string | null>(null)

  const {
    register,
    handleSubmit,
    formState: { errors, isSubmitting },
  } = useForm<FormData>({
    resolver: zodResolver(schema),
    mode: 'onSubmit',
    defaultValues: { model: 'text-embedding-3-small', input: '', key: gatewayKey ?? '' },
  })

  const onSubmit = async (d: FormData): Promise<void> => {
    setGatewayKey(d.key)
    setError(null)
    setDims(null)
    setCount(null)
    try {
      const out = await new GatewayClient({ token: d.key }).embeddings({
        model: d.model,
        input: d.input,
      })
      setCount(out.data.length)
      const first = out.data[0]
      setDims(first === undefined ? 0 : first.embedding.length)
    } catch (e) {
      setError(toErrorMessage(e, 'Embedding request failed.'))
    }
  }

  return (
    <div className="space-y-4">
      <h1 className="font-display text-2xl font-medium tracking-tight">Embeddings</h1>
      <form
        onSubmit={(e) => {
          void handleSubmit(onSubmit)(e)
        }}
        className="space-y-3 rounded-lg border border-ink/10 bg-cream p-4 dark:border-parchment/10 dark:bg-transparent"
      >
        <div>
          <label htmlFor="emb-key" className="mb-1 block text-xs font-medium">
            API key (memory only, never stored)
          </label>
          <input
            id="emb-key"
            type="password"
            autoComplete="off"
            {...register('key')}
            aria-invalid={errors.key !== undefined}
            className="w-full rounded-md border border-ink/15 bg-transparent px-3 py-2 text-sm dark:border-parchment/15"
          />
          {errors.key === undefined ? null : (
            <p role="alert" className="mt-1 text-xs text-danger dark:text-danger-soft">
              {errors.key.message}
            </p>
          )}
        </div>
        <div>
          <label htmlFor="emb-model" className="mb-1 block text-xs font-medium">
            Model
          </label>
          <input
            id="emb-model"
            {...register('model')}
            aria-invalid={errors.model !== undefined}
            className="w-full rounded-md border border-ink/15 bg-transparent px-3 py-2 text-sm dark:border-parchment/15"
          />
          {errors.model === undefined ? null : (
            <p role="alert" className="mt-1 text-xs text-danger dark:text-danger-soft">
              {errors.model.message}
            </p>
          )}
        </div>
        <div>
          <label htmlFor="emb-input" className="mb-1 block text-xs font-medium">
            Input text
          </label>
          <textarea
            id="emb-input"
            rows={4}
            {...register('input')}
            aria-invalid={errors.input !== undefined}
            className="w-full rounded-md border border-ink/15 bg-transparent px-3 py-2 text-sm dark:border-parchment/15"
          />
          {errors.input === undefined ? null : (
            <p role="alert" className="mt-1 text-xs text-danger dark:text-danger-soft">
              {errors.input.message}
            </p>
          )}
        </div>
        <button
          type="submit"
          disabled={isSubmitting}
          className="rounded-md bg-ink px-4 py-2 text-sm font-medium text-paper disabled:opacity-50 dark:bg-parchment dark:text-night"
        >
          {isSubmitting ? 'Embedding…' : 'Create embeddings'}
        </button>
      </form>
      {error === null ? null : (
        <p role="alert" className="text-sm text-danger dark:text-danger-soft">
          {error}
        </p>
      )}
      {dims === null || count === null ? null : (
        <dl role="status" className="grid grid-cols-2 gap-3">
          <div className="rounded-lg border border-ink/10 bg-cream p-3 dark:border-parchment/10 dark:bg-transparent">
            <dt className="text-xs text-ink-soft dark:text-parchment-soft">Vectors</dt>
            <dd className="text-lg tnum">{count}</dd>
          </div>
          <div className="rounded-lg border border-ink/10 bg-cream p-3 dark:border-parchment/10 dark:bg-transparent">
            <dt className="text-xs text-ink-soft dark:text-parchment-soft">Dimensions</dt>
            <dd className="text-lg tnum">{dims}</dd>
          </div>
        </dl>
      )}
    </div>
  )
}
