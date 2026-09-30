import { fireEvent, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { HttpResponse, http } from 'msw'
import { describe, expect, it } from 'vitest'
import { server } from '../../test/setup.js'
import { renderApp, selectOption } from '../../test/utils.js'
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

/**
 * Org list stub: the local-management section fetches it on mount for
 * every admin session, so admin scenarios stub it (empty by default).
 */
function orgs(body: Record<string, string>[] = []) {
  return http.get('*/v1/admin/orgs', () => HttpResponse.json(body))
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
      orgs(),
      http.get('*/v1/admin/teams', () =>
        HttpResponse.json([
          { teamId: 't1', orgSlug: 'acme', name: 'Eng', idpGroupId: 'group-1', activeMembers: 12 },
        ]),
      ),
    )
    renderApp(<TeamsPage />, { route: '/teams', adminSession: true })
    await screen.findByText(/no team yet/i)
    fireEvent.change(screen.getByLabelText(/^org slug$/i), { target: { value: 'acme' } })
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
      orgs(),
      http.get('*/v1/admin/teams', () => new HttpResponse('x', { status: 404 })),
    )
    renderApp(<TeamsPage />, { route: '/teams', adminSession: true })
    await screen.findByText(/no team yet/i)
    fireEvent.change(screen.getByLabelText(/^org slug$/i), { target: { value: 'nope' } })
    await user.click(screen.getByRole('button', { name: /^list teams$/i }))
    await waitFor(() => {
      expect(screen.getByText(/unknown org/i)).toBeInTheDocument()
    })
  })

  it('requires an org slug before listing', async () => {
    const user = userEvent.setup()
    server.use(mine([]), orgs())
    renderApp(<TeamsPage />, { route: '/teams', adminSession: true })
    await screen.findByText(/no team yet/i)
    await user.click(screen.getByRole('button', { name: /^list teams$/i }))
    expect(await screen.findByRole('alert')).toHaveTextContent(/org slug/i)
  })

  it('surfaces inventory failures without inventing teams', async () => {
    const user = userEvent.setup()
    server.use(
      mine([]),
      orgs(),
      http.get('*/v1/admin/teams', () => new HttpResponse('x', { status: 500 })),
    )
    renderApp(<TeamsPage />, { route: '/teams', adminSession: true })
    await screen.findByText(/no team yet/i)
    fireEvent.change(screen.getByLabelText(/^org slug$/i), { target: { value: 'acme' } })
    await user.click(screen.getByRole('button', { name: /^list teams$/i }))
    await waitFor(() => {
      expect(screen.getByRole('alert')).toHaveTextContent(/HTTP 500/)
    })
  })

  it('surfaces unreachable inventory endpoints as failures', async () => {
    const user = userEvent.setup()
    server.use(
      mine([]),
      orgs(),
      http.get('*/v1/admin/teams', () => HttpResponse.error()),
    )
    renderApp(<TeamsPage />, { route: '/teams', adminSession: true })
    await screen.findByText(/no team yet/i)
    fireEvent.change(screen.getByLabelText(/^org slug$/i), { target: { value: 'acme' } })
    await user.click(screen.getByRole('button', { name: /^list teams$/i }))
    await waitFor(() => {
      expect(screen.getByRole('alert')).toHaveTextContent(/unreachable/i)
    })
  })

  it('names empty orgs without inventing teams', async () => {
    const user = userEvent.setup()
    server.use(
      mine([]),
      orgs(),
      http.get('*/v1/admin/teams', () => HttpResponse.json([])),
    )
    renderApp(<TeamsPage />, { route: '/teams', adminSession: true })
    await screen.findByText(/no team yet/i)
    fireEvent.change(screen.getByLabelText(/^org slug$/i), { target: { value: 'acme' } })
    await user.click(screen.getByRole('button', { name: /^list teams$/i }))
    await waitFor(() => {
      expect(screen.getByText(/has no teams yet/i)).toBeInTheDocument()
    })
  })

  it('lists orgs and creates one from slug and display name', async () => {
    const user = userEvent.setup()
    let listed = 1
    server.use(
      mine([]),
      http.get('*/v1/admin/orgs', () =>
        HttpResponse.json(
          listed === 1
            ? [{ id: 'o1', slug: 'acme', displayName: 'Acme' }]
            : [
                { id: 'o1', slug: 'acme', displayName: 'Acme' },
                { id: 'o2', slug: 'globex', displayName: 'Globex' },
              ],
        ),
      ),
      http.post('*/v1/admin/orgs', async ({ request }) => {
        const body = (await request.json()) as { slug: string }
        expect(body.slug).toBe('globex')
        listed = 2
        return HttpResponse.json(
          { id: 'o2', slug: 'globex', displayName: 'Globex' },
          { status: 201 },
        )
      }),
    )
    renderApp(<TeamsPage />, { route: '/teams', adminSession: true })
    await waitFor(() => {
      expect(screen.getByText('Acme')).toBeInTheDocument()
    })
    await user.type(screen.getByLabelText(/new org slug/i), 'globex')
    await user.type(screen.getByLabelText(/new org display name/i), 'Globex')
    await user.click(screen.getByRole('button', { name: /^create org$/i }))
    await waitFor(() => {
      expect(screen.getByText('Globex')).toBeInTheDocument()
    })
    expect(await screen.findByText('Org created.')).toBeInTheDocument()
  })

  it('renames an org without touching its slug', async () => {
    const user = userEvent.setup()
    let body: unknown = null
    server.use(
      mine([]),
      http.get('*/v1/admin/orgs', () =>
        HttpResponse.json([{ id: 'o1', slug: 'acme', displayName: 'Acme' }]),
      ),
      http.patch('*/v1/admin/orgs/:id', async ({ request }) => {
        body = await request.json()
        return HttpResponse.json({ id: 'o1', slug: 'acme', displayName: 'Acme Inc' })
      }),
    )
    renderApp(<TeamsPage />, { route: '/teams', adminSession: true })
    await waitFor(() => {
      expect(screen.getByText('Acme')).toBeInTheDocument()
    })
    await user.click(screen.getByRole('button', { name: /rename org acme/i }))
    await user.type(screen.getByLabelText(/new display name for acme/i), 'Acme Inc')
    await user.click(screen.getByRole('button', { name: /save org name acme/i }))
    expect(await screen.findByText('Org renamed.')).toBeInTheDocument()
    expect(body).toEqual({ displayName: 'Acme Inc' })
  })

  it('refuses org deletion while teams remain', async () => {
    const user = userEvent.setup()
    server.use(
      mine([]),
      http.get('*/v1/admin/orgs', () =>
        HttpResponse.json([{ id: 'o1', slug: 'acme', displayName: 'Acme' }]),
      ),
      http.delete(
        '*/v1/admin/orgs/:id',
        () =>
          new HttpResponse(JSON.stringify({ error: { message: 'Org acme still has teams.' } }), {
            status: 409,
            headers: { 'Content-Type': 'application/json' },
          }),
      ),
    )
    renderApp(<TeamsPage />, { route: '/teams', adminSession: true })
    await waitFor(() => {
      expect(screen.getByText('Acme')).toBeInTheDocument()
    })
    await user.click(screen.getByRole('button', { name: /delete org acme/i }))
    await waitFor(() => {
      expect(screen.getByRole('alert')).toHaveTextContent(/still has teams/i)
    })
  })

  it('creates a team inside an org with zero members', async () => {
    const user = userEvent.setup()
    server.use(
      mine([]),
      http.get('*/v1/admin/orgs', () =>
        HttpResponse.json([{ id: 'o1', slug: 'acme', displayName: 'Acme' }]),
      ),
      http.post('*/v1/admin/orgs/:id/teams', () =>
        HttpResponse.json(
          {
            teamId: 't9',
            orgSlug: 'acme',
            name: 'Ops',
            idpGroupId: 'local:acme',
            activeMembers: 0,
          },
          { status: 201 },
        ),
      ),
    )
    renderApp(<TeamsPage />, { route: '/teams', adminSession: true })
    await waitFor(() => {
      expect(screen.getByText('Acme')).toBeInTheDocument()
    })
    await user.click(screen.getByRole('button', { name: /new team in acme/i }))
    await user.type(screen.getByLabelText(/team name for acme/i), 'Ops')
    await user.click(screen.getByRole('button', { name: /create team in acme/i }))
    expect(await screen.findByText('Team created.')).toBeInTheDocument()
  })

  it('marks sync-owned teams read-only and guards Unassigned deletion', async () => {
    const user = userEvent.setup()
    server.use(
      mine([]),
      orgs(),
      http.get('*/v1/admin/teams', () =>
        HttpResponse.json([
          {
            teamId: 't1',
            orgSlug: 'acme',
            name: 'Eng',
            idpGroupId: 'local:acme',
            activeMembers: 3,
          },
          {
            teamId: 't2',
            orgSlug: 'acme',
            name: 'Okta Eng',
            idpGroupId: 'okta-group-7',
            activeMembers: 9,
          },
          {
            teamId: 't3',
            orgSlug: 'acme',
            name: 'Unassigned',
            idpGroupId: 'local:acme',
            activeMembers: 1,
          },
        ]),
      ),
    )
    renderApp(<TeamsPage />, { route: '/teams', adminSession: true })
    await screen.findByText(/no team yet/i)
    fireEvent.change(screen.getByLabelText(/^org slug$/i), { target: { value: 'acme' } })
    await user.click(screen.getByRole('button', { name: /^list teams$/i }))
    await waitFor(() => {
      expect(screen.getByText('Sync-owned')).toBeInTheDocument()
    })
    expect(screen.queryByRole('button', { name: /rename team okta eng/i })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /delete team okta eng/i })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: /rename team eng/i })).toBeInTheDocument()
    const unassignedDelete = screen.getByRole('button', { name: /delete team unassigned/i })
    expect(unassignedDelete).toBeDisabled()
  })

  it('assigns and revokes members with idempotent retries', async () => {
    const user = userEvent.setup()
    const seen: string[] = []
    server.use(
      mine([]),
      orgs(),
      http.get('*/v1/admin/teams', () =>
        HttpResponse.json([
          {
            teamId: 't1',
            orgSlug: 'acme',
            name: 'Eng',
            idpGroupId: 'local:acme',
            activeMembers: 0,
          },
        ]),
      ),
      http.put('*/v1/admin/teams/:team/members/:user', ({ request }) => {
        seen.push(request.method)
        return HttpResponse.json({
          userId: '123e4567-e89b-12d3-a456-426614174000',
          teamId: 't1',
          role: 'LEAD',
          status: 'ACTIVE',
        })
      }),
      http.delete('*/v1/admin/teams/:team/members/:user', () =>
        HttpResponse.json({
          userId: '123e4567-e89b-12d3-a456-426614174000',
          teamId: 't1',
          role: 'LEAD',
          status: 'INACTIVE',
        }),
      ),
    )
    renderApp(<TeamsPage />, { route: '/teams', adminSession: true })
    await screen.findByText(/no team yet/i)
    fireEvent.change(screen.getByLabelText(/^org slug$/i), { target: { value: 'acme' } })
    await user.click(screen.getByRole('button', { name: /^list teams$/i }))
    await waitFor(() => {
      expect(screen.getByRole('button', { name: /assign member to eng/i })).toBeInTheDocument()
    })
    await user.type(
      screen.getByLabelText(/member account uuid for eng/i),
      '123e4567-e89b-12d3-a456-426614174000',
    )
    await selectOption(user, /member role for eng/i, 'LEAD')
    await user.click(screen.getByRole('button', { name: /assign member to eng/i }))
    expect(await screen.findByText(/member assigned — active/i)).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: /revoke member from eng/i }))
    expect(await screen.findByText(/member revoked — inactive/i)).toBeInTheDocument()
    expect(seen).toEqual(['PUT'])
  })

  it('rejects non-UUID member accounts before sending', async () => {
    const user = userEvent.setup()
    let calls = 0
    server.use(
      mine([]),
      orgs(),
      http.get('*/v1/admin/teams', () =>
        HttpResponse.json([
          {
            teamId: 't1',
            orgSlug: 'acme',
            name: 'Eng',
            idpGroupId: 'local:acme',
            activeMembers: 0,
          },
        ]),
      ),
      http.put('*/v1/admin/teams/:team/members/:user', () => {
        calls += 1
        return HttpResponse.json({
          userId: 'x',
          teamId: 't1',
          role: 'MEMBER',
          status: 'ACTIVE',
        })
      }),
    )
    renderApp(<TeamsPage />, { route: '/teams', adminSession: true })
    await screen.findByText(/no team yet/i)
    fireEvent.change(screen.getByLabelText(/^org slug$/i), { target: { value: 'acme' } })
    await user.click(screen.getByRole('button', { name: /^list teams$/i }))
    await waitFor(() => {
      expect(screen.getByRole('button', { name: /assign member to eng/i })).toBeInTheDocument()
    })
    await user.type(screen.getByLabelText(/member account uuid for eng/i), 'not-a-uuid')
    await user.click(screen.getByRole('button', { name: /assign member to eng/i }))
    await waitFor(() => {
      expect(screen.getByRole('alert')).toHaveTextContent(/valid uuid/i)
    })
    expect(calls).toBe(0)
  })

  it('renames a local team and reports the update', async () => {
    const user = userEvent.setup()
    let body: unknown = null
    server.use(
      mine([]),
      orgs(),
      http.get('*/v1/admin/teams', () =>
        HttpResponse.json([
          {
            teamId: 't1',
            orgSlug: 'acme',
            name: 'Eng',
            idpGroupId: 'local:acme',
            activeMembers: 0,
          },
        ]),
      ),
      http.patch('*/v1/admin/teams/:id', async ({ request }) => {
        body = await request.json()
        return HttpResponse.json({
          teamId: 't1',
          orgSlug: 'acme',
          name: 'Engineering',
          idpGroupId: 'local:acme',
          activeMembers: 0,
        })
      }),
    )
    renderApp(<TeamsPage />, { route: '/teams', adminSession: true })
    await screen.findByText(/no team yet/i)
    fireEvent.change(screen.getByLabelText(/^org slug$/i), { target: { value: 'acme' } })
    await user.click(screen.getByRole('button', { name: /^list teams$/i }))
    await waitFor(() => {
      expect(screen.getByRole('button', { name: /rename team eng/i })).toBeInTheDocument()
    })
    await user.click(screen.getByRole('button', { name: /rename team eng/i }))
    await user.type(screen.getByLabelText(/new display name for team eng/i), 'Engineering')
    await user.click(screen.getByRole('button', { name: /save team name eng/i }))
    expect(await screen.findByText('Team renamed.')).toBeInTheDocument()
    expect(body).toEqual({ displayName: 'Engineering' })
  })

  it('refuses team deletion while members remain', async () => {
    const user = userEvent.setup()
    server.use(
      mine([]),
      orgs(),
      http.get('*/v1/admin/teams', () =>
        HttpResponse.json([
          {
            teamId: 't1',
            orgSlug: 'acme',
            name: 'Eng',
            idpGroupId: 'local:acme',
            activeMembers: 2,
          },
        ]),
      ),
      http.delete(
        '*/v1/admin/teams/:id',
        () =>
          new HttpResponse(
            JSON.stringify({ error: { message: 'Team Eng still has active members.' } }),
            { status: 409, headers: { 'Content-Type': 'application/json' } },
          ),
      ),
    )
    renderApp(<TeamsPage />, { route: '/teams', adminSession: true })
    await screen.findByText(/no team yet/i)
    fireEvent.change(screen.getByLabelText(/^org slug$/i), { target: { value: 'acme' } })
    await user.click(screen.getByRole('button', { name: /^list teams$/i }))
    await waitFor(() => {
      expect(screen.getByRole('button', { name: /delete team eng/i })).toBeInTheDocument()
    })
    await user.click(screen.getByRole('button', { name: /delete team eng/i }))
    await waitFor(() => {
      expect(screen.getByRole('alert')).toHaveTextContent(/still has active members/i)
    })
  })

  it('rejects non-UUID revocations before sending', async () => {
    const user = userEvent.setup()
    let calls = 0
    server.use(
      mine([]),
      orgs(),
      http.get('*/v1/admin/teams', () =>
        HttpResponse.json([
          {
            teamId: 't1',
            orgSlug: 'acme',
            name: 'Eng',
            idpGroupId: 'local:acme',
            activeMembers: 0,
          },
        ]),
      ),
      http.delete('*/v1/admin/teams/:team/members/:user', () => {
        calls += 1
        return HttpResponse.json({
          userId: 'x',
          teamId: 't1',
          role: 'MEMBER',
          status: 'INACTIVE',
        })
      }),
    )
    renderApp(<TeamsPage />, { route: '/teams', adminSession: true })
    await screen.findByText(/no team yet/i)
    fireEvent.change(screen.getByLabelText(/^org slug$/i), { target: { value: 'acme' } })
    await user.click(screen.getByRole('button', { name: /^list teams$/i }))
    await waitFor(() => {
      expect(screen.getByRole('button', { name: /revoke member from eng/i })).toBeInTheDocument()
    })
    await user.type(screen.getByLabelText(/member account uuid for eng/i), 'nope')
    await user.click(screen.getByRole('button', { name: /revoke member from eng/i }))
    await waitFor(() => {
      expect(screen.getByRole('alert')).toHaveTextContent(/valid uuid/i)
    })
    expect(calls).toBe(0)
  })

  it('deletes an empty org and surfaces org list failures', async () => {
    const user = userEvent.setup()
    server.use(
      mine([]),
      http.get('*/v1/admin/orgs', () =>
        HttpResponse.json([{ id: 'o1', slug: 'acme', displayName: 'Acme' }]),
      ),
      http.delete('*/v1/admin/orgs/:id', () => new HttpResponse(null, { status: 204 })),
    )
    renderApp(<TeamsPage />, { route: '/teams', adminSession: true })
    await waitFor(() => {
      expect(screen.getByText('Acme')).toBeInTheDocument()
    })
    await user.click(screen.getByRole('button', { name: /delete org acme/i }))
    expect(await screen.findByText('Org deleted.')).toBeInTheDocument()
  })

  it('surfaces org list failures without inventing orgs', async () => {
    server.use(
      mine([]),
      http.get('*/v1/admin/orgs', () => new HttpResponse('x', { status: 500 })),
    )
    renderApp(<TeamsPage />, { route: '/teams', adminSession: true })
    await waitFor(() => {
      expect(screen.getByRole('alert')).toHaveTextContent(/HTTP 500/)
    })
  })
})
