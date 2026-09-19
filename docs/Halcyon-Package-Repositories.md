# Halcyon Package Repositories

Forge can browse and install custom sets from remote Halcyon package repositories. A repository is a base URL that hosts a catalog file and one or more `.forgepkg.zip` packages.

## Repository URL forms

Users may add any of the following:

| Input | Resolved base |
|-------|----------------|
| `https://example.com/sets/` | used as-is (trailing `/` normalized) |
| `owner/repo` | `https://raw.githubusercontent.com/owner/repo/main/` |
| `owner/repo@branch` | `https://raw.githubusercontent.com/owner/repo/{branch}/` |
| `https://github.com/owner/repo` | `https://raw.githubusercontent.com/owner/repo/main/` |
| `https://github.com/owner/repo/tree/branch` | `https://raw.githubusercontent.com/owner/repo/{branch}/` |

Forge fetches `{base}manifest.json` for the catalog.

## Catalog `manifest.json`

```json
{
  "schemaVersion": 1,
  "name": "Example Custom Sets",
  "sets": [
    {
      "id": "MSE_CHAMPIONS",
      "code": "MSE_CHAMPIONS",
      "name": "MSEM Champions",
      "description": "Champions showcase set for MSEM.",
      "lastModified": "2026-09-18T12:00:00Z",
      "package": "packages/MSEM_Champions.forgepkg.zip"
    }
  ]
}
```

| Field | Required | Notes |
|-------|----------|-------|
| `schemaVersion` | yes | Currently `1` |
| `name` | no | Display name for the repository |
| `sets` | yes | May be an empty array |
| `sets[].id` | yes | Stable install key (prefer set code) |
| `sets[].code` | no | Set code for display; falls back to `id` |
| `sets[].name` | yes | Set display name |
| `sets[].description` | no | Shown in the package browser |
| `sets[].lastModified` | yes | ISO-8601 timestamp; UI shows `YYYY-MM-DD` |
| `sets[].package` | yes | Relative to repo base, or absolute `http(s)` URL |

Invalid entries are skipped with a warning. An empty `sets` list is valid.

## Package artifact

Each `package` points at a Halcyon `.forgepkg.zip` containing at least:

- `manifest.json` — package metadata (`name`, `code`, …)
- `edition.txt` — full custom edition file
- `cards/` — card scripts (paths preserved under Forge's custom cards folder layout)
- `images/` — card images installed under the set-code folder in card pics cache

Optional (installed when present):

- `tokens/` — token scripts → custom tokens directory
- `token_images/` — token images → token pics cache under the set code

## Install behavior

- **Install / Reinstall** replaces the edition file, card scripts from the package, and set-scoped images.
- **Uninstall** removes the edition and set image folders. Shared card scripts that other custom editions still reference are left on disk.
- After any successful install, reinstall, or uninstall, Forge must be **restarted** before the change takes effect.
