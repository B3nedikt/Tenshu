# Cost brakes: the relay storage ceiling and the signup cap

Two operator settings put an upper bound on the backend's only cost that grows with users: stored
bytes. Both are **off unless set**.

| Setting | Service | What happens past it |
|---|---|---|
| `STORAGE_CEILING_GIB` | relay | New content uploads and new share links get `507` + `{"error":"storage_ceiling"}`. |
| `SIGNUP_LIMIT_PER_DAY` | commerce | A new address is mailed "sign-ups are paused" instead of a code. |
| `SIGNUP_LIMIT_TOTAL` | commerce | Same, once that many verified open accounts exist. |

## Why both

Per-owner quotas cap one account (Free 5 GiB, Basic 50 GiB, Premium 2 TiB). They don't cap how many
accounts exist, and signup is open with a verified email as the only gate. The ceiling bounds the
bill outright. The signup cap slows down anyone trying to reach it with throwaway accounts.

## What a full relay still does

- **Reads, sync and downloads:** unaffected.
- **Re-uploads of a ref the owner already holds:** accepted, so retries and in-place re-seals
  (manifests, relation slices) keep working.
- **Chunks of a share created before the ceiling was reached:** accepted. The share's bytes were
  charged when it was created.
- **Clients:** they see the same `507` as an owner at quota and back off with probes
  (`SyncScheduler.storageBackoff`). ⚠ They show "storage full" copy today; nothing on the client
  tells the two apart yet.

## How the relay measures

`StorageCeiling` sums `content_blob.byte_len` plus `share_record.chunk_count × 8 MiB` at most once a
minute, and adds each accepted write in between. The figure can only run high (a re-PUT counts again,
and nothing freed comes off until the next measurement), which is the safe side for a brake. Shares are
counted at the per-chunk cap because chunk sizes are never indexed.

The relay logs one line each time the state changes:
`Storage ceiling reached: ~N of M bytes stored; …` and `Storage ceiling cleared: …`.

## How commerce counts

Only **verified, open** accounts count. Unverified code requests never fill the cap, so nobody can
close sign-ups by requesting codes for addresses they don't own. Existing verified accounts always
sign in. ⚠ The check runs at `/register`, so codes already mailed can still be verified: expect a
small overshoot.

`/register` answers `202` either way, and the paused notice costs the same SMTP round trip as a code,
so the route still reveals nothing about whether an address has an account.

## Sizing

Storage is mirrored to a second region, so each stored GB is billed about twice
(≈ €0.03/GB-month at Scaleway's Standard price in Nov 2026). Rough guide:

| `STORAGE_CEILING_GIB` | Worst-case storage bill / month |
|---|---|
| 500 | ≈ €15 |
| 2000 | ≈ €60 |
| 10000 | ≈ €300 |

Pick a signup cap that fits the ceiling: `SIGNUP_LIMIT_TOTAL × 5 GiB` should not be far above it, or
Free accounts alone can fill the relay.

## Setting them

Both are environment variables passed through each service's `docker-compose.yml`.

1. **Relay:** add `STORAGE_CEILING_GIB=<n>` to `/opt/tenshu/deploy.env` on the relay box, then recreate
   the relay container as `deploy/scaleway-relay/README.md` describes. Check the start-up
   log for `Storage ceiling: <n> GiB across all owners`.
2. **Commerce:** add `SIGNUP_LIMIT_PER_DAY=<n>` and/or `SIGNUP_LIMIT_TOTAL=<n>` to the commerce env file,
   then recreate commerce. Check for `Signup cap: …` in its log.

⚠ A value that isn't a whole number makes the service **refuse to start**, rather than silently
running without a brake. ⚠ A Terraform apply does not change a running box (user_data is read once);
edit the env file on the box.

## Related

- Threat model **T-R-14**: filling the ceiling denies new uploads to everyone. That's the accepted trade
  for a bounded bill.
- `docs/tech-debt.md`, *Share-link bytes are not charged to the owner's quota*.
