# mini-npm

A small, dependency-aware package manager that runs in the terminal, written in
plain Java with no external libraries. Packages are looked up in an in-memory
registry, dependencies are installed transitively, and `ref_count` tracking
decides when a package can actually be removed.

## Commands

| Command | What it does |
| --- | --- |
| `install <package_name>` | Installs a package together with all of its dependencies. |
| `install --dry-run <package_name>` | Shows what an install would add, without changing anything. |
| `why <package_name>` | Shows why a package is installed: every chain from a package you installed down to it. |
| `install-json <manifest>` | Installs the `dependencies` of a manifest typed on the command line. |
| `install-file [path]` | Same, reading the manifest from a file (default `package.json`). |
| `remove <package_name>` | Removes a package you installed directly, and any dependency that nothing else still needs. |
| `list` | Shows the installed packages and their current `ref_count`. |
| `history` | Shows successful commands, newest first. |
| `help` | Prints the command summary. |
| `exit` / `quit` | Quits the program. |

`samples` in the registry: `app`, `ui`, `net`, `core`, `utils`, `logger`,
`x`, `y`, `selfref`, `broken`.

## Build and run

```bash
javac -encoding UTF-8 -d out *.java
java -cp out Main
```

The test suite is a plain `main` as well, so no test framework is needed:

```bash
java -cp out ManagerTests
java -cp out AlgorithmTests
java -cp out ManifestTests
java -cp out AuditRegressionTests
```

`ManagerTests` asserts that the default cyclic samples fail. Successful diamond
scenarios use an explicitly acyclic test registry; the production registry is
unchanged. `AlgorithmTests` checks graphs and randomized command sequences
against an independent reference model, plus structures, parser robustness and
CLI integration. `ManifestTests` covers complete JSON syntax, Unicode and escape
decoding, generated values, 10,000 nested containers, and CLI atomicity.
`AuditRegressionTests` asserts desired behavior for audit findings within the
agreed CLI scope. The registry-mutation API scenario is excluded by default and
can be reproduced with `java -cp out AuditRegressionTests --include-api-mutation`;
it remains an API limitation and that optional run currently exits with status 1.
Its memory stress case
runs in a separate JVM with a 32 MiB heap and an eight-second timeout.
The expanded audit tests use JDK 11 or newer. Run the suites above to review results.

GitHub Actions runs all four suites on Ubuntu and Windows with JDK 11 and 25
for pushes and pull requests. The workflow can also be started manually from Actions.

`install-file` treats the remainder of the command as one path. Paths containing
spaces work unquoted, in double quotes, or in single quotes; an empty quoted path
or an unclosed opening quote produces an error. With no path, it uses `package.json`.

```text
install-file "C:\my project\package.json"
install-file C:\my project\package.json
```

## How it works

- Preflight validation uses an array of three states (unseen, visiting, validated)
  and the existing `LinkedStack` for iterative depth-first search. An edge to a
  visiting package is a cycle; a validated package is skipped. Each reachable
  package's dependencies are expanded once per command, including shared roots
  in a manifest install. This avoids exponential path enumeration and recursive
  call-stack limits without introducing maps or sets into production code.
  Registry name lookup still scans an array, so overall validation is polynomial,
  rather than claiming linear time for the whole operation.
- `PackageRegistry` — array-backed table of name → dependency names.
- `PackageManager` — installs and removes packages. `install` walks the
  dependency graph breadth-first with a `LinkedQueue`, `remove` releases
  references outward from the target.
- `InstalledPackageList` — the installed set kept in a linked list, in install
  order, so `list` output is stable and removals can be undone by index.
- `PackageNode` — one installed package: `ref_count` (how many packages need it)
  and `direct_count` (how many times the user asked for it).
- `DependencyRequest` — a queued request carrying its parent chain, which is how
  circular dependencies (`x -> y -> x`) are detected.
- `PackageManagerException` — a failed command is all-or-nothing.
- `InstallPlan` — the result of a dry run.
- `WhyExplainer` — builds the chains printed by `why`.
- `ManifestParser` — full JSON validation with a linked stack and dependency names in a linked queue.

### Bonus features

Each one reuses the structures above instead of adding new ones.

**`install --dry-run <pkg>`** (`npm install --dry-run`). The install really runs, then the undo
stack is popped to take everything back. Popping gives the newest change first, so every popped
record is pushed onto a second stack, which turns the list back into install order. The command
prints the new packages and the references it would raise, and the state is exactly as before.

```
mini-npm> install --dry-run app
Dry run of 'install app' (nothing is changed):
  + would add app
  + would add ui
  + would add net
  + would add core
  + would add utils
  ~ would raise ref_count of core by 1
```

**`why <pkg>`** (`npm why`). Starting at the package, a `LinkedQueue` walks upwards to the installed
packages that depend on it, level by level, so the shortest chains come first. Every step keeps a
link to the step it came from, so a finished chain reads top-down without reversing anything. A chain
ends at a package you installed directly. Packages already in a chain are skipped, so it can never loop.

```
mini-npm> why core
'core' is installed because (each chain starts at a package you installed):
  app -> ui -> core
  app -> net -> core
```

**`install-json` / `install-file`** (`npm install` reading `package.json`). A manifest looks like
`{"name": "my-app", "dependencies": ["ui", "logger"]}` (the npm style
`"dependencies": {"ui": "1.0.0"}` works too, only the names are used). It is read in two steps:
first a `LinkedStack` checks that every `{` and `[` is closed by the matching bracket in the right
order (brackets inside quotes are ignored); then an iterative parser validates the
entire JSON grammar, including unrelated properties and trailing input. Syntax
errors include their position. JSON string escapes and Unicode escapes are decoded.
The root must be one object; only its top-level `dependencies` is used, and a
duplicate top-level `dependencies` key is rejected as ambiguous. Dependency arrays
contain strings and dependency objects map names to string versions. No installation
starts until the entire manifest is valid. Names are collected in a `LinkedQueue`
and installed in file order. If any package fails, the whole install is rolled back.

```
mini-npm> install-json {"dependencies":["logger","utils"]}
Installed 2 package(s) from the manifest (2 new package(s) added).
mini-npm> install-json {"dependencies":["ui","logger"
Error: Manifest error: '[' opened at position 17 is never closed.
```

### All-or-nothing commands

Every install or remove records its mutations as `UndoRecord`s on a
`LinkedStack`. If anything fails part-way — unknown package, circular
dependency, inconsistent state — the stack is popped back to its starting point
and the command reports that its changes were rolled back. A command that fails
leaves the installed set exactly as it was.

### Reference counting

Installing a package that is already present only increments `ref_count`. On
removal, a package is deleted from the installed set only when its `ref_count`
drops to zero; its own dependencies are then released in turn. A package the
user did not install directly (`direct_count == 0`) cannot be removed on its own
— it is only released when the packages depending on it are gone.

## Files

| File | Responsibility |
| --- | --- |
| `Main.java` | Terminal loop, command parsing, messages. |
| `PackageManager.java` | Install / remove logic, reference counting, rollback. |
| `PackageRegistry.java` | Package catalogue and dependency lookup. |
| `InstalledPackageList.java` | Linked list of installed packages. |
| `PackageNode.java` | One installed package's counters and link. |
| `DependencyRequest.java` | Queued dependency request + cycle detection. |
| `UndoRecord.java` | A single reversible mutation. |
| `CommandHistory.java` | Successful command history. |
| `LinkedStack.java` | Generic LIFO stack (undo, history building). |
| `LinkedQueue.java` | Generic FIFO queue (BFS traversal). |
| `ManagerTests.java` | Self-checking test suite with a `main`. |
| `PackageManagerException.java` | Domain error type. |
| `InstallPlan.java` | What a dry-run install would do. |
| `WhyExplainer.java` | Upward search behind `why`. |
| `ManifestParser.java` | Reads the dependencies of a manifest. |
| `Queues.java` | Helper: empties a queue of names into an array. |
| `package.json` | Sample manifest for `install-file`. |
