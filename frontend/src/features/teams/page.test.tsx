import { fireEvent, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { HttpResponse, http } from 'msw'
import { describe, expect, it } from 'vitest'
import { server } from '../../test/setup.js'
import { renderApp } from '../../test/utils.js'
import { TeamsPage } from './page.js'

function mine(body: unknown = null) {
  return http.get(
    '*/v1/me/teams',
    () =>
      new HttpResponse(
        JSON.stringify(
          body ?? [
            { teamId: 't1', teamName: 'Eng', orgSlug: 'acme', role: 'MEMBER', status: 'ACTIVE' },
          ],
        ),
        { headers: { 'Content-Type': 'application/json' } },
      ),
  )
}

describe('TeamsPage', () => {
  it('lists my memberships with role and status', async () => {
    server.use(mine())
    renderApp(<TeamsPage />, { route: '/teams', nonAdminSession: true })
    await waitFor(() => {
      expect(screen.getByText('Eng')).toBeInTheDocument()
    })
    expect(screen.getByText('MEMBER')).toBeInTheDocument()
    expect(screen.getByText('ACTIVE')).toBeInTheDocument()
    // FE-22: narrow viewports scroll the table region, never the page.
    for (const table of screen.getAllByRole('table')) {
      expect(table.closest('.overflow-x-auto')).not.toBeNull()
    }
  })

  it('renders the empty trio for holding-team users, not an error', async () => {
    server.use(mine([]))
    renderApp(<TeamsPage />, { route: '/teams', nonAdminSession: true })
    await waitFor(() => {
      expect(screen.getByText(/no team yet/i)).toBeInTheDocument()
    })
    expect(screen.getByText(/contact your admin/i)).toBeInTheDocument()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  it('hints IdP disablement on membership failures', async () => {
    server.use(http.get('*/v1/me/teams', () => new HttpResponse('x', { status: 401 })))
    renderApp(<TeamsPage />, { route: '/teams', nonAdminSession: true })
    await waitFor(() => {
      expect(screen.getByRole('alert')).toHaveTextContent(/IdP account may be disabled/i)
    })
  })

  it('hides the org inventory from non-admins without a hint', async () => {
    server.use(mine([]))
    renderApp(<TeamsPage />, { route: '/teams', nonAdminSession: true })
    await screen.findByText(/no team yet/i)
    expect(screen.queryByLabelText(/org slug/i)).not.toBeInTheDocument()
  })

  it('lists org teams for admins and shows member counts only', async () => {
    const user = userEvent.setup()
    server.use(
      mine([]),
      http.get('*/v1/admin/teams', () =>
        HttpResponse.json([
          { teamId: 't1', orgSlug: 'acme', name: 'Eng', idpGroupId: 'group-1', activeMembers: 12 },
        ]),
      ),
    )
    renderApp(<TeamsPage />, { route: '/teams', adminSession: true })
    await screen.findByText(/no team yet/i)
    fireEvent.change(screen.getByLabelText(/org slug/i), { target: { value: 'acme' } })
    await user.click(screen.getByRole('button', { name: /^list teams$/i }))
    await waitFor(() => {
      expect(screen.getByText('group-1')).toBeInTheDocument()
    })
    expect(screen.getByText('12')).toBeInTheDocument()
    expect(screen.queryByText(/requests|tokens/i)).not.toBeInTheDocument()
  })

  it('names unknown orgs without inventing teams', async () => {
    const user = userEvent.setup()
    server.use(
      mine([]),
      http.get('*/v1/admin/teams', () => new HttpResponse('x', { status: 404 })),
    )
    renderApp(<TeamsPage />, { route: '/teams', adminSession: true })
    await screen.findByText(/no team yet/i)
    fireEvent.change(screen.getByLabelText(/org slug/i), { target: { value: 'nope' } })
    await user.click(screen.getByRole('button', { name: /^list teams$/i }))
    await waitFor(() => {
      expect(screen.getByText(/unknown org/i)).toBeInTheDocument()
    })
  })

  it('requires an org slug before listing', async () => {
    const user = userEvent.setup()
    server.use(mine([]))
    renderApp(<TeamsPage />, { route: '/teams', adminSession: true })
    await screen.findByText(/no team yet/i)
    await user.click(screen.getByRole('button', { name: /^list teams$/i }))
    expect(await screen.findByRole('alert')).toHaveTextContent(/org slug/i)
  })

  it('surfaces inventory failures without inventing teams', async () => {
    const user = userEvent.setup()
    server.use(
      mine([]),
      http.get('*/v1/admin/teams', () => new HttpResponse('x', { status: 500 })),
    )
    renderApp(<TeamsPage />, { route: '/teams', adminSession: true })
    await screen.findByText(/no team yet/i)
    fireEvent.change(screen.getByLabelText(/org slug/i), { target: { value: 'acme' } })
    await user.click(screen.getByRole('button', { name: /^list teams$/i }))
    await waitFor(() => {
      expect(screen.getByRole('alert')).toHaveTextContent(/HTTP 500/)
    })
  })

  it('surfaces unreachable inventory endpoints as failures', async () => {
    const user = userEvent.setup()
    server.use(
      mine([]),
      http.get('*/v1/admin/teams', () => HttpResponse.error()),
    )
    renderApp(<TeamsPage />, { route: '/teams', adminSession: true })
    await screen.findByText(/no team yet/i)
    fireEvent.change(screen.getByLabelText(/org slug/i), { target: { value: 'acme' } })
    await user.click(screen.getByRole('button', { name: /^list teams$/i }))
    await waitFor(() => {
      expect(screen.getByRole('alert')).toHaveTextContent(/unreachable/i)
    })
  })

  it('names empty orgs without inventing teams', async () => {
    const user = userEvent.setup()
    server.use(
      mine([]),
      http.get('*/v1/admin/teams', () => HttpResponse.json([])),
    )
    renderApp(<TeamsPage />, { route: '/teams', adminSession: true })
    await screen.findByText(/no team yet/i)
    fireEvent.change(screen.getByLabelText(/org slug/i), { target: { value: 'acme' } })
    await user.click(screen.getByRole('button', { name: /^list teams$/i }))
    await waitFor(() => {
      expect(screen.getByText(/has no teams yet/i)).toBeInTheDocument()
    })
  })
})
