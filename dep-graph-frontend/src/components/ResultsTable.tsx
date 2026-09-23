import { QueryResult } from '../api'

/**
 * Cells can hold lists (a cycle, a set of relationship kinds) and the odd map. Rendering
 * those as [object Object] would quietly lose the most interesting column in several
 * queries, so lists are joined and anything else is shown as JSON.
 */
function cell(value: unknown) {
  if (value === null || value === undefined) {
    return <span className="text-slate-600">—</span>
  }
  if (Array.isArray(value)) {
    return (
      <span className="font-mono text-xs">
        {value.length === 0 ? <span className="text-slate-600">—</span> : value.join(', ')}
      </span>
    )
  }
  if (typeof value === 'boolean') {
    return (
      <span className={value ? 'text-emerald-400' : 'text-slate-500'}>
        {value ? 'yes' : 'no'}
      </span>
    )
  }
  if (typeof value === 'object') {
    return <span className="font-mono text-xs">{JSON.stringify(value)}</span>
  }
  if (typeof value === 'number') {
    return <span className="tabular-nums">{value.toLocaleString()}</span>
  }
  return <span className="font-mono text-xs">{String(value)}</span>
}

function toCsv(result: QueryResult): string {
  const escape = (value: unknown) => {
    const text = Array.isArray(value) ? value.join(' | ') : String(value ?? '')
    return /[",\n]/.test(text) ? `"${text.replace(/"/g, '""')}"` : text
  }
  const header = result.columns.join(',')
  const body = result.rows.map((row) => result.columns.map((c) => escape(row[c])).join(','))
  return [header, ...body].join('\n')
}

export default function ResultsTable({ result }: { result: QueryResult }) {
  const download = () => {
    const blob = new Blob([toCsv(result)], { type: 'text/csv' })
    const url = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = `${result.id ?? 'query'}.csv`
    link.click()
    URL.revokeObjectURL(url)
  }

  if (result.rows.length === 0) {
    return (
      <div className="card text-sm text-slate-400">
        No rows. That is an answer: nothing in the graph matches this question.
      </div>
    )
  }

  return (
    <div className="space-y-2">
      <div className="flex items-center gap-3 text-xs text-slate-400">
        <span>
          {result.rowCount.toLocaleString()} row{result.rowCount === 1 ? '' : 's'} in{' '}
          {result.millis} ms
        </span>
        {result.truncated && (
          <span className="rounded bg-amber-900/40 px-2 py-0.5 text-amber-300">
            truncated — there are more rows than the server will return
          </span>
        )}
        <button onClick={download} className="btn-ghost ml-auto !py-1 !text-xs">
          Download CSV
        </button>
      </div>

      <div className="overflow-auto rounded-lg border border-slate-800">
        <table className="min-w-full text-left text-sm">
          <thead className="sticky top-0 bg-slate-900 text-xs uppercase tracking-wide text-slate-400">
            <tr>
              {result.columns.map((column) => (
                <th key={column} className="whitespace-nowrap px-3 py-2 font-medium">
                  {column}
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {result.rows.map((row, index) => (
              <tr
                key={index}
                className="border-t border-slate-800/70 hover:bg-slate-900/60"
              >
                {result.columns.map((column) => (
                  <td key={column} className="px-3 py-1.5 align-top">
                    {cell(row[column])}
                  </td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  )
}
