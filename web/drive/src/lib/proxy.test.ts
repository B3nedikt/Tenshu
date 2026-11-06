import { afterEach, describe, expect, it, vi } from 'vitest'

// Mock the bridge so we can assert the drive proxy dispatches the right wire
// method + params without mounting a real apex iframe. `mountApexProxy`
// resolves to a fake client whose `call` records its arguments.
const call = vi.fn(async () => true as unknown)
vi.mock('@tenshu/sdk/bridge', () => ({
  mountApexProxy: vi.fn(async () => ({ call, dispose: vi.fn() })),
}))

// Imported after the mock is registered.
const {
  listFiles,
  recentFiles,
  uploadFile,
  createFolder,
  rename,
  move,
  deleteEntry,
  details,
  activity,
  createShareLink,
  listShares,
  revokeShare,
  storageStatus,
  createContactInvite,
  revokeContactInvite,
  redeemContactInvite,
  awaitInviteAck,
  completeHandshake,
  listContactInvites,
  connectedContacts,
  pendingRedemptions,
  grantMembership,
  acceptGrant,
  realmMembers,
  removeMember,
  pollPendingGrants,
  declineGrant,
  sharedWithMe,
  browseSharedFolder,
  readSharedFile,
  shareFolder,
  sharedFolders,
  openContentStream,
  readContentRange,
  closeContentStream,
  ProxyMethod,
} = await import('./proxy')

afterEach(() => {
  call.mockClear()
})

describe('drive proxy surface', () => {
  it('listFiles dispatches fs.list with the path', async () => {
    await listFiles('/Photos')
    expect(call).toHaveBeenCalledWith(ProxyMethod.ListFiles, { path: '/Photos' })
  })

  it('recentFiles dispatches fs.recentFiles with the limit', async () => {
    await recentFiles(5)
    expect(call).toHaveBeenCalledWith(ProxyMethod.RecentFiles, { limit: 5 })
  })

  it('recentFiles defaults the limit to 20', async () => {
    await recentFiles()
    expect(call).toHaveBeenCalledWith(ProxyMethod.RecentFiles, { limit: 20 })
  })

  it('uploadFile dispatches fs.uploadFile with parentPath + fileName + data', async () => {
    const data = new Int8Array([1, 2, 3])
    await uploadFile('/Photos', 'a.txt', data)
    expect(call).toHaveBeenCalledWith(ProxyMethod.UploadFile, {
      parentPath: '/Photos',
      fileName: 'a.txt',
      data,
    })
  })

  it('createFolder dispatches fs.createFolder with the path', async () => {
    await createFolder('/Photos/2024')
    expect(call).toHaveBeenCalledWith(ProxyMethod.CreateFolder, { path: '/Photos/2024' })
  })

  it('rename dispatches fs.rename with path + newName', async () => {
    await rename('/a.txt', 'b.txt')
    expect(call).toHaveBeenCalledWith(ProxyMethod.Rename, { path: '/a.txt', newName: 'b.txt' })
  })

  it('move dispatches fs.move with source + destination folder', async () => {
    await move('/Photos/a.jpg', '/Archive')
    expect(call).toHaveBeenCalledWith(ProxyMethod.Move, {
      sourcePath: '/Photos/a.jpg',
      destinationFolderPath: '/Archive',
    })
  })

  it('deleteEntry dispatches fs.delete with the path', async () => {
    await deleteEntry('/junk.tmp')
    expect(call).toHaveBeenCalledWith(ProxyMethod.Delete, { path: '/junk.tmp' })
  })

  it('details dispatches fs.details with the path', async () => {
    await details('/Photos/a.jpg')
    expect(call).toHaveBeenCalledWith(ProxyMethod.Details, { path: '/Photos/a.jpg' })
  })

  it('activity dispatches fs.activity with the path', async () => {
    await activity('/Photos/a.jpg')
    expect(call).toHaveBeenCalledWith(ProxyMethod.Activity, { path: '/Photos/a.jpg' })
  })

  it('createShareLink dispatches shares.create with defaults for no expiry/cap', async () => {
    await createShareLink('/Photos/a.jpg')
    expect(call).toHaveBeenCalledWith(ProxyMethod.ShareCreate, {
      path: '/Photos/a.jpg',
      expiresAtMillis: 0,
      maxOpens: 0,
    })
  })

  it('createShareLink forwards explicit expiry + cap', async () => {
    await createShareLink('/x', 1234, 5)
    expect(call).toHaveBeenCalledWith(ProxyMethod.ShareCreate, {
      path: '/x',
      expiresAtMillis: 1234,
      maxOpens: 5,
    })
  })

  it('listShares dispatches shares.list', async () => {
    await listShares()
    expect(call).toHaveBeenCalledWith(ProxyMethod.ShareList)
  })

  it('revokeShare dispatches shares.revoke with the shareId', async () => {
    await revokeShare('share-123')
    expect(call).toHaveBeenCalledWith(ProxyMethod.ShareRevoke, { shareId: 'share-123' })
  })

  it('storageStatus dispatches storage.status', async () => {
    await storageStatus()
    expect(call).toHaveBeenCalledWith(ProxyMethod.StorageStatus)
  })

  it('returns the boolean the apex resolves', async () => {
    call.mockResolvedValueOnce(false)
    await expect(rename('/x', 'y')).resolves.toBe(false)
  })

  // MB-1 contact handshake wrappers (drive-web-mode-b-sharing.md).
  it('createContactInvite dispatches contact.createInvite with empty intent by default', async () => {
    await createContactInvite()
    expect(call).toHaveBeenCalledWith(ProxyMethod.ContactCreateInvite, {
      intentKind: '',
      intentLabel: '',
    })
  })

  it('revokeContactInvite dispatches contact.revokeInvite with the inviteId', async () => {
    await revokeContactInvite('inv-1')
    expect(call).toHaveBeenCalledWith(ProxyMethod.ContactRevokeInvite, { inviteId: 'inv-1' })
  })

  it('redeemContactInvite dispatches contact.redeem with the link + name', async () => {
    await redeemContactInvite('https://tenshu.app/i#v1.abc', 'Bob')
    expect(call).toHaveBeenCalledWith(ProxyMethod.ContactRedeem, {
      link: 'https://tenshu.app/i#v1.abc',
      selfAssertedName: 'Bob',
    })
  })

  it('awaitInviteAck dispatches contact.awaitAck, defaulting sasConfirmed false', async () => {
    await awaitInviteAck('inv-1')
    expect(call).toHaveBeenCalledWith(ProxyMethod.ContactAwaitAck, {
      inviteId: 'inv-1',
      sasConfirmed: false,
    })
  })

  it('completeHandshake fills the optional params with sentinels', async () => {
    await completeHandshake({
      inviteId: 'inv-1',
      accept: true,
      shownFingerprint: 'feedface00112233',
      newPersonName: 'Bob',
      selfAssertedName: 'Alice',
    })
    expect(call).toHaveBeenCalledWith(ProxyMethod.ContactComplete, {
      inviteId: 'inv-1',
      accept: true,
      // contact-service v2: what the prompt displayed, always sent (threat model T-CI-10).
      shownFingerprint: 'feedface00112233',
      personId: '',
      newPersonName: 'Bob',
      selfAssertedName: 'Alice',
      sasConfirmed: false,
    })
  })

  it('listContactInvites / connectedContacts / pendingRedemptions dispatch their reads', async () => {
    await listContactInvites()
    expect(call).toHaveBeenCalledWith(ProxyMethod.ContactListInvites)
    await connectedContacts()
    expect(call).toHaveBeenCalledWith(ProxyMethod.ContactConnected)
    await pendingRedemptions()
    expect(call).toHaveBeenCalledWith(ProxyMethod.ContactPending)
  })

  // MB-2 membership grant wrappers (drive-web-mode-b-sharing.md).
  it('grantMembership dispatches membership.grant with realm + person + label', async () => {
    await grantMembership('realm-1', 'p1', 'Photos')
    expect(call).toHaveBeenCalledWith(ProxyMethod.MembershipGrant, {
      realmId: 'realm-1',
      personId: 'p1',
      label: 'Photos',
    })
  })

  it('acceptGrant dispatches membership.accept with the grantor fingerprint', async () => {
    await acceptGrant('fp-1')
    expect(call).toHaveBeenCalledWith(ProxyMethod.MembershipAccept, { grantorFingerprint: 'fp-1' })
  })

  it('realmMembers dispatches membership.members with the realmId', async () => {
    await realmMembers('realm-1')
    expect(call).toHaveBeenCalledWith(ProxyMethod.MembershipMembers, { realmId: 'realm-1' })
  })

  it('removeMember dispatches membership.remove with realm + member', async () => {
    await removeMember('realm-1', 'm1')
    expect(call).toHaveBeenCalledWith(ProxyMethod.MembershipRemove, {
      realmId: 'realm-1',
      memberId: 'm1',
    })
  })

  it('pollPendingGrants / declineGrant dispatch their ops', async () => {
    await pollPendingGrants()
    expect(call).toHaveBeenCalledWith(ProxyMethod.MembershipPollPending)
    await declineGrant('fp-1')
    expect(call).toHaveBeenCalledWith(ProxyMethod.MembershipDecline, { grantorFingerprint: 'fp-1' })
  })

  // MB-3 Mode-B file read wrappers (drive-web-mode-b-sharing.md).
  it('sharedWithMe dispatches sharedFile.sharedWithMe', async () => {
    await sharedWithMe()
    expect(call).toHaveBeenCalledWith(ProxyMethod.SharedWithMe)
  })

  it('browseSharedFolder dispatches sharedFile.browse, defaulting to the root', async () => {
    await browseSharedFolder('realm-1')
    expect(call).toHaveBeenCalledWith(ProxyMethod.SharedBrowse, {
      realmId: 'realm-1',
      parentFileId: '',
    })
  })

  it('browseSharedFolder forwards an explicit parentFileId', async () => {
    await browseSharedFolder('realm-1', 'folder-9')
    expect(call).toHaveBeenCalledWith(ProxyMethod.SharedBrowse, {
      realmId: 'realm-1',
      parentFileId: 'folder-9',
    })
  })

  it('readSharedFile dispatches sharedFile.readFile with realm + file', async () => {
    await readSharedFile('realm-1', 'file-9')
    expect(call).toHaveBeenCalledWith(ProxyMethod.SharedReadFile, {
      realmId: 'realm-1',
      fileId: 'file-9',
    })
  })

  // MB-4 outbound share wrappers (drive-web-mode-b-sharing.md).
  it('shareFolder dispatches sharedFile.shareFolder with the folderPath', async () => {
    await shareFolder('/Photos/Holiday')
    expect(call).toHaveBeenCalledWith(ProxyMethod.ShareFolder, { folderPath: '/Photos/Holiday' })
  })

  it('sharedFolders dispatches sharedFile.sharedFolders', async () => {
    await sharedFolders()
    expect(call).toHaveBeenCalledWith(ProxyMethod.SharedFolders)
  })

  // S6a D2b streaming wrappers (drive-streaming-preview.md §3.5).
  it('openContentStream dispatches fs.openContentStream with the path', async () => {
    call.mockResolvedValueOnce({ handle: 'content-stream-1', totalSize: 42 })
    await expect(openContentStream('/Movies/clip.mp4')).resolves.toEqual({
      handle: 'content-stream-1',
      totalSize: 42,
    })
    expect(call).toHaveBeenCalledWith(ProxyMethod.OpenContentStream, { path: '/Movies/clip.mp4' })
  })

  it('readContentRange dispatches fs.readContentRange with handle + offset + length', async () => {
    const bytes = new Int8Array([9, 8, 7])
    call.mockResolvedValueOnce(bytes)
    await expect(readContentRange('content-stream-1', 2048, 4096)).resolves.toBe(bytes)
    expect(call).toHaveBeenCalledWith(ProxyMethod.ReadContentRange, {
      handle: 'content-stream-1',
      offset: 2048,
      length: 4096,
    })
  })

  it('closeContentStream dispatches fs.closeContentStream with the handle', async () => {
    await closeContentStream('content-stream-1')
    expect(call).toHaveBeenCalledWith(ProxyMethod.CloseContentStream, { handle: 'content-stream-1' })
  })
})
