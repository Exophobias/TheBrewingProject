# November 2, 2026 integration

Official history is merged through `d5932b8f`. Compatible fixes include canonical keys,
correct incomplete-recipe ingredient amount conditions, clearer colour errors, additional
named colours, Oklab cauldron blending and clearer default-recipe diagnostics.
The ingredient-condition parser retains its asynchronous ingredient contract and rejects
invalid amount names instead of broadening them to ANY. Existing named RGB values and
serializer spellings remain stable; incoming colour aliases only fill previously absent names.
The existing keyed-to-generic structure-matcher fallback is preserved: upstream's per-key
catch would swallow scalar generic declarations and publish an empty keyed matcher. The existing
recursive material-list parser is retained because upstream's string-list discriminator can
reject nested lists that the existing parser accepts.
The upstream overlength rejection is deliberately excluded: this release keeps all previously
accepted hex syntax/lengths. Tightening operator-file validation requires a separate preserving
schema migration; the existing Okaeri config marker alone does not supply that lifecycle.

Recipe-group API/default-group resources and replacement async initialization remain excluded
with the earlier group-loader rollout: the atomic generation loader, schema/catalogue,
production servings, external leases and provider API are preserved. The existing locale-free
migration conversion already supersedes upstream's comma replacement fix. The compile-only
ItemsAdder 4.0.10 dependency is unchanged; a module-scoped local Maven fallback uses the existing
verified cache when its vendor URL no longer serves the artifact.

# October 14, 2026 integration

The release retains official upstream/master history through 1b4be43c (October 14, 2026).
Compatible changes applied: mount/impulse groundwork and coordinate fixes c26c2120/e3f85cfe/
aa75db0c/f811b7d8, integration startup failure containment b541d03e, Folia tick/destroy repairs
cce2ed46, item colour fixes 917a3e4d/ae6aac12, and teleport/command scheduler repairs from 406d6c7c.
Integration failures use local logging without introducing telemetry sends.

Patriam retains the published 3.3.2-patriam.7 API, strict existing operator schemas,
recipe catalogue, reload generation/last-known-good behavior, ordered database writes,
external cauldron leases and ownership protections, and hidden incomplete/poor brews.
New mount/elytra configuration is withheld and those branches remain disabled.
Upstream feature-flag files, default-recipe directory/group rollout, hot-reload injection,
changed barrel defaults and incomplete-recipe semantics are withheld: they replace existing
operator/configuration or persisted recipe/atomic ownership contracts and require a separately
tested migration before activation. Their upstream commits remain in the merged history.
The Nexo hot-reload-only formatting fix is inactive because hot-reload injection is withheld.
