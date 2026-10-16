# Cellar printer patch

`src/cellar/TypePrinter.scala` is a small fork of the file in the published
`org.virtuslab:cellar-lib_3:0.1.0-M13` sources JAR, under MPL 2.0 (see `LICENSE`).
Keep it aligned with the Cellar release pinned in `CellarEnvironment`.

The patch preserves package and enclosing-object names on type references so
an agent can copy a returned type name into its next Cellar lookup. For example:

```scala
def lookupIssue(number: Int): githubskill.Issue
def commit(branch: githubskill.Branch, message: String, paths: Seq[String])(using files: editing.TextFiles): githubskill.Branch
```

Class type parameters such as `A` retain their meaning. Common auto-imported
Scala and Java types remain concise. Inspection and symbol resolution continue
to use Cellar and TASTy Query against the actual published API JARs.

The module compiles with the normal build. On first Cellar use, the harness
packages only these printer classes into a small JAR and puts it ahead of the
pinned Cellar library. The runtime cache includes its content hash, so rebuilding
the printer invalidates the cached runtime configuration automatically.
