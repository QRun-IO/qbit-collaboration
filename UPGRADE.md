# Upgrading

## Unreleased

No schema change and nothing required of hosts.

- New table action **Create rows from configuration** on Feature Settings. Run it once if you want the effective feature settings visible and editable as rows. It changes no behavior and does nothing once the table has any row. Note that after rows exist they win over later changes to `withDefaultFeatures` or `withTableFeatures` in code, so change those settings in the rows from then on.
- New **Setup** widget first on the Collaboration home, and table help text. Hosts that override help on a table keep their own.
