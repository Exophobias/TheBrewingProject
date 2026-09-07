# September Patriam lifecycle review

The complete default correctness gate now passes: 37 API, 1,070 core and 2,126 Bukkit tests,
with four existing explicit Bukkit skips. Run `VERSION=v3.3.2-patriam.3 ./gradlew test` on the
normal Java 21 toolchain; the separate Java 25 `-Ppaper26.2` build produces the shipping artifact.
The Revival canonical script now treats correctness failures as blocking instead of a soft gate.
This is source/build evidence, not a live Paper or production approval.

World hydration reads immutable raw rows and inventories in one SQL transaction. It constructs
Bukkit holders on the server thread and publishes only the current world generation. Unload,
reload and stop invalidate old work before registry replacement. Failed or incomplete hydration
keeps atomic admissions closed until repair/reload; it cannot silently skip durable holders.
Both barrels and distilleries retain the exact persisted key and full-width timestamps. Only new
structures use the corrected deterministic coordinate ordering.

Database drainage includes complete admitted session results and descendant executor tasks.
Barrel insertion and its initial inventory now commit together. Owner-publication receipts are
separate from SQL drainage: queued callbacks settle exceptionally at stop, late callbacks cannot
publish, and cancelling a returned observation cannot discard a committed owner action. Restart
loads authoritative durable state after an interrupted publication. Plugin shutdown closes the
database pool and worker after draining accepted persistence.

Recipe loads prepare complete isolated generations, atomically publish recipes and defaults,
and discard obsolete/shutdown callbacks. Replacing a recipe removes its old ingredient index;
public views are snapshots. Repeated readers no longer leak dedicated executors. Accepted aging
aliases/defaults parse consistently. Okaeri's file-load stream leak is closed for independently
loaded configuration classes, including both parse success and failure; no schema keys changed.

Structure registration rejects overlaps/uninitialized holders before batch publication. Stale
unregistration cannot erase replacement coordinate, cauldron or inventory owners. Open inventory
iteration uses a snapshot so close handlers can unregister themselves without skipping remaining
owners. MockBukkit fixtures now implement the missing resource-pack method and the current barrel
matcher contract; a narrow test-only BlockType adapter supplies MockBukkit's missing material
prefix while retaining real transformation/property checks.

Follow-up ordinary-removal hardening checks the exact current holder before scheduled cauldron
work and on both sides of teardown callbacks. Registry removal already preserved replacement
identities, but a stale callback could still submit a coordinate-only SQL delete against a new
holder's row. Multiblock destruction now revalidates the complete proposal batch after owner
events, retains structure aliases through inventory-close callbacks, and rechecks identity before
SQL admission. It unregisters the old holder only after admitting its delete on the ordered owner
executor; the durable receipt is linked after runtime retirement. A later holder's refusal cannot
falsely cancel an earlier admitted deletion's receipt.

`OrdinaryRemovalOwnershipTest` adds eight passing regressions using real plugin callbacks and
SQLite rows: stale/direct/queued cauldron retirement, replacement during cauldron teardown,
cross-world preservation, replacement during multiblock authorization or inventory close, normal
current-holder deletion, and a delayed earlier receipt surviving later batch refusal. The full
`VERSION=v3.3.2-patriam.3 ./gradlew test` run on JDK 25.0.4 completed 3,233 tests with zero
failures/errors and the same four existing explicit skips. No configuration schema or gameplay
delivery rule changed.

Remaining ordinary-destruction risk is explicit: BlockEventListener/ListenerUtil and last-bottle
cauldron extraction still publish player/world outputs before ordinary durable deletion. The
prepared Powderkeg distillery-consumption path is separate and does not use that path. Safe repair
requires persistent removal/delivery intents and native-event replay or recoverable escrow, with
failure/restart testing. See the Revival plan
`plans/tbp-ordinary-destruction-remediation-2026-09-07.md`; delaying a drop callback alone can lose
items at shutdown and is not a fix. Exact production listener order and live acceptance remain
required for the matched TBP/Powderkeg/Alembic stack.
The identity checks do not make ordinary multi-holder events transactional: an earlier deletion
and its drops can already be admitted when a later callback changes another holder and causes
that native event to be refused. That partial-batch/delivery boundary remains part of M4.
