import { useEffect, useRef, useState } from 'react'
import { ScanSummary } from '../api'

/**
 * Scanning is the one operation long enough to need narration.
 *
 * <p>EventSource rather than axios because the endpoint is server-sent events, and the URL
 * is built by hand because EventSource takes a plain string and no options.
 */
export default function Scan() {
  const [path, setPath] = useState('/projects')
  const [buildPlaneOnly, setBuildPlaneOnly] = useState(false)
  const [lines, setLines] = useState<string[]>([])
  const [summary, setSummary] = useState<ScanSummary | null>(null)
  const [running, setRunning] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const logRef = useRef<HTMLDivElement>(null)
  const sourceRef = useRef<EventSource | null>(null)

  useEffect(() => {
    logRef.current?.scrollTo({ top: logRef.current.scrollHeight })
  }, [lines])

  useEffect(() => () => sourceRef.current?.close(), [])

  const start = () => {
    setRunning(true)
    setLines([])
    setSummary(null)
    setError(null)

    const url = `/api/scan/stream?path=${encodeURIComponent(path)}&buildPlaneOnly=${buildPlaneOnly}`
    const source = new EventSource(url)
    sourceRef.current = source

    source.addEventListener('progress', (event) => {
      setLines((previous) => [...previous, (event as MessageEvent).data])
    })
    source.addEventListener('done', (event) => {
      setSummary(JSON.parse((event as MessageEvent).data))
      setRunning(false)
      source.close()
    })
    source.addEventListener('failed', (event) => {
      setError((event as MessageEvent).data)
      setRunning(false)
      source.close()
    })
    source.onerror = () => {
      // Fires on a clean close too, so only report it if the scan never finished.
      setRunning((wasRunning) => {
        if (wasRunning) setError('Lost the connection to the backend.')
        return false
      })
      source.close()
    }
  }

  return (
    <div className="max-w-4xl space-y-5">
      <div>
        <h1 className="text-xl font-semibold text-white">Scan a project</h1>
        <p className="mt-1 text-sm text-slate-400">
          Reads every pom under this path, then the compiled classes, and replaces the
          graph with what it finds.
        </p>
      </div>

      <div className="card space-y-3">
        <label className="block">
          <span className="mb-1 block text-xs text-slate-500">
            Path — a container path, not a host path. The scan root is mounted at /projects.
          </span>
          <input
            className="input font-mono"
            value={path}
            onChange={(e) => setPath(e.target.value)}
            onKeyDown={(e) => e.key === 'Enter' && !running && start()}
          />
        </label>

        <label className="flex items-center gap-2 text-sm text-slate-400">
          <input
            type="checkbox"
            checked={buildPlaneOnly}
            onChange={(e) => setBuildPlaneOnly(e.target.checked)}
            className="accent-sky-600"
          />
          Build plane only — poms, no bytecode. Faster, and the only option for a project
          that has never been compiled.
        </label>

        <button className="btn" disabled={running} onClick={start}>
          {running ? 'Scanning…' : 'Start scan'}
        </button>
      </div>

      {lines.length > 0 && (
        <div
          ref={logRef}
          className="max-h-64 overflow-auto rounded-lg border border-slate-800 bg-slate-950 p-3 font-mono text-xs text-slate-400"
        >
          {lines.map((line, index) => (
            <div key={index}>{line}</div>
          ))}
        </div>
      )}

      {error && (
        <div className="rounded-md border border-rose-900/60 bg-rose-950/40 px-3 py-2 text-sm text-rose-200">
          {error}
        </div>
      )}

      {summary && <Summary summary={summary} />}
    </div>
  )
}

function Summary({ summary }: { summary: ScanSummary }) {
  const figures: [string, string | number][] = [
    ['Modules read', summary.modulesRead],
    ['Dependencies', summary.dependencyCount],
    ['Distinct artifacts', summary.artifactCount],
    ['Conflicts', summary.conflictCount],
    ['Proposed BOM entries', summary.proposedEntryCount],
    ['Types', summary.hasCodePlane ? summary.typeCount : '—'],
    ['Type relationships', summary.hasCodePlane ? summary.edgeCount : '—'],
    ['Took', `${(summary.totalMillis / 1000).toFixed(1)} s`],
  ]

  return (
    <div className="space-y-3">
      <div className="card">
        <div className="mb-2 font-medium text-white">{summary.projectName}</div>
        <div className="grid grid-cols-4 gap-4">
          {figures.map(([label, value]) => (
            <div key={label}>
              <div className="text-lg tabular-nums text-sky-300">
                {typeof value === 'number' ? value.toLocaleString() : value}
              </div>
              <div className="text-xs text-slate-500">{label}</div>
            </div>
          ))}
        </div>
      </div>

      {summary.hasCodePlane && summary.inlinedConstants > 0 && (
        <div className="rounded-md border border-amber-900/50 bg-amber-950/30 px-3 py-2 text-xs text-amber-200/90">
          {summary.inlinedConstants.toLocaleString()} inlined constants. javac copies these
          into their callers, so their uses leave no trace in the bytecode — which is why
          “declared but never used” is a question rather than a verdict.
        </div>
      )}

      {summary.unreadablePoms.length > 0 && (
        <Detail title={`${summary.unreadablePoms.length} pom(s) would not read`}
                items={summary.unreadablePoms} />
      )}
      {summary.unbuiltModules.length > 0 && (
        <Detail title={`${summary.unbuiltModules.length} module(s) had no compiled classes`}
                items={summary.unbuiltModules} />
      )}
    </div>
  )
}

function Detail({ title, items }: { title: string; items: string[] }) {
  return (
    <details className="card text-sm">
      <summary className="cursor-pointer text-slate-400">{title}</summary>
      <ul className="mt-2 space-y-0.5 font-mono text-xs text-slate-500">
        {items.map((item) => (
          <li key={item}>{item}</li>
        ))}
      </ul>
    </details>
  )
}
