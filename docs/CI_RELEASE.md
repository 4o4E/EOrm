# CI 与 Release

## CI

`.github/workflows/ci.yml` 会在以下场景执行：

- push 到 `main` 或 `master`
- pull request
- 手动触发 `workflow_dispatch`

执行命令：

```bash
./gradlew --no-daemon check jacocoTestReport
```

测试报告和 JaCoCo 覆盖率报告会作为 workflow artifact 上传。

## Release

`.github/workflows/release.yml` 会在以下场景执行：

- push `v*` 标签，例如 `v1.0.0`
- 手动触发 `workflow_dispatch`

标签发布示例：

```bash
git tag v1.0.0
git push origin v1.0.0
```

Release 流程会执行：

```bash
./gradlew --no-daemon -PreleaseVersion=1.0.0 check jacocoTestReport
./gradlew --no-daemon -PreleaseVersion=1.0.0 assemble sourcesJar
./gradlew --no-daemon -PreleaseVersion=1.0.0 publish
```

## GitHub Secrets

发布 Maven 包到 Nexus 需要配置：

- `NEXUS_USERNAME`：Nexus 用户名
- `NEXUS_PASSWORD`：Nexus 密码

可选配置：

- `NEXUS_RELEASE_URL`：release 仓库地址，默认 `https://nexus.e404.top:3443/repository/maven-releases/`
- `NEXUS_SNAPSHOT_URL`：snapshot 仓库地址，默认 `https://nexus.e404.top:3443/repository/maven-snapshots/`

手动触发 release 时可以关闭 `publish`，此时只会创建 GitHub Release 和上传构建产物，不会发布 Maven 包到 Nexus。
