import { zodResolver } from '@hookform/resolvers/zod'
import { useState } from 'react'
import type { KeyboardEvent } from 'react'
import { useForm } from 'react-hook-form'
import { Link, useNavigate, useSearchParams } from 'react-router'
import * as z from 'zod/v4'
import { resolveNext } from '../../shared/auth/next.js'
import { login } from '../../shared/auth/session.js'

const schema = z.object({
  username: z.string().min(1, 'Username is required'),
  password: z.string().min(1, 'Password is required'),
})

type FormData = z.infer<typeof schema>

/**
 * Human login: username plus password into a memory-only session.
 *
 * @remarks Public route. The username is trimmed (identifiers are
 * canonicalized server-side); the password is never trimmed, stored,
 * logged, or rendered. Wrong credentials and lockouts surface distinct
 * honest messages. A validated `?next=` returns the session to the screen
 * that bounced it here; anything off-shape falls back to `/`.
 *
 * @returns The login screen.
 */
export function LoginPage(): React.JSX.Element {
  const navigate = useNavigate()
  const [params] = useSearchParams()
  const [error, setError] = useState<string | null>(null)
  const [showPassword, setShowPassword] = useState(false)
  const [capsLock, setCapsLock] = useState(false)

  const {
    register,
    handleSubmit,
    formState: { errors, isSubmitting },
  } = useForm<FormData>({ resolver: zodResolver(schema), mode: 'onSubmit' })

  /**
   * Tracks Caps Lock while typing the password. A wrong-password failure
   * with Caps Lock on is the most common avoidable login failure, so the
   * warning is inline rather than a post-submit surprise.
   *
   * @param e - Key event on the password field.
   */
  const trackCapsLock = (e: KeyboardEvent<HTMLInputElement>): void => {
    const active = typeof e.getModifierState === 'function' ? e.getModifierState('CapsLock') : false
    setCapsLock(active)
  }

  const onSubmit = async (d: FormData): Promise<void> => {
    setError(null)
    try {
      await login(d.username.trim(), d.password)
      await navigate(resolveNext(params.get('next')), { state: { fromLogin: true } })
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Login failed.')
    }
  }

  return (
    <div className="mx-auto w-full max-w-sm space-y-4">
      <div className="space-y-1">
        <p className="font-mono text-xs text-ink-soft dark:text-parchment-soft">
          <span aria-hidden="true" className="mr-1 text-ember">
            ❯
          </span>
          access
        </p>
        <h1 className="font-display text-3xl font-medium tracking-tight">Log in</h1>
        <p className="text-sm text-ink-soft dark:text-parchment-soft">
          Sign in to reach the console. Gateway keys stay on the Run screens.
        </p>
      </div>
      <form
        onSubmit={(e) => {
          void handleSubmit(onSubmit)(e)
        }}
        className="space-y-3 rounded-xl border border-ink/10 bg-cream p-4 dark:border-parchment/10 dark:bg-transparent"
      >
        <div>
          <label htmlFor="login-username" className="mb-1 block text-[13px] font-medium">
            Username
          </label>
          <input
            id="login-username"
            autoComplete="username"
            autoFocus
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
          <label htmlFor="login-password" className="mb-1 block text-[13px] font-medium">
            Password
          </label>
          <div className="flex gap-2">
            <input
              id="login-password"
              type={showPassword ? 'text' : 'password'}
              autoComplete="current-password"
              {...register('password')}
              onKeyDown={trackCapsLock}
              onKeyUp={trackCapsLock}
              aria-invalid={errors.password !== undefined}
              className="w-full rounded-md border border-ink/15 bg-transparent px-3 py-2 text-sm dark:border-parchment/15"
            />
            <button
              type="button"
              onClick={() => {
                setShowPassword((s) => !s)
              }}
              aria-label={showPassword ? 'Hide password' : 'Show password'}
              aria-pressed={showPassword}
              className="shrink-0 rounded-md border border-ink/15 px-3 text-[13px] dark:border-parchment/15"
            >
              {showPassword ? 'Hide' : 'Show'}
            </button>
          </div>
          {errors.password === undefined ? null : (
            <p role="alert" className="mt-1 text-[13px] text-danger dark:text-danger-soft">
              {errors.password.message}
            </p>
          )}
          {capsLock ? (
            <p role="status" className="mt-1 text-[13px] text-ink-soft dark:text-parchment-soft">
              Caps Lock is on. Passwords are case sensitive.
            </p>
          ) : null}
        </div>
        {error === null ? null : (
          <div
            role="alert"
            className="rounded-md border border-ink/10 bg-transparent p-3 dark:border-parchment/10"
          >
            <p className="text-sm text-danger dark:text-danger-soft">{error}</p>
            <p className="mt-1 text-[13px] text-ink-soft dark:text-parchment-soft">
              Check Caps Lock and try again. The form kept your entries.
            </p>
          </div>
        )}
        <button
          type="submit"
          disabled={isSubmitting}
          className="w-full rounded-md bg-ink px-4 py-2 text-sm font-medium text-paper disabled:cursor-not-allowed disabled:bg-ink-soft disabled:text-paper dark:bg-parchment dark:text-night dark:disabled:bg-parchment-soft dark:disabled:text-night"
        >
          {isSubmitting ? 'Logging in…' : 'Log in'}
        </button>
      </form>
      <p className="text-[13px] text-ink-soft dark:text-parchment-soft">First account?</p>
      <Link
        to="/redeem"
        className="block w-full rounded-md border border-ink/15 px-4 py-2 text-center text-sm dark:border-parchment/15"
      >
        Redeem an invite instead
      </Link>
      <p className="text-center font-mono text-xs text-ink-soft dark:text-parchment-soft">
        session in memory only
      </p>
    </div>
  )
}
