# Patriam cauldron persistence

`3.3.2-patriam.5` gives every ordinary cauldron an immutable birth UUID. Captured INSERT,
UPDATE and DELETE work carries that birth; updates and deletes require both the coordinates
and original birth. An old queued writer cannot modify a replacement with identical contents.
Fresh world hydration adopts the persisted birth through its exact drained lifecycle permit.

Database schema3 becomes4 in one SQLite transaction: add and backfill the birth column, retain
all existing row fields and extension columns, create uniqueness/type guards, then publish the
version. Existing cauldron triggers are restored with their original SQL without firing their
gameplay/audit effects during metadata backfill. Version-table extension values survive marker
publication. Fresh creation and supported older migration paths are tested separately; older
PRAGMA-dependent migrations retain their original autocommit boundary. Failed rollback never
enables autocommit on an unresolved transaction.

Malformed, partial and future schemas refuse initialization. The new version cannot be used by
an older schema3 binary; do not reset the database version or remove birth data to force a
downgrade. Operator configuration is unchanged.

The optional public `Cauldron.birthUuid()` and persistence-row birth projection expose identity,
not fixture ownership. Legacy providers return unknown birth evidence. A birth alone never
authorizes deleting test contents, reclaiming a position after restart, serving an item, or
running the twenty-lane load test. Fixture reservation is a separate capability, described below;
native fixture creation and consumer recovery remain separate prerequisites.

## Empty fixture reservations

`3.3.2-patriam.6` adds database schema **5**, migrating schema4 atomically without
rewriting ordinary cauldron births, brew values or extension columns. It introduces bounded
reservation history and SQL guards; operator configuration is unchanged. Older binaries reject
schema5. Do not remove its owner tables or lower the version to force a downgrade.

The optional public API now offers `reserveCauldronFixtures`, `inspectCauldronFixtures` and
`closeCauldronFixtures`. A caller must first persist the exact request: run UUID, random capability
and 1–20 distinct same-world lane coordinates with distinct actual actor UUIDs. The actor fields
are attribution supplied by the caller, not verified player provenance or input permission.
There is no acquisition by coordinate, copied birth, missing-record fallback or replacement adoption.

Reserve fences empty coordinates before queued SQL begins, then persists the complete request
and newly issued births in one transaction. Native ingredient/extraction entry, lower owner
capabilities and ordinary SQL writes cannot use reserved coordinates. Startup reinstates retained
reservations before hydration; unexpected rows remain stored and quarantined while unrelated
cauldrons hydrate normally. Reload and world unload refuse retained reservation ownership.

Close retires **only the empty reservation**. It rechecks runtime and persisted absence and
atomically closes every lane while retaining the exact request/birth tombstone. It never deletes
cauldron contents, restores blocks, removes actors or hands out items. Any live or persisted row
refuses cleanup. CLOSED permits an exact close retry, including after a lost reply/restart; a
closed run cannot be acquired again. A new run can reserve the released keys with new births.
History is capped at 128 runs and requests at 20 lanes; admission refuses capacity rather than
silently pruning authority. Unknown SQL outcomes retain runtime fences until exact inspection
and cleanup/restart establish the durable state. Missing/malformed owner evidence never means clean.

Receipt completion is owner-controlled. `current()` additionally requires the original enabled
provider/main thread, current ordering revision, runtime absence and coherent reservation fences.
A caller-completed/cancelled future is not an acknowledgment. Inspect is read-only; RESERVED
or historical CLOSED observations alone do not prove the consumer's arena/item cleanup.

**Native cooking remains unavailable for reserved lanes.** The next separate increment must
provide the default-disabled isolated profile and actor/input guards, native INSERT+CREATED
publication, owned-content retirement and consumer recovery before registering one real lane
or twenty-lane load. This release provides reservation/quarantine/empty cleanup, not a completed
cooking workload, fixture-value destruction authority, or general removal escrow.

Focused owner verification covers real SQLite fresh/legacy migration, exact row/extension and
trigger preservation, interruption and rollback failure, malformed/partial schemas, hydration,
and stale writes against equal-content replacements. The September 12 birth gate ran 144 distinct
checks; the September 14 reservation gate ran 136 checks, including registered input and cold
hydration quarantine. Both passed without skips. Canonical dependent builds and native acceptance are separate
release gates; see the shared Patriam implementation handoff for their current status.
