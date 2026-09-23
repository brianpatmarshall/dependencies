import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { errorMessage, getStats } from '../api'

/** The counters live on the :Project node, so this survives a backend restart. */
export default function Dashboard() {
  const [stats, setStats] = useState<Record<string, unknown> | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    getStats().then(setStats).catch((e) => setError(errorMessage(e)))
  }, [])

  if (error) {
    return (
      <div className="rounded-md border border-rose-900/60 bg-rose-950/40 px-3 py-2 text-sm text-rose-200">
        {error}
      </div>
    )
  }

  const number = (key: string) => Number(stats?.[key] ?? 0)
  const scanned = stats && stats['name']

  if (!scanned) {
    return (
      <div className="card max-w-2xl">
        <h1 className="text-lg font-semibold text-white">Nothing scanned yet</h1>
        <p className="mt-2 text-sm text-slate-400">
          The graph is empty. Run a scan and both planes land in Neo4J together — what the
          poms declare, what the bytecode uses, and the edge that joins them.
        </p>
        <Link to="/scan" className="btn mt-4 inline-block">
          Run a scan
        </Link>
      </div>
    )
  }

  const groups: { title: string; figures: [string, number, string?][] }[] = [
    {
      title: 'Build plane',
      figures: [
        ['Modules', number('modulesRead')],
        ['Dependencies', number('dependencyCount')],
        ['Distinct artifacts', number('artifactCount')],
        ['Version conflicts', number('conflictCount'), 'conflicts'],
      ],
    },
    {
      title: 'Versions',
      figures: [
        ['Safe (patch skew)', number('safeCount')],
        ['Needs review', number('reviewCount')],
        ['Blocked (major skew)', number('blockedCount')],
        ['Already managed', number('alreadyManagedCount')],
      ],
    },
    {
      title: 'Code plane',
      figures: [
        ['Types', number('typeCount')],
        ['From libraries', number('thirdPartyTypes')],
        ['Type relationships', number('edgeCount')],
        ['Inlined constants', number('inlinedConstants')],
      ],
    },
    {
      title: 'Graph',
      figures: [
        ['Nodes', number('nodes')],
        ['Relationships', number('relationships')],
        ['FROM bridge edges', number('bridgeEdges')],
        ['Module code edges', number('usesCodeEdges')],
      ],
    },
  ]

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-xl font-semibold text-white">{String(stats['name'])}</h1>
        <p className="mt-0.5 font-mono text-xs text-slate-500">{String(stats['path'])}</p>
      </div>

      {groups.map((group) => (
        <div key={group.title}>
          <h2 className="mb-2 text-xs font-semibold uppercase tracking-wide text-sky-400">
            {group.title}
          </h2>
          <div className="grid grid-cols-4 gap-4">
            {group.figures.map(([label, value]) => (
              <div key={label} className="card">
                <div className="text-2xl tabular-nums text-slate-100">
                  {value.toLocaleString()}
                </div>
                <div className="mt-0.5 text-xs text-slate-500">{label}</div>
              </div>
            ))}
          </div>
        </div>
      ))}

      {number('bridgeEdges') === 0 && number('typeCount') > 0 && (
        <div className="rounded-md border border-amber-900/50 bg-amber-950/30 px-3 py-2 text-xs text-amber-200/90">
          No FROM edges were written, so nothing joins the two planes and every
          bridge query will come back empty.
        </div>
      )}

      <Link to="/queries" className="btn inline-block">
        Ask the graph something
      </Link>
    </div>
  )
}
