# Contributing to KetraTerm

Thanks for your interest in contributing to KetraTerm! 

---

## What this repository is

This repository contains the source code for KetraTerm, a fast, modern, and secure terminal pipeline built in Kotlin/JVM 21. It hosts:
*   The headless terminal logic (`ketraterm-core`, `ketraterm-parser`, `ketraterm-input`, etc.).
*   The reusable Swing component (`ketraterm-ui-swing`).
*   The standalone desktop application wrapper (`ketraterm-app`).
*   The IntelliJ Platform plugin (`ketraterm-intellij-plugin`).

## What this repository is not

KetraTerm is not trying to chase literal full xterm parity or legacy terminal emulation quirks (such as Tektronix 4014 emulation, printer passthrough, or X11 font protocols). The focus is on a fast, clean, and secure architecture for contemporary shells and TUI tools.

---

## Contributing Workflow

We welcome community contributions!

1.  **Search / Open an Issue**: If you want to fix a bug or add a feature, check the existing issues first. If none exist, please open a new issue to discuss your proposed changes with the maintainers. This helps avoid conflicts with the roadmap and helps discover potential edge cases early.
2.  **Submit Patches**: The best way to submit a patch is to fork the repository on GitHub and send a Pull Request to the `master` branch.
3.  **Keep it Focused**: Make a reasonable amount of changes related only to the issue you are addressing.

---

## Building & Running

### Standalone Application (`ketraterm-app`)
To compile, run, or test the standalone desktop application from the root project directory:

*   **Run the App:**
    ```bash
    ./gradlew :ketraterm-app:run
    ```
*   **Run with Custom Arguments / Shell:**
    ```bash
    ./gradlew :ketraterm-app:run --args="cmd.exe"
    ```
*   **Run Tests:**
    ```bash
    ./gradlew :ketraterm-app:test
    ```

### IntelliJ Platform Plugin (`ketraterm-intellij-plugin`)
The IntelliJ plugin is structured as a **separate Gradle project**. To build, run, or test it, you must navigate into its module directory:

```bash
cd ketraterm-intellij-plugin
```

From inside the `ketraterm-intellij-plugin` directory, you can run:

*   **Run Sandbox IDE:**
    ```bash
    ./gradlew runIde
    ```
*   **Run Tests:**
    ```bash
    ./gradlew test
    ```

### Code Formatting
Before submitting your changes, run spotless to format the Kotlin and Gradle files:
*   In the root directory (for core/app files):
    ```bash
    ./gradlew spotlessApply
    ```
*   In the `ketraterm-intellij-plugin` directory (for plugin files):
    ```bash
    ./gradlew spotlessApply
    ```

---

## Rules for Commit Messages

We follow clean, structured commit guidelines. It is highly recommended to read [How to Write a Git Commit Message](https://chris.beams.io/posts/git-commit/).

### Commit Message Formatting
1.  **Capitalize the title**
2.  **Do not end the title with a period**
3.  **Use the imperative mood in the title** (e.g., `fix(ui): request focus...` instead of `fixed...` or `fixes...`).
4.  **Explain "what" and "why" vs. "how"** in the commit body.
5.  **Reference relevant issue IDs** (e.g., `#42`).

---

## Pull Request Checklist

Before submitting a Pull Request, please ensure you can say "YES" to all of the following points:

- [ ] You ran the build locally and verified the new functionality works.
- [ ] You ran the relevant tests locally and they passed.
- [ ] Your code complies with the formatting rules (Spotless has been applied).
- [ ] Your commit messages follow the repository guidelines.
- [ ] You do not have merge commits in your Pull Request.
