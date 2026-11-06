// `sharedFile.*` — Mode-B shared-folder reads and the owner's outbound share.
import { SharedFiles } from '@tenshu/sdk/sharedFiles'
import type { GatewayModule } from './module'
import { asRecord } from './params'

export const sharedFileGateway: GatewayModule = {
  handlers: {
    // MB-3 — Mode-B file read. Membership-driven inbound: `sharedWithMe` lists
    // the realms you're a member of; `browse` walks a realm's tree;
    // `readFile` fetches a file's bytes lazily on open (chunks AEAD'd under the
    // realm keys, fetched via the realm runtime's OnDemand resolver).
    'sharedFile.sharedWithMe': async () => {
      return SharedFiles.sharedWithMe()
    },
    'sharedFile.browse': async (params) => {
      const b = readBrowse(params)
      return SharedFiles.browseSharedFolder(b.realmId, b.parentFileId)
    },
    'sharedFile.readFile': async (params) => {
      const r = readSharedFileParams(params)
      return SharedFiles.readSharedFile(r.realmId, r.fileId)
    },

    // MB-4 outbound — share a folder out (create + populate + self-mount a
    // realm) and list the folders this owner shares.
    'sharedFile.shareFolder': async (params) => {
      return SharedFiles.shareFolder(readFolderPath(params))
    },
    'sharedFile.sharedFolders': async () => {
      return SharedFiles.sharedFolders()
    },
  },
}

// MB-3 shared-file read validators.
function readBrowse(params: unknown): { realmId: string; parentFileId: string } {
  const r = asRecord(params)
  if (typeof r.realmId !== 'string') {
    throw new Error('sharedFile.browse requires { realmId }')
  }
  // '' is the realm root (SharedFile.ROOT_PARENT).
  return { realmId: r.realmId, parentFileId: typeof r.parentFileId === 'string' ? r.parentFileId : '' }
}

function readSharedFileParams(params: unknown): { realmId: string; fileId: string } {
  const r = asRecord(params)
  if (typeof r.realmId !== 'string' || typeof r.fileId !== 'string') {
    throw new Error('sharedFile.readFile requires { realmId, fileId }')
  }
  return { realmId: r.realmId, fileId: r.fileId }
}

function readFolderPath(params: unknown): string {
  const v = asRecord(params).folderPath
  if (typeof v !== 'string') throw new Error('sharedFile.shareFolder requires { folderPath }')
  return v
}
