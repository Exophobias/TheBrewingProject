# Patriam upstream integration

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
