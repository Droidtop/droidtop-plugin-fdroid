# F-Droid core (`creation`)

The code that reads F-Droid repositories, used by droidtop's F-Droid plugin: a repository's signed `entry.jar`
checked against its fingerprint (`EntryJar.kt`), its index read through the F-Droid index library (`Indexer.kt`),
the catalog and store model droidtop shows (`Catalog.kt`, `Store.kt`), and repository links (`RepoLinks.kt`).
`test/run.sh` runs EntryJar against real `entry.jar` files in `test/fixtures`.

## Branches

Like Enginehost's cores, this plugin is three branches:

- **`creation`** (this branch): the core code, written here.
- **`plugin-core`** (the default branch): the wrapper that makes it a droidtop plugin (the plugin host glue,
  build, signing, manifest) and the line declarations in `plugin-line/lines/<line>/line.json`, each pinning a
  `creation` commit.
- **`plugin/<line>`**: a composition, `creation` at its pin with `plugin-core` written over it, built by
  Droidtop/droidtop-platforms `tools/plugin_line.py`. Releases are built from it; nothing is authored there.

Every change lands through a `ci/` branch. There is no `main`.

## Licence

GPL-3.0, see [LICENSE](LICENSE) and [NOTICE](NOTICE).
