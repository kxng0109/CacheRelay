import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { createMemoryRouter, RouterProvider } from 'react-router'
import { describe, expect, it, vi } from 'vitest'
import { RouteError } from './RouteError.js'

function Boom(): React.JSX.Element {
  throw new Error('render gone')
}

function StringBoom(): React.JSX.Element {
  // Non-error throws carry no message: the boundary stays honest anyway.
  throw 'string-thing' as unknown as Error
}

describe('RouteError', () => {
  it('renders a recovery screen instead of a blank page on render throws', async () => {
    const user = userEvent.setup()
    const reload = vi.fn()
    const original = window.location
    Object.defineProperty(window, 'location', { value: { reload }, configurable: true })
    const router = createMemoryRouter(
      [
        {
          path: '/',
          element: <Boom />,
          errorElement: <RouteError />,
        },
      ],
      { initialEntries: ['/'] },
    )
    try {
      render(<RouterProvider router={router} />)
      expect(await screen.findByRole('heading', { name: /something broke here/i })).toBeVisible()
      expect(screen.getByRole('alert')).toHaveTextContent('render gone')
      await user.click(screen.getByRole('button', { name: /reload screen/i }))
      expect(reload).toHaveBeenCalledTimes(1)
    } finally {
      Object.defineProperty(window, 'location', { value: original, configurable: true })
    }
  })

  it('stays honest for non-error throws without a message', async () => {
    const router = createMemoryRouter(
      [
        {
          path: '/',
          element: <StringBoom />,
          errorElement: <RouteError />,
        },
      ],
      { initialEntries: ['/'] },
    )
    render(<RouterProvider router={router} />)
    expect(await screen.findByRole('heading', { name: /something broke here/i })).toBeVisible()
    expect(screen.getByRole('alert')).toHaveTextContent(/something broke on this screen/i)
  })
})
