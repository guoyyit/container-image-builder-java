# 本地容器镜像分层构建与缓存服务

以本机目录作为构建上下文，依据构建描述文件产出**按层组织、内容寻址**的本地镜像包。
多次构建之间按内容复用已有层，全程离线运行，只依赖本机文件系统与常规开源组件，
不访问任何外部服务、不使用真实凭据或付费资源。

## 能力概览

- 解析构建描述文件并读取本机构建上下文，产出按层组织的镜像包，层摘要与顺序可校验。
- 基于内容的增量复用：未变化的层不会重新生成，重复构建产物逐字节一致。
- 并发构建共享同一份层缓存：相同内容的层只生成一次，无重复写入、无半成品被引用。
- 构建失败 / 取消后自动清理：不留被引用的半成品层，已完成层与元数据始终自洽。
- 缓存淘汰与垃圾回收：未被任何镜像引用的层可回收，GC 与构建并发时不会删除在用层。
- 可复现：相同输入在任何时刻得到相同镜像标识，构建时间等易变信息不进入层或标识。

## 支持的指令范围

构建描述文件为精简的 Dockerfile 风格文本（行式语法，支持空行与 `#` 注释）：

| 指令 | 形式 | 说明 |
| --- | --- | --- |
| `FROM` | `FROM <基础镜像名>` 或 `FROM scratch` | 每个描述文件有且仅有一个，且必须位于所有 `COPY` 之前。`scratch` 表示空根。基础镜像必须是本地镜像仓库中已存在的镜像。 |
| `COPY` | `COPY <源路径>... <目标路径>` | 把构建上下文中的文件 / 目录复制到镜像目标路径。支持多源（此时目标必须是目录）。源路径必须相对上下文，禁止 `..` 越界、禁止绝对路径。 |

不支持的指令（`RUN`、`ADD`、`CMD`、`ENV` 等）会在解析阶段直接报错，不会被静默忽略。

`COPY` 路径语义：

- `COPY app /app`：把目录 `app` 的内容放入镜像 `/app` 下。
- `COPY f.txt /f.txt`：单文件映射到指定目标。
- `COPY a.txt b.txt /dir/`：多个源以各自文件名放入目录 `/dir`。

## 镜像包与磁盘布局

缓存根目录（默认 `${user.dir}/.image-cache`，可配置）结构：

```
<cache-root>/
├── layers/
│   └── <chainId>/
│       ├── layer.tar      # 该层的可复现 tar 归档
│       └── diff-id        # layer.tar 的原始 SHA-256（OCI diffId）
├── images/
│   ├── registry.json      # 镜像名 -> 镜像 ID 的索引（原子写）
│   └── <imageId>.json     # 镜像清单（镜像名 / 基础层 / 层顺序 / 镜像 ID）
└── tmp/                   # 层写入临时目录，启动时自动清理残留
```

- **diffId**：层 tar 归档字节的 SHA-256。
- **chainId**：按链式规则计算，`chainId(n) = sha256(chainId(n-1) + "|copy|" + 内容摘要)`。
  内容摘要只包含规范化后的条目路径、类型、文件内容 SHA-256 与大小（**不含**文件 mtime、
  uid/gid、宿主机路径等易变信息）。因此父层变化会改变子层 chainId，但纯 mtime 变化不会。
- **imageId**：`sha256("image|" + 镜像名 + "|" + baseChainId + "|" + 各层 chainId)`。
  清单中 `createdAtEpoch` 固定为 `0`，时间戳不参与任何标识计算。

镜像清单（`ImageManifest`）记录基础层 `baseChainId` 与有序的 `layerChainIds`，
层的内容摘要与顺序均可据此逐项校验。

## 缓存与复用语义

- 层以 chainId 内容寻址。命中时直接复用，不会重新生成或覆盖归档。
- 构建前先计算每层 chainId 并探测缓存；命中计入 `cacheHit`，未命中才写层。
- 相同 chainId 的并发生成在每个 chainId 的锁内完成（双重检查），保证**只写一次**；
  其他线程复用同一结果。
- 层通过“临时文件 → 同目录原子改名 + diff-id 元数据”发布；只有 `layer.tar` 与
  `diff-id` 同时存在才算完整层，启动时会清理未完成的层目录。
- 构建中对正在使用的 chainId 加 **pin 计数**，GC 不会删除被 pin 的层。

## 失败、取消与垃圾回收

- 镜像清单只在所有层成功后一次性**原子提交**。构建失败或取消不会写入镜像引用，
  因此不会留下“被引用的半成品镜像”。
- 失败前已成功发布的层保留在内容寻址缓存中，但不被任何镜像引用，可被 GC 回收；
  后续构建仍可正常复用它们。
- `tmp/` 中的半成品在进程启动时统一清理。
- GC 删除“不被任何镜像清单引用且未被构建 pin”的层。GC 通过构建租约与互斥屏障与构建协调：
  有进行中的构建时 GC 等待，GC 进行时新构建等待，杜绝删除正在写入 / 使用的层。

## 配置项

在 `application.properties` 或通过环境 / 系统属性配置：

| 配置键 | 默认值 | 说明 |
| --- | --- | --- |
| `container-builder.cache.root-dir` | `${user.dir}/.image-cache` | 层与镜像缓存根目录。 |
| `container-builder.cache.unreferenced-grace-millis` | `0` | 未引用层宽限时间预留项（当前 GC 立即回收未引用层）。 |

服务端口沿用 Spring Boot 默认 `8080`，可用 `server.port` 覆盖。

## HTTP API

| 方法与路径 | 说明 |
| --- | --- |
| `POST /api/images/build` | 请求体 `{imageName, descriptorPath, contextDir}`，返回 `{imageId, layerChainIds, cacheHit}`。 |
| `GET  /api/images/{name}` | 查询镜像清单（基础层、层顺序、镜像 ID）。 |
| `DELETE /api/images/{name}` | 从镜像索引移除镜像名（层在下次 GC 时按引用情况回收）。 |
| `POST /api/images/gc` | 执行垃圾回收，返回删除的层数 `{removedLayers}`。 |

## 本地构建与测试

需要 JDK 17 与 Maven。

```bash
mvn test          # 运行全部自动化测试
mvn package       # 构建可执行 jar
java -jar target/container-image-builder-java-1.0-SNAPSHOT.jar
```

## 测试覆盖

- `IncrementalBuildTest`：重复构建复用、产物逐字节一致、变化层及后续层重建。
- `BaseImageTest`：基于本地基础镜像构建、基础镜像变化触发重建、缺失基础镜像报错。
- `ConcurrentBuildTest`：相同并发构建每层只生成一次、不同镜像共享相同内容层。
- `FailureAndCancellationTest`：缺源失败不提交镜像、取消不产生层、失败后缓存仍自洽、上下文越界拒绝。
- `GarbageCollectionTest`：未引用层回收、被引用层保留、GC 与活跃构建互斥。
- `ReproducibilityTest`：跨独立缓存目录 / 跨时间构建标识与 tar 字节一致，mtime 变化不影响标识。
- `LayerArchiveTest`：tar 可被标准库读取、条目有序、mtime/uid/gid 固定为 0、内容正确。
- `WebApiTest`：构建 / 查询 / GC / 删除的 HTTP 端到端行为与错误响应。
