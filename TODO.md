# TODO

- [x] SQL migration 支持 PostgreSQL dollar-quoted block，例如 `DO $$ ... $$;` 和 `$tag$ ... $tag$;`，避免块内分号被拆成多条语句。
- [x] SQL migration 支持从 classpath/jar 读取脚本，生产 Coursier bootstrap 嵌套 jar 资源 URL 不能转换成本地 Path 时也应正常加载。
