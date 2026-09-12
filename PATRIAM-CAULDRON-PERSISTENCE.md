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
running the twenty-lane load test. Durable fixture reservation and consumer recovery remain
separate prerequisites.

Focused owner verification covers real SQLite fresh/legacy migration, exact row/extension and
trigger preservation, interruption and rollback failure, malformed/partial schemas, hydration,
and stale writes against equal-content replacements. The September12 gate ran144 distinct
checks without failures or skips. Canonical dependent builds and native acceptance are separate
release gates; see the shared Patriam implementation handoff for their current status.
