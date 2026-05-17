# CI 与 Release

## CI

`.github/workflows/ci.yml` 会在以下场景执行：

- push 到 `main` 或 `master`
- pull request
- 手动触发 `workflow_dispatch`

执行命令：

```bash
./gradlew --no-daemon --parallel --build-cache --configuration-cache check
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
./gradlew --no-daemon --parallel --build-cache -PreleaseVersion=1.0.0 check publish
```

## GitHub Packages

Maven 包发布到 GitHub Packages。默认发布地址由 `GITHUB_REPOSITORY` 自动推导：

```text
https://maven.pkg.github.com/4o4E/EOrm
```

Release workflow 使用 GitHub Actions 自动提供的 `GITHUB_TOKEN` 发布同仓库 package，不需要额外配置包发布 Secret。

可选覆盖配置：

- `github.repository` / `GITHUB_REPOSITORY`：仓库名，格式为 `owner/repo`
- `github.packages.url` / `GITHUB_PACKAGES_URL`：GitHub Packages Maven 仓库地址
- `github.packages.username` / `GITHUB_ACTOR`：发布用户名
- `github.packages.token` / `GITHUB_TOKEN`：发布 token

手动触发 release 时可以关闭 `publish`，此时只会创建 GitHub Release 和上传构建产物，不会发布 Maven 包到 GitHub Packages。
