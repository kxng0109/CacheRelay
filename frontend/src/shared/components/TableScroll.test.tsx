import { screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { renderApp } from '../../test/utils.js'
import { TableScroll } from './TableScroll.js'

describe('TableScroll', () => {
  it('confines table overflow to the table region', () => {
    renderApp(
      <TableScroll>
        <table>
          <caption>Probe table</caption>
          <tbody>
            <tr>
              <td>cell</td>
            </tr>
          </tbody>
        </table>
      </TableScroll>,
    )
    const table = screen.getByRole('table', { name: /probe table/i })
    expect(table.parentElement).toHaveClass('overflow-x-auto')
  })
})
