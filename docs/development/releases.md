# Releasing KetraTerm

`VERSION` on `master` is the release version for libraries, the standalone app,
and the IntelliJ plugin. Set it to the desired version and merge the change
before releasing. No manual tag or version input is needed.

In GitHub Actions, select **Release**, then **Run workflow** on `master`.
The workflow:

1. Reads `VERSION` and compares it with all published GitHub releases,
   including prereleases. An already published version is skipped; a downgrade
   or an alias of an existing version fails.
2. Replaces the leading `Unreleased` heading in each of the three changelogs
   with the version and the current UTC date. Older entries and already
   finalized dates are preserved. A finalized section with additional
   Unreleased changes is rejected to avoid ambiguous release contents.
3. Runs formatting, ABI, tests, local publication checks, and native version
   validation before creating the release commit.
4. Commits the changelogs to `master` and creates `v{version}` on that commit
   in one atomic push. If `master` advances during verification, the push
   fails; rerun against the updated branch. Existing tags are never moved.
5. Publishes Maven artifacts, builds application binaries, and creates the
   GitHub release with notes from all three versioned changelog sections.
   Relative links in those notes point to the tagged source.
6. Triggers website/documentation publication through the existing
   `workflow_run` hook after successful completion. Pushes and releases made
   with `GITHUB_TOKEN` do not trigger ordinary event workflows.

The workflow needs permission to push directly to `master` and create tags.
Repository rules must permit the Actions token to do this; the workflow does
not bypass branch protection. Existing Maven Central and signing secrets are
still required.

For the next development cycle, add a new `## [Unreleased]` section to each
changelog and bump `VERSION` before the next release.

## Prereleases

Use the native packager's existing formats: `0.5.0-alpha01`, `0.5.0-beta02`,
or `0.5.0-rc01`. Stage numbers are 1–19; unpadded forms such as `alpha1`
are also accepted, but cannot be published alongside their padded aliases.
The order is alpha, beta, rc, then stable, with numeric ordering within a
stage (`alpha2` precedes `alpha10`). Dotted suffixes (`alpha.1`), unnumbered
stages, build metadata, and `-SNAPSHOT` are rejected before publication.
Prereleases are marked as such on GitHub and never become the latest stable
release. A subsequent `0.5.0` release is newer than `0.5.0-rc19`.

## Interrupted releases

Use GitHub's **Re-run failed jobs** to resume after a publishing failure,
preserving successful Maven publication. Maven releases are immutable; do not
rerun a successful Maven upload unnecessarily. If preparation is rerun with an
existing unpublished tag, it checks out and validates that tagged commit,
preserving its files and date rather than releasing newer changes on `master`.
Tags containing unfinalized changelogs are rejected.

Run the release automation tests locally with:

```shell
node --test .github/scripts/release.test.mjs
```
