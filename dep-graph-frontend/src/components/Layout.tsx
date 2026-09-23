import { ReactNode, useEffect, useState } from 'react'
import { NavLink } from 'react-router-dom'
import { getHealth, Health } from '../api'

const tabs = [
  { to: '/', label: 'Dashboard' },
  { to: '/scan', label: 'Scan' },
  { to: '/queries', label: 'Queries' },
]

export default function Layout({ children }: { children: ReactNode }) {
  const [health, setHealth] = useState<Health | null>(null)

  useEffect(() => {
    const check = () => getHealth().then(setHealth).catch(() => setHealth(null))
    check()
    const timer = setInterval(check, 15000)
    return () => clearInterval(timer)
  }, [])

  return (
    <div className="flex h-full flex-col">
      <header className="border-b border-slate-800 bg-slate-900/80 px-6 py-3">
        <div className="flex items-center gap-6">
          <div>
            <span className="font-mono text-lg font-semibold text-sky-400">dep-graph</span>
            <span className="ml-3 text-xs text-slate-500">
              what the poms declare, and what the bytecode actually uses
            </span>
          </div>

          <nav className="flex gap-1">
            {tabs.map((tab) => (
              <NavLink
                key={tab.to}
                to={tab.to}
                end={tab.to === '/'}
                className={({ isActive }) =>
                  `rounded-md px-3 py-1.5 text-sm ${
                    isActive
                      ? 'bg-slate-800 text-white'
                      : 'text-slate-400 hover:text-slate-200'
                  }`
                }
              >
                {tab.label}
              </NavLink>
            ))}
          </nav>

          <div className="ml-auto flex items-center gap-2 text-xs">
            <span
              className={`h-2 w-2 rounded-full ${
                health?.neo4j === 'up' ? 'bg-emerald-500' : 'bg-rose-500'
              }`}
            />
            <span className="text-slate-400">
              {health?.neo4j === 'up'
                ? health.scanned
                  ? 'graph ready'
                  : 'graph empty - run a scan'
                : 'Neo4J unreachable'}
            </span>
          </div>
        </div>
      </header>

      <main className="flex-1 overflow-auto p-6">{children}</main>
    </div>
  )
}
