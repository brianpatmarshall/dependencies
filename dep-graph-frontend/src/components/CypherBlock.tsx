import { useState } from 'react'

/**
 * The Cypher behind a canned query, collapsed by default.
 *
 * <p>Shown at all because the catalogue is meant to be a starting point: the fastest way
 * to a query nobody wrote yet is to copy the nearest one and change a line.
 */
export default function CypherBlock({ cypher }: { cypher: string }) {
  const [open, setOpen] = useState(false)
  const [copied, setCopied] = useState(false)

  const copy = () => {
    navigator.clipboard.writeText(cypher).then(() => {
      setCopied(true)
      setTimeout(() => setCopied(false), 1500)
    })
  }

  return (
    <div className="rounded-md border border-slate-800">
      <div className="flex items-center gap-2 px-3 py-1.5">
        <button
          onClick={() => setOpen(!open)}
          className="text-xs text-slate-400 hover:text-slate-200"
        >
          {open ? '▾' : '▸'} Cypher
        </button>
        {open && (
          <button onClick={copy} className="ml-auto text-xs text-slate-500 hover:text-slate-300">
            {copied ? 'copied' : 'copy'}
          </button>
        )}
      </div>
      {open && (
        <pre className="overflow-auto border-t border-slate-800 bg-slate-950 px-3 py-2 font-mono text-xs text-sky-300">
          {cypher.trim()}
        </pre>
      )}
    </div>
  )
}
