# Releasing KetraTerm

`VERSION` on `master` is the release version for libraries and the standalone app.
Set it to the desired version and merge the change before releasing. No manual
tag or version input is needed. The
[IntelliJ plugin](https://github.com/ketraterm/KetraTerm-IntelliJ) owns its version
and release workflow in its separate repository.

In GitHub Actions, select **Release**, then **Run workflow** on `master`.
The workflow:

1. Reads `VERSION` and compares it with all published GitHub releases,
   including prereleases. An already published version is skipped; a downgrade
   or an alias of an existing version fails.
2. Replaces the leading `Unreleased` heading in the library and standalone
   changelogs with the version and the current UTC date. Older entries and already
   finalized dates are preserved. A finalized section with additional
   Unreleased changes is rejected to avoid ambiguous release contents.
3. Runs formatting, ABI, tests, local publication checks, and native version
   validation before creating the release commit.
4. Commits the changelogs to `master` and creates `v{version}` on that commit
   in one atomic push. If `master` advances during verification, the push
   fails; rerun against the updated branch. Existing tags are never moved.
5. Runs the full [CI validation set](testing.md#ci) on the exact tagged revision,
   including native/desktop suites and three-platform consumers.
   Publication waits for this verification to succeed.
6. Publishes Maven artifacts, builds application binaries, and creates the
   GitHub release with notes from both versioned changelog sections.
   Relative links in those notes point to the tagged source.
7. Triggers website/documentation publication through the existing
   `workflow_run` hook after successful completion, so downloads reflect the
   published assets. The release bot's push can also trigger ordinary push
   workflows before publication finishes.

## Release bot setup

The release commit and tag are pushed as a dedicated GitHub App. Create and
install the app on this repository with **Contents: Read and write**, then add
it to the `master` ruleset's bypass list with **Always allow**. Save the ruleset.
If release tags have their own ruleset, allow the app to create those tags too.
Other contributors continue to follow the existing branch rules.

On the app's **General** settings page, copy its **Client ID** and generate a
private key. In the repository's **Settings > Secrets and variables > Actions**,
add:

- **Variables:** `RELEASE_APP_CLIENT_ID` containing the app's Client ID.
- **Secrets:** `RELEASE_APP_PRIVATE_KEY` containing the entire downloaded PEM
  file, including its BEGIN/END lines.

See [GitHub's App authentication guide](https://docs.github.com/en/apps/creating-github-apps/authenticating-with-a-github-app/making-authenticated-api-requests-with-a-github-app-in-a-github-actions-workflow).
The workflow validates these credentials before the release build, generates
a token scoped to this repository with contents-write permission, and uses it
only for the atomic commit/tag push. The token is revoked at the end of the job.
Ordinary checkout and release API calls use `GITHUB_TOKEN`; they do not inherit
the release app's bypass access. Existing Maven Central and signing secrets are
still required.

At the start of the next development cycle, bump `VERSION` to the next intended
release and add a new `## [Unreleased]` section to both changelogs in the same
change, before adding new release notes. Development builds append `-SNAPSHOT`
to `VERSION`; keep that suffix out of the file itself.

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
