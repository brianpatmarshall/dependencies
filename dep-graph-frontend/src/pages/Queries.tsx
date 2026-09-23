import { useEffect, useMemo, useState } from 'react'
import CypherBlock from '../components/CypherBlock'
import ResultsTable from '../components/ResultsTable'
import {
  CannedQuery,
  QueryGroup,
  QueryResult,
  errorMessage,
  listQueries,
  runCypher,
  runQuery,
} from '../api'

export default function Queries() {
  const [groups, setGroups] = useState<QueryGroup[]>([])
  const [selected, setSelected] = useState<CannedQuery | null>(null)
  const [params, setParams] = useState<Record<string, string>>({})
  const [result, setResult] = useState<QueryResult | null>(null)
  const [running, setRunning] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [cypher, setCypher] = useState('')

  useEffect(() => {
    listQueries()
      .then((loaded) => {
        setGroups(loaded)
        const first = loaded[0]?.queries[0]
        if (first) select(first)
      })
      .catch((e) => setError(errorMessage(e)))
    // Loading the catalogue once is the whole lifecycle; select() is stable enough here.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  const select = (query: CannedQuery) => {
    setSelected(query)
    setResult(null)
    setError(null)
    const defaults: Record<string, string> = {}
    query.params.forEach((param) => {
      defaults[param.name] = param.defaultValue
    })
    setParams(defaults)
  }

  const run = async (query: CannedQuery) => {
    setRunning(true)
    setError(null)
    try {
      setResult(await runQuery(query.id, params))
    } catch (e) {
      setError(errorMessage(e))
      setResult(null)
    } finally {
      setRunning(false)
    }
  }

  const runAdHoc = async () => {
    setRunning(true)
    setError(null)
    setSelected(null)
    try {
      setResult(await runCypher(cypher))
    } catch (e) {
      setError(errorMessage(e))
      setResult(null)
    } finally {
      setRunning(false)
    }
  }

  const total = useMemo(
    () => groups.reduce((sum, group) => sum + group.queries.length, 0),
    [groups],
  )

  return (
    <div className="flex gap-6">
      {/* ------------------------------------------------------ catalogue */}
      <aside className="w-80 shrink-0 space-y-5">
        <div className="text-xs uppercase tracking-wide text-slate-500">
          {total} queries
        </div>

        {groups.map((group) => (
          <div key={group.id}>
            <h2 className="mb-1.5 text-xs font-semibold uppercase tracking-wide text-sky-400">
              {group.title}
            </h2>
            <ul className="space-y-0.5">
              {group.queries.map((query) => (
                <li key={query.id}>
                  <button
                    onClick={() => select(query)}
                    className={`w-full rounded-md px-2.5 py-1.5 text-left text-sm ${
                      selected?.id === query.id
                        ? 'bg-slate-800 text-white'
                        : 'text-slate-400 hover:bg-slate-900 hover:text-slate-200'
                    }`}
                  >
                    {query.title}
                    {query.needsCodePlane && (
                      <span
                        title="Needs the code plane - run a scan with bytecode"
                        className="ml-1.5 text-[10px] text-slate-600"
                      >
                        ◆
                      </span>
                    )}
                  </button>
                </li>
              ))}
            </ul>
          </div>
        ))}
      </aside>

      {/* ------------------------------------------------------ detail */}
      <section className="min-w-0 flex-1 space-y-4">
        {selected && (
          <div className="space-y-3">
            <div>
              <h1 className="text-xl font-semibold text-white">{selected.title}</h1>
              <p className="mt-0.5 text-sm text-slate-400">{selected.question}</p>
            </div>

            {selected.params.length > 0 && (
              <div className="flex flex-wrap items-end gap-3">
                {selected.params.map((param) => (
                  <label key={param.name} className="block">
                    <span className="mb-1 block text-xs text-slate-500">{param.label}</span>
                    <input
                      className="input w-72 font-mono"
                      value={params[param.name] ?? ''}
                      onChange={(e) =>
                        setParams({ ...params, [param.name]: e.target.value })
                      }
                      onKeyDown={(e) => e.key === 'Enter' && run(selected)}
                    />
                  </label>
                ))}
              </div>
            )}

            <div className="flex items-center gap-3">
              <button
                className="btn"
                disabled={running}
                onClick={() => run(selected)}
              >
                {running ? 'Running…' : 'Run query'}
              </button>
              {selected.needsCodePlane && (
                <span className="text-xs text-slate-500">
                  Needs the code plane — scan without “build plane only”.
                </span>
              )}
            </div>

            <CypherBlock cypher={selected.cypher} />

            {selected.caveat && (
              <div className="rounded-md border border-amber-900/50 bg-amber-950/30 px-3 py-2 text-xs text-amber-200/90">
                {selected.caveat}
              </div>
            )}
          </div>
        )}

        {error && (
          <div className="rounded-md border border-rose-900/60 bg-rose-950/40 px-3 py-2 text-sm text-rose-200">
            {error}
          </div>
        )}

        {result && <ResultsTable result={result} />}

        {/* ------------------------------------------------ ad-hoc */}
        <details className="card">
          <summary className="cursor-pointer text-sm text-slate-400">
            Write your own Cypher
          </summary>
          <div className="mt-3 space-y-2">
            <textarea
              className="input h-32 font-mono"
              placeholder="MATCH (m:Module)-[:DECLARES_DEP]->(v) RETURN m.label, v.gav LIMIT 25"
              value={cypher}
              onChange={(e) => setCypher(e.target.value)}
            />
            <div className="flex items-center gap-3">
              <button className="btn" disabled={running || !cypher.trim()} onClick={runAdHoc}>
                Run
              </button>
              <span className="text-xs text-slate-500">
                Read-only. A write clause comes back as an error rather than changing the graph.
              </span>
            </div>
          </div>
        </details>
      </section>
    </div>
  )
}
