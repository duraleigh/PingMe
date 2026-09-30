# PingMe build log

One entry per plan step. Newest at the bottom.

## Session start

No steps executed yet. Next: P0.1.

## P0.3 (done ahead of the build, by the owner)

The sideload signing keystore was generated and the four repository secrets exist on
GitHub with these exact names: `SIDELOAD_KEYSTORE_B64`, `SIDELOAD_KEYSTORE_PASSWORD`,
`SIDELOAD_KEY_ALIAS`, `SIDELOAD_KEY_PASSWORD`. The keystore file is not in the repo
and never should be. The builder only needs to wire the release signing config and
the CI workflow to read these; do not ask the owner to create them again. If CI
fails to decode or open the keystore, ask the owner to re-paste
`SIDELOAD_KEYSTORE_B64` from their password manager. Next: P0.1.
