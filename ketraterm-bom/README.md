# KetraTerm BOM

`ketraterm-bom` supplies version constraints for the published KetraTerm libraries
and the `ketraterm-headless` and `ketraterm-swing` dependency entry points. Import
it once, then declare the modules your host needs without repeating their versions.
The BOM contains dependency metadata and adds no runtime libraries by itself.

## Gradle

```kotlin
dependencies {
    implementation(platform("io.github.ketraterm:ketraterm-bom:{version}"))
    implementation("io.github.ketraterm:ketraterm-headless")
    implementation("io.github.ketraterm:ketraterm-pty")
}
```

This selects the headless pipeline and optional local PTY support. Use
`ketraterm-swing` instead of `ketraterm-headless` when embedding the Swing view.

See the [root setup guide](../README.md#using-the-libraries) for repositories and version selection.

## Maven

Import the BOM in `dependencyManagement`, then add selected dependencies:

```xml
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>io.github.ketraterm</groupId>
      <artifactId>ketraterm-bom</artifactId>
      <version>{version}</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>
<dependencies>
  <dependency>
    <groupId>io.github.ketraterm</groupId>
    <artifactId>ketraterm-headless</artifactId>
    <type>pom</type>
  </dependency>
  <dependency>
    <groupId>io.github.ketraterm</groupId>
    <artifactId>ketraterm-pty</artifactId>
  </dependency>
</dependencies>
```

The headless and Swing entry points have POM packaging and require `<type>pom</type>`
in Maven dependencies. Individual JVM libraries use the default JAR type. Snapshot
repository configuration is covered in the
[compatibility guide](../docs/library-compatibility.md#supported-boundary).

## Managed artifacts

The BOM covers the [published libraries](../ARCHITECTURE.md#published-libraries)
and both dependency entry points. Optional integrations are version-aligned but
are included only when you declare them or a selected module depends on them.
