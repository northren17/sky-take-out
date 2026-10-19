# 本地配置与密钥

开发环境凭证与连接配置存放在：

```text
sky-server/config/application-dev-local.yml
```

该文件位于资源目录之外，不会被打进应用 JAR，并已由仓库根目录 `.gitignore` 排除。仅当激活 `dev` profile 时，[application-dev.yml](./sky-server/src/main/resources/application-dev.yml) 会从相对于当前工作目录的 `config/application-dev-local.yml` 或 `sky-server/config/application-dev-local.yml` 导入此文件；从 `backend-java` 根目录或 `sky-server` 模块目录启动均可。其他 profile 不会读取本地开发凭证。

本地文件保留开发用的数据库、Redis、RabbitMQ、对象存储和微信支付配置。还需按需设置以下根级属性：

```yaml
APP_JWT_ADMIN_SECRET_KEY: "使用随机强密钥"
APP_JWT_USER_SECRET_KEY: "使用另一条随机强密钥"
APP_BAIDU_AK: "使用你自己的百度地图密钥"
```

`APP_BAIDU_AK` 当前留空；使用地图相关功能前请填写有效值。JWT 密钥已为当前本地配置生成独立随机值，重启时会继续从此文件读取。若更换 JWT 密钥，已签发的令牌将失效。

部署环境不需要此文件，但必须通过环境变量或部署配置提供：

- `APP_JWT_ADMIN_SECRET_KEY`
- `APP_JWT_USER_SECRET_KEY`
- `APP_BAIDU_AK`
- `SKY_DATASOURCE_*`、`SKY_REDIS_*`、`SKY_RABBITMQ_*`、`SKY_ALIOSS_*`、`SKY_WECHAT_*` 对应的连接与服务凭证

避免将本地配置复制进 `src/main/resources` 或提交到版本控制。若任何曾写入 Git 历史的凭证仍有效，应在对应服务端轮换；删除工作树中的值不能清理 Git 历史。
