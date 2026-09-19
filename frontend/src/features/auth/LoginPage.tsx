import { zodResolver } from '@hookform/resolvers/zod'
import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { useNavigate } from 'react-router'
import * as z from 'zod/v4'
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
 * honest messages.
 *
 * @returns The login screen.
 */
export function LoginPage(): React.JSX.Element {
  const navigate = useNavigate()
  const [error, setError] = useState<string | null>(null)

  const {
    register,
    handleSubmit,
    formState: { errors, isSubmitting },
  } = useForm<FormData>({ resolver: zodResolver(schema), mode: 'onSubmit' })

  const onSubmit = async (d: FormData): Promise<void> => {
    setError(null)
    try {
      await login(d.username.trim(), d.password)
      await navigate('/')
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Login failed.')
    }
  }

  return (
    <div className="mx-auto w-full max-w-sm space-y-4">
      <div>
        <h1 className="font-display text-2xl font-medium tracking-tight">Log in</h1>
        <p className="mt-1 text-sm text-ink-soft dark:text-parchment-soft">
          Human accounts only. Gateway keys go on the Playground screen.
        </p>
      </div>
      <form
        onSubmit={(e) => {
          void handleSubmit(onSubmit)(e)
        }}
        className="space-y-3 rounded-xl border border-ink/10 bg-cream p-4 dark:border-parchment/10 dark:bg-transparent"
      >
        <div>
          <label htmlFor="login-username" className="mb-1 block text-xs font-medium">
            Username
          </label>
          <input
            id="login-username"
            autoComplete="username"
            {...register('username')}
            aria-invalid={errors.username !== undefined}
            className="w-full rounded-md border border-ink/15 bg-transparent px-3 py-2 text-sm dark:border-parchment/15"
          />
          {errors.username === undefined ? null : (
            <p role="alert" className="mt-1 text-xs text-danger dark:text-danger-soft">
              {errors.username.message}
            </p>
          )}
        </div>
        <div>
          <label htmlFor="login-password" className="mb-1 block text-xs font-medium">
            Password
          </label>
          <input
            id="login-password"
            type="password"
            autoComplete="current-password"
            {...register('password')}
            aria-invalid={errors.password !== undefined}
            className="w-full rounded-md border border-ink/15 bg-transparent px-3 py-2 text-sm dark:border-parchment/15"
          />
          {errors.password === undefined ? null : (
            <p role="alert" className="mt-1 text-xs text-danger dark:text-danger-soft">
              {errors.password.message}
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
          className="w-full rounded-md bg-ink px-4 py-2 text-sm font-medium text-paper disabled:opacity-50 dark:bg-parchment dark:text-night"
        >
          {isSubmitting ? 'Logging in…' : 'Log in'}
        </button>
      </form>
      <p className="text-xs text-ink-soft dark:text-parchment-soft">
        First account? Redeem an invite instead.
      </p>
    </div>
  )
}
