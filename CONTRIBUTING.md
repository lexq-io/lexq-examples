# Contributing

These rules cover every change to this repository. What a port of the client must
keep is in the [Porting Contract](README.md#porting-contract).

- **Run every example before it lands.** Run it against a live tenant. Outputs pasted
  into READMEs and comments come from a real run, and the pull request says which run.
- **Sanitize pasted output.** API keys, tenant IDs and user IDs become placeholders.
  Decision payloads stay exactly as they came back.
- **Keep the tracks in lock-step.** Every track implements the same scenario with the
  same envelope handling. A contract-level fix lands in every track in the same PR, or
  the PR says why not.
- **Leave no IDE warnings.** Each track is clean under its default inspection profile.
  Readers copy warnings along with the code.
- **Use common dependencies.** Stick to the stack defaults most developers already
  know, so the example teaches LexQ rather than a library.
- **Keep it runnable from the README.** Clone, set the environment variables, run. No
  step may be implied.
- **Link only what readers can open.** Point to docs.lexq.io, never to an internal
  document.
- **Write commits in English.** Use `type: description`.

Run commands and versions live in each track's README and build file, not here.
