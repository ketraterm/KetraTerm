---
name: terminal-documentation
description: Use when writing or reviewing KetraTerm READMEs, guides, API examples, or changelogs, and when behavior, API, setup, or default changes can invalidate consumer instructions. Do not invoke for source formatting or internal refactoring with no documentation impact.
---

# KetraTerm Documentation

Read the [root documentation rules](../../../AGENTS.md#documentation), the owning
module guide, and the [documentation index](../../../docs/README.md). Work within
the task's scope and honor explicit exclusions such as no changelog updates.

## Keep consumer instructions current

- Check the affected README and linked guides against the final implementation,
  even when the task did not explicitly request documentation. Update inaccurate
  instructions; an internal change does not automatically need a prose change.
- Lead READMEs with purpose, prerequisites, setup, and a small usable example.
  Explain relevant defaults, customization, limitations, and ownership. Put
  detailed contracts in their owning reference and link them from the overview.
- Verify names, signatures, imports, dependency coordinates, settings, commands,
  defaults, and shell/platform requirements against source and tests. Use
  `{version}` in dependency examples. Do not infer support from planned work or
  a passing test that exercises a different environment.
- Describe the current system. Remove obsolete migration notes for unreleased
  APIs, abandoned approaches, and comments about what an earlier draft did.
  Keep migration guidance when consumers of a released version need it.

## Give each fact one home

- Follow the document roles in the root guide. Keep feature scope in the canonical
  feature/gap maps, protocol details in the protocol reference, and module
  integration and lifecycle contracts beside the owning module.
- Link shared limits and guarantees rather than copying their full explanation
  into READMEs, feature pages, module maps, and examples. Keep the short context a
  reader needs to understand the link.
- Keep real API, cancellation, failure, and privacy contracts. Simplification must
  not erase a guarantee or turn partial support into a broader claim.
- Use plain language and practical examples. Remove run-by-run validation logs,
  temporary tool failures, debugger transcripts, and repeated test counts from
  maintained guides. Git and PR history can explain implementation history.

## Write release notes only for release changes

- Check the latest release and the relevant released changelog before calling a
  change a fix or regression. A change to an unreleased feature is part of that
  feature's development, not a separate fix to shipped behavior.
- Add changelog entries for completed consumer-visible changes in the release
  scope. If an unreleased feature already has an entry, consolidate around its
  final behavior instead of appending its implementation steps and interim fixes.
  Do not create entries for features the user has excluded from release notes.
- Preserve released history. Keep task progress, investigations, test runs,
  benchmark trials, and future work out of changelogs. Use the gap map for actual
  deferred capability work; do not create a development diary for every task.

## Keep evidence proportional

Performance measurements can guide implementation without becoming permanent
documentation. Do not add before/after reports or commit generated captures by
default for work in progress. If publishing a performance claim is requested or
required, include reproducible methodology and supporting evidence under the
benchmark guide; otherwise describe behavior without unsupported speed or memory
claims. Remove obsolete captures when their reports are removed, after checking
references and preserving unrelated artifacts.

## Verify the final documentation

Check local links and heading anchors, including incoming links when moving or
removing content. Verify changed examples against current APIs and compile them
when API calls change. Follow the root validation rules for documentation-only
changes. Finish by removing duplication and stale text within the touched scope;
do not expand a focused update into an unrelated repository-wide cleanup.
