# Container Image Builder (Java)

A local, layered container-image build and cache service. It takes a build
description (Dockerfile-like) and a context directory, and produces a
layered image package. Layers are content-addressed and reused across builds;
the base image is a local image package. Everything runs on the local machine
with no external services, credentials or paid resources.

## Build & run

```bash
mvn clean package
mvn spring-boot:run
```

The service starts on `http://localhost:8080`.

## Supported instructions

| Instruction | Description |
|-------------|-------------|
| `FROM name[:tag]` | Reference to a local base image package. Tag defaults to `latest`. |
| `COPY [--chown=...] src... dest` | Copy context files into a new layer. `--chown` is accepted (ownership is normalized to root). |
| `ADD src... dest` | Treated as `COPY` (no remote fetch, no archive extraction). |

- Comments (`#`), blank lines and backslash line continuations are supported.
- Any other instruction (e.g. `RUN`, `CMD`) fails the build.
- `COPY` sources must stay inside the build context (no `..` escapes).
- `dest` is normalized (leading `/` stripped); `..` segments are rejected.
- Multiple sources require `dest` to be a directory (ending with `/`).

## Image package layout

A built image is a self-contained directory under the output dir:

```
<output>/<tag-sanitized>/
  manifest.json          # deterministic, sorted-keys JSON
  blobs/sha256/<digest>  # config blob + layer tars (hard-linked from cache)
```

The manifest lists the config descriptor and the ordered layer descriptors.
Every load re-verifies each blob's sha256 digest and the layer order.

## Cache semantics

- **Content-addressed blobs**: layers are stored as `blobs/sha256/<tar-digest>`.
  Identical tar content is stored exactly once.
- **Input key**: each layer's cache key is `sha256(parent-digest + destination +
  sorted(context-relative-path : content-sha256 : mode : size))`. When the base
  image or any context file changes, that layer's key changes, so it is rebuilt;
  every subsequent layer's key also changes (parent digest is part of the key),
  so they are rebuilt too.
- **Atomic publication**: a layer is written to a staging temp file, then
  atomically moved into the blob store. A half-written layer is never visible or
  referenced.
- **Per-key locks**: identical layers from concurrent builds are computed once;
  other threads wait and reuse the result.
- **Global read/write lock**: builds hold the read lock from layer acquisition
  through image package publication. Garbage collection holds the write lock,
  so it can never delete a layer a build is about to reference.
- **Index**: `layers-index.json` maps input keys to layer digests. It is updated
  atomically and self-heals if missing or corrupt.

## Reproducibility

- Layer tars are deterministic: entries are sorted, mtime is the epoch (0),
  uid/gid are 0 (root), uname/gname are `root`. File content and permission bits
  are preserved.
- The image config contains only `architecture` and `os` (no `created` field).
- The image id is `sha256(manifest.json)`; the manifest has no volatile fields.
- Same input at any time, on any machine, produces the same image id and layer
  bytes.

## Garbage collection

A blob is **referenced** if it is the config or a layer of any image package in
the output directory or the base image directory. GC:

1. Scans all packages for referenced digests.
2. Deletes unreferenced blobs (oldest-access-first when a size limit is set).
3. Removes staging temp files older than the grace period.
4. Drops stale index entries.

When `builder.max-cache-size-bytes` is `0` (default), all unreferenced blobs are
removed. When set, unreferenced blobs are evicted LRU until the total is under
the limit. Referenced blobs are never deleted.

## Configuration

Configure in `application.properties` (or via `BUILDER_*` environment variables):

| Property | Default | Description |
|----------|---------|-------------|
| `builder.cache-dir` | `data/cache` | Root of the content-addressed layer cache. |
| `builder.base-images-dir` | `data/base-images` | Directory of pre-built local base image packages. |
| `builder.output-dir` | `data/images` | Directory where built image packages are written. |
| `builder.max-cache-size-bytes` | `0` | Max cache size in bytes; `0` means unlimited. Unreferenced blobs are evicted LRU when exceeded. |
| `builder.staging-grace-ms` | `3600000` | Minimum age (ms) of staging temp files before GC removes them. |

## REST API

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/api/build` | Build an image. Body: `{"descriptionPath":"...","contextPath":"...","tag":"..."}`. |
| `GET` | `/api/images` | List built image tags. |
| `GET` | `/api/images/{tag}` | Get the manifest and image id of an image. |
| `DELETE` | `/api/images/{tag}` | Delete an image package. |
| `POST` | `/api/gc` | Run garbage collection. |
| `GET` | `/api/cache` | Cache statistics (blob count, total bytes, index size). |

## Tests

```bash
mvn test
```

The test suite covers:

- **Incremental reuse**: unchanged layers are reused; changing a context file or
  the base image rebuilds the affected and subsequent layers.
- **Concurrent builds**: identical layers are generated once with no duplicate
  writes or corruption; concurrent builds of different images all succeed.
- **Failure cleanup**: failed builds leave no staging temp files, no half-written
  layers and no image package; completed layers remain reusable.
- **Garbage collection**: unreferenced layers are collected, referenced layers
  are kept, and GC never deletes in-use layers during concurrent builds.
- **Reproducibility**: same input yields the same image id and layer bytes at
  any time and with a fresh cache; manifests and configs contain no timestamps.
