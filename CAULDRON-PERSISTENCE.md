# Exact cauldron persistence observations

`TheBrewingProjectApi` has two optional methods. Older providers keep the default unsupported
behavior; callers must not translate an unsupported method, exception, or missing result into
an absent cauldron.

- `inspectPersistedCauldrons(keys)` reads 1–32 distinct keys in one world after the original writes
  already admitted for those keys. One SELECT-only SQLite transaction returns exact serialized
  brew and type values, including an explicit nullable legacy type. It neither hydrates a holder
  nor loads a chunk, parses an unknown brew, reserves a coordinate, or mutates persistence.
- `retireCauldron(holder)` requires the exact current native holder and its current persistence
  owner. It performs ordinary display teardown, rechecks holder, provider, database and service
  identities after callbacks, then admits the exact ordered DELETE and removes that registration.
  The receipt observes the original SQL operation and a subsequent absent-row SELECT. It does
  not return drinks, change inventories, restore blocks, or retry an operation.

Both admissions and `current()` require the primary server thread. This seam does not support
Folia region execution. At most eight inspections can be pending; each transaction is capped at
8 MiB of raw SQL text bytes and 8 Mi UTF-16 code units across all requested rows. Byte lengths
are checked before strings are retrieved, including text with embedded NUL. Duplicate physical
rows, malformed SQL types, failed original writes and failed reads are refusals, never absence.

The returned `CauldronPersistenceReceipt` owns its completion privately. Completing or cancelling
a caller's future copy cannot change the original operation, drain acknowledgment, state, or
observation. `OBSERVED` describes successful historical SQL observation. It does not certify
complete cleanup. `current()` additionally rechecks the provider/database lifetime and a
conservative global cauldron admission revision; for a retirement it also requires an empty live
registration at that key. A later write anywhere, world hydration, reload or shutdown invalidates
the proof, including an absent → inserted → deleted ABA sequence.

`deletionAcknowledged()` is deliberately separate: it is true only when this receipt's original
DELETE actually succeeded. It remains a historical fact if a later admission makes its SQL
observation stale. A bounded batch can retire its owned holders, wait for every exact original
acknowledgment, then request one fresh batch SQL inspection. It must independently verify final
runtime/arena ownership and cleanup. A stale `current()` value is never permission to replay a
DELETE. Failed or unacknowledged operations keep the fixture cleanup unresolved; teardown is
not rolled back after an external callback or SQL failure.

There is no schema or operator configuration change. Existing ordinary cauldron writers still
use their shared owner queue; existing ordinary removal behavior is unchanged. The old barrel/
distillery inspection format and its `absent()` meaning are unchanged.

This is groundwork for a native brewing scenario, not its admission. Coordinate rows have no
durable birth token. These runtime holders and receipts cannot authorize deleting a replacement
after restart, prove a crash-safe fixture reservation, or recover delivered items. The proposed
twenty-actor load scenario remains unregistered until durable birth ownership and restart
reconciliation exist. No client load, native physics, cold recovery, escrow integration, or live
acceptance completion is claimed here.

Owner verification uses real temporary SQLite databases. `SqLiteCauldronInspectionTest` covers
scoped raw rows, ordering, physical SQL faults, byte limits, reload and ABA fencing, and admission
bounds. `CauldronPersistenceReceiptTest` exercises the registered provider with MockBukkit's
registry and lifecycle, real SQL, original future tampering, callback/provider replacement,
failed deletion, foreign-row resurrection, and twenty retirements followed by one fresh batch
observation. MockBukkit does not establish native client/physics or process-crash behavior.
