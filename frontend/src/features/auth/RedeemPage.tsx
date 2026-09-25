import { zodResolver } from '@hookform/resolvers/zod'
import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { Link, useNavigate, useSearchParams } from 'react-router'
import * as z from 'zod/v4'
import { redeemInvite } from '../../shared/auth/session.js'

const schema = z.object({
  token: z.string().min(1, 'Invite token is required'),
  username: z.string().min(3, 'Username must be 3-255 characters').max(255),
  password: z.string().min(12, 'Password must be 12-255 characters').max(255),
  confirm: z.string().min(1, 'Confirm the password'),
})

type FormData = z.infer<typeof schema> & { confirm: string }

/**
 * Invite redemption: single-use token into a new account, logged in at once.
 *
 * @remarks Public route. The token prefills from `?token=` and leaves the
 * URL on submit (replace navigation). Unknown, consumed, expired, and
 * taken-username invites all resolve to one identical message so invite
 * validity cannot be probed. The first redemption on a fresh backend
 * becomes the initial admin; the screen states that documented behavior
 * without leaking whether this backend is fresh.
 *
 * @returns The redeem screen.
 */
export function RedeemPage(): React.JSX.Element {
  const navigate = useNavigate()
  const [params] = useSearchParams()
  const [error, setError] = useState<string | null>(null)

  const {
    register,
    handleSubmit,
    formState: { errors, isSubmitting },
  } = useForm<FormData & { confirm: string }>({
    resolver: zodResolver(
      schema.refine((d) => d.password === d.confirm, {
        message: 'Passwords do not match',
        path: ['confirm'],
      }),
    ),
    mode: 'onSubmit',
    defaultValues: { token: params.get('token') ?? '', username: '', password: '', confirm: '' },
  })

  const onSubmit = async (d: FormData & { confirm: string }): Promise<void> => {
    setError(null)
    try {
      await redeemInvite(d.token.trim(), d.username.trim(), d.password)
      await navigate('/', { replace: true })
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Redemption failed.')
    }
  }

  return (
    <div className="mx-auto w-full max-w-sm space-y-4">
      <div>
        <h1 className="font-display text-2xl font-medium tracking-tight">Redeem invite</h1>
        <p className="mt-1 text-sm text-ink-soft dark:text-parchment-soft">
          Single-use. The first account on a fresh backend becomes its admin.
        </p>
      </div>
      <form
        onSubmit={(e) => {
          void handleSubmit(onSubmit)(e)
        }}
        className="space-y-3 rounded-xl border border-ink/10 bg-cream p-4 dark:border-parchment/10 dark:bg-transparent"
      >
        <div>
          <label htmlFor="redeem-token" className="mb-1 block text-[13px] font-medium">
            Invite token
          </label>
          <input
            id="redeem-token"
            autoComplete="off"
            {...register('token')}
            aria-invalid={errors.token !== undefined}
            className="w-full rounded-md border border-ink/15 bg-transparent px-3 py-2 font-mono text-sm dark:border-parchment/15"
          />
          {errors.token === undefined ? null : (
            <p role="alert" className="mt-1 text-[13px] text-danger dark:text-danger-soft">
              {errors.token.message}
            </p>
          )}
        </div>
        <div>
          <label htmlFor="redeem-username" className="mb-1 block text-[13px] font-medium">
            Username
          </label>
          <input
            id="redeem-username"
            autoComplete="username"
            placeholder="operator"
            {...register('username')}
            aria-invalid={errors.username !== undefined}
            className="w-full rounded-md border border-ink/15 bg-transparent px-3 py-2 text-sm dark:border-parchment/15"
          />
          {errors.username === undefined ? null : (
            <p role="alert" className="mt-1 text-[13px] text-danger dark:text-danger-soft">
              {errors.username.message}
            </p>
          )}
        </div>
        <div>
          <label htmlFor="redeem-password" className="mb-1 block text-[13px] font-medium">
            Password (12+ characters)
          </label>
          <input
            id="redeem-password"
            type="password"
            autoComplete="new-password"
            {...register('password')}
            aria-invalid={errors.password !== undefined}
            className="w-full rounded-md border border-ink/15 bg-transparent px-3 py-2 text-sm dark:border-parchment/15"
          />
          {errors.password === undefined ? null : (
            <p role="alert" className="mt-1 text-[13px] text-danger dark:text-danger-soft">
              {errors.password.message}
            </p>
          )}
        </div>
        <div>
          <label htmlFor="redeem-confirm" className="mb-1 block text-[13px] font-medium">
            Confirm password
          </label>
          <input
            id="redeem-confirm"
            type="password"
            autoComplete="new-password"
            {...register('confirm')}
            aria-invalid={errors.confirm !== undefined}
            className="w-full rounded-md border border-ink/15 bg-transparent px-3 py-2 text-sm dark:border-parchment/15"
          />
          {errors.confirm === undefined ? null : (
            <p role="alert" className="mt-1 text-[13px] text-danger dark:text-danger-soft">
              {errors.confirm.message}
            </p>
          )}
        </div>
        {error === null ? null : (
          <p role="alert" className="text-sm text-danger dark:text-danger-soft">
            {error}
          </p>
        )}
        <button
          type="submit"
          disabled={isSubmitting}
          className="w-full rounded-md bg-ink px-4 py-2 text-sm font-medium text-paper disabled:cursor-not-allowed disabled:bg-ink-soft disabled:text-paper dark:bg-parchment dark:text-night dark:disabled:bg-parchment-soft dark:disabled:text-night"
        >
          {isSubmitting ? 'Redeeming…' : 'Create account'}
        </button>
      </form>
      <Link
        to="/login"
        className="block w-full rounded-md border border-ink/15 px-4 py-2 text-center text-sm dark:border-parchment/15"
      >
        Back to log in
      </Link>
    </div>
  )
}
