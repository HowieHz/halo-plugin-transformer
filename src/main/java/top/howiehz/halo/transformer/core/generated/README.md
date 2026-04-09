# Generated Files

This directory stores generated Java sources.

- Do not edit files in this directory by hand.
- Update the authoritative spec or generator instead.
- Regenerate files with `pnpm generate:spec-artifacts` from the repository root.

Why:

- keeping generated sources in a dedicated directory makes the hand-written `core` code easier to read
- routing all changes through the spec and generator prevents Java and TypeScript contract helpers from drifting apart
