# Generated Files

This directory stores generated JSON schema artifacts.

- Do not edit files in this directory by hand.
- Update the authoritative spec or generator instead.
- Regenerate files with `pnpm generate:spec-artifacts` from the repository root.

Why:

- schema artifacts must stay byte-for-byte aligned with the shared contract spec
- treating these files as generated prevents formatter or manual edits from becoming a second source of truth
