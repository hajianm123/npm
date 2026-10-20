# mini-npm

A small, dependency-aware package manager that runs in the terminal, written in
plain Java with no external libraries. Packages are looked up in an in-memory
registry, dependencies are installed transitively, and `ref_count` tracking
decides when a package can actually be removed.

## Commands

| Command | What it does |
| --- | --- |
| `install <package_name>` | Installs a package together with all of its dependencies. |
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
```

## How it works

- `PackageRegistry` — array-backed table of name → dependency names.
- `PackageManager` — installs and removes packages. `install` walks the
  dependency graph breadth-first with a `LinkedQueue`, `remove` releases
  references outward from the target.
- `InstalledPackageList` — the installed set kept in a linked list, in install
  order, so `list` output is stable and removals can be undone by index.
- `PackageNode` — one installed package: `ref_count` (how many packages need it)
  and `direct_count` (how many times the user asked for it).
- `DependencyRequest` — a queued request carrying its parent chain, which is how
  circular dependencies (`app -> ui -> core -> app`) are detected.
- `PackageManagerException` — a failed command is all-or-nothing.

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
