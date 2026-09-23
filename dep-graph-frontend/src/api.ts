import axios from 'axios'

// Same-origin by design: vite proxies in dev, nginx proxies in Docker. Nothing here ever
// needs a host, and there is therefore no CORS configuration to keep in sync.
export const api = axios.create({ baseURL: '/api', timeout: 10 * 60 * 1000 })

export interface Param {
  name: string
  label: string
  defaultValue: string
}

export interface CannedQuery {
  id: string
  category: string
  title: string
  question: string
  cypher: string
  params: Param[]
  caveat: string | null
  needsCodePlane: boolean
}

export interface QueryGroup {
  id: string
  title: string
  queries: CannedQuery[]
}

export interface QueryResult {
  id: string | null
  title: string
  question: string | null
  cypher: string
  columns: string[]
  rows: Record<string, unknown>[]
  rowCount: number
  truncated: boolean
  millis: number
  caveat: string | null
}

export interface ScanSummary {
  projectPath: string
  projectName: string
  scannedAt: string
  modulesRead: number
  modulesFailed: number
  dependencyCount: number
  artifactCount: number
  conflictCount: number
  blockedCount: number
  reviewCount: number
  alreadyManagedCount: number
  heldCount: number
  proposedEntryCount: number
  hasCodePlane: boolean
  typeCount: number
  thirdPartyTypes: number
  edgeCount: number
  inlinedConstants: number
  unbuiltModules: string[]
  unreadablePoms: string[]
  scanMillis: number
  totalMillis: number
}

export interface Health {
  neo4j: string
  scanned: boolean
  error?: string
}

/** The API returns { message } on 4xx; showing that beats showing "Request failed". */
export function errorMessage(error: unknown): string {
  if (axios.isAxiosError(error)) {
    return error.response?.data?.message ?? error.message
  }
  return error instanceof Error ? error.message : String(error)
}

export const listQueries = () =>
  api.get<QueryGroup[]>('/query/canned').then((r) => r.data)

export const runQuery = (id: string, params: Record<string, string>) =>
  api.post<QueryResult>(`/query/canned/${id}`, params).then((r) => r.data)

export const runCypher = (cypher: string) =>
  api.post<QueryResult>('/query/execute', { cypher }).then((r) => r.data)

export const getStats = () =>
  api.get<Record<string, unknown>>('/stats').then((r) => r.data)

export const getHealth = () => api.get<Health>('/stats/health').then((r) => r.data)
