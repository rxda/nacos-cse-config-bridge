# 下一步

## 当前状态

- 已完成 CSE Service Center 面板注册；直接运行 Spring Boot 时默认关闭，Docker Compose 因自带
  Service Center 而默认启用。关闭时不访问 Service Center。
- 已从 Nacos 2.x `ConnectionSetupRequest` 读取 `AppName`、namespace/tenant 和 gRPC 远端 IP。
- 已确认并修复测试编译问题：gRPC 测试改用 `BlockingQueue` 并补齐 `KieConfigStore` 包名。
- 已移除 Service Center 单测对 Mockito 调度器 mock 的依赖，改为普通 fake，JDK 25 下可直接执行。
- 已根据 Nacos 2.5.2 官方源码确认：标准标签收集器只允许字母、数字、`_`、`-`、`.`，会过滤包含
  `://`、`:`、`/` 的完整 endpoint 标签值。
- 已确认 Nacos 2.5.2 会给 `nacos.app.conn.labels` 的键添加 `app_` 前缀；代理同时兼容原始键和
  `app_serviceHost`、`app_servicePort`、`app_serviceProtocol`。
- 已增加 `serviceHost` 标签，完整 endpoint 标签默认关闭；无业务端口时只注册微服务，绝不使用
  gRPC 临时源端口。
- 已增加/调整单测，覆盖 app 前缀标签、默认忽略完整 endpoint、禁用注册零访问和同 endpoint 引用计数。
- 已执行 `./mvnw test-compile`，主代码和全部测试代码编译通过。
- 已将 `ConfigBatchListen` 改为立即 `checkNow` 并立即返回，不再阻塞 KIE 长轮询；对未变化 key 启动
  后台 KIE watch，revision/MD5 变化后通过 BiStream 主动发送 `ConfigChangeNotifyRequest`，以支持
  `@NacosValue(autoRefreshed = true)`。
- 已增加真实 Nacos 2.5.2 客户端测试 `realNacosConfigClientListenerReceivesActiveServerPush`，
  验证注册不阻塞、后台 watch 启动且客户端 listener 收到服务端主动推送。
- 已修复连接注册地址、BiStream 异常、watch 替换、注册关闭和同地址新 stream 等竞态/泄漏问题。
- 已补充 README 的 Service Center 开关、地址、namespace 映射、标签规则和 K8s 发现边界。
- 已修复代理 JAR 被 `.dockerignore` 排除的问题：改为宿主机 Maven 打包，Dockerfile 单阶段复制指定 JAR。
- 已执行 `docker compose config --quiet`，退出码为 0。
- 已显式加入 `com.google.guava:failureaccess`：ServiceComb 的依赖 BOM 会排除 Guava 的全部传递依赖，
  否则运行时会因缺少 `InternalFutureFailureAccess` 启动失败。
- 已给 `ServiceCenterRegistrationService` 的生产构造器增加 `@Autowired`，避免存在测试构造器时
  Spring Boot 找不到默认构造器。
- 已执行 `./mvnw -DskipTests clean package`，可执行 JAR 包含
  `BOOT-INF/lib/failureaccess-1.0.3.jar`。
- Compose 已默认设置 `CSE_SERVICE_CENTER_ENABLED=true`；微服务列表需要 `ConnectionSetup` 携带
  `AppName`，实例列表还必须携带 `servicePort`，缺少业务端口时只注册微服务、不伪造实例。
- 已执行完整 `./mvnw test`：21 个测试通过、0 失败、0 错误、1 个默认跳过；唯一跳过是需显式开启的
  外部对比测试 `NacosConfigReadComparisonTest`。
- 已用最终源码重新执行 `./mvnw -DskipTests clean package` 并执行
  `docker compose up -d --build`；三个容器均为 `Up`，代理 `/actuator/health` 返回
  `{"status":"UP"}`，启动日志确认 Service Center 注册已启用。
- 已执行 `docker compose config --quiet` 和 `git diff --check`，均通过。
- 配置迁移接口现在支持不传 `configs` 自动分页迁移指定 namespace 的全部配置。
- 已增加 `allNamespaces` 请求选项；为 `true` 时查询并迁移源 Nacos 所有 namespace，
  同时忽略 `sourceNamespace`，且拒绝非空 `configs`。
- 已为全 namespace、单 namespace 全量分页、显式配置和参数冲突补充
  `NacosMigrationServiceTest`；迁移定向测试 4 个全部通过。
- 迁移服务增加测试构造器后，生产构造器同样补充 `@Autowired`，避免 Spring Boot 因存在两个构造器
  而报 `No default constructor found`。
- 已定位源 Nacos 的 `user not found!` 403：`/nacos/v1/console/namespaces` 可匿名访问，但
  `/nacos/v1/cs/configs` 开启鉴权后要求登录响应里的 `accessToken`；空 token 会报
  `user not found!`，错误值会报 `token invalid!`。
- 已在 README 补充 `/nacos/v1/auth/login` 获取 token 的完整命令和字段位置。
- 已使用有效 token 实际调用 `allNamespaces=true`：public 和 dwyzt 两个 namespace 共 2 个配置，
  `success=2`、`failed=0`。

## 待处理

1. 使用运行中的 Compose 环境做端到端验证：
   - `@NacosValue(autoRefreshed = true)` 在 CSE KIE 修改后收到 `[server-push] config changed`
     并刷新变量。
   - CSE 面板能够显示微服务；客户端提供 `servicePort` 后能够显示并保持实例。
   - gRPC 断开后实例注销，同一 endpoint 的多个连接不会产生重复实例。
2. 若业务实例仍为空，按 README 的排查顺序确认客户端 `AppName`、`servicePort` 标签以及代理日志中的
   `Registered CSE microservice`。

## 验收标准

- `./mvnw test` 全部通过。
- `docker compose config --quiet` 成功（已通过）。
- 注册功能关闭时完全不访问 Service Center（已有单测）。
- 不把 gRPC 临时源端口当作业务端口（已有单测）。
- 业务服务发现继续基于 K8s，CSE 注册不影响流量路由。
