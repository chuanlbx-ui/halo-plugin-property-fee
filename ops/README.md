# 物业费插件 · 升级运维手册

三个脚本，覆盖"升级前 → 升级 → 出问题回滚"全流程。**只需记三条命令。**

## 一、日常升级（最常用）

```bash
cd ~/halo-plugin-workspace/halo-plugin-property-fee
./gradlew build -x test          # 1. 构建
bash ops/deploy.sh               # 2. 自检 → 备份 → 升级 → 等 STARTED → 冒烟 12 项
```

`deploy.sh` 做的事（任何一步失败就中止，不会把线上留在半截）：

| 步骤 | 内容 |
|---|---|
| ① 自检 | `ops/preflight.sh` 共 10 项：jar 存在/版本一致/控制台产物齐全/**控制台样式已内联**/前台页已打包/生产插件 STARTED/前后 jar 对比/前台页 200/代理页 200/后台接口 200 |
| ② 备份 | 把生产现有 jar 复制到 `data/plugins/_backup/`（`cp -n`，不覆盖历史版本） |
| ③ 升级 | `halo plugin upgrade property-fee --profile wenbita --file <jar> -y`（**必须走 CLI**，直接换 jar 会因 `loadLocation` 写死文件名而 404） |
| ④ 等状态 | 轮询到 `STARTED`（实测 10 秒内），出现 `FAILED` 立即中止 |
| ⑤ 冒烟 | `ops/smoke.sh` 12 项：前台页/代理页/选项接口/后台 7 个接口/业主接口鉴权/绑定校验 |
| ⑥ 报告 | 全绿输出 ✅，并打印回滚命令 |

## 二、出问题回滚

```bash
bash ops/rollback.sh 1.1.0              # 从生产 _backup/ 取指定版本
bash ops/rollback.sh /path/to/x.jar     # 或用本地 jar
```

**实测结论：Halo 接受安装更低版本的 jar（降级可用）**，1.2.0 → 1.1.0 回滚 10 秒完成、冒烟通过。
备份目录里的历史版本可用 `bash ops/rollback.sh`（不带参数）列出。

## 三、只做体检（不改动任何东西）

```bash
bash ops/smoke.sh        # 12 项接口/页面冒烟
bash ops/preflight.sh    # 10 项升级前自检
```

## 关键坑位（踩过的）

1. **Halo 控制台只加载 `console/main.js`，不加载 `console/style.css`** → 视图里禁用 `<style scoped>`（会被抽成孤儿文件，页面裸奔）；样式统一写 `ui/src/assets/plugin.css`，用 `?inline` 字符串注入。
2. **升级有 30~60 秒重启窗口**，期间控制台页面空白/无样式，属正常，刷新即恢复（`deploy.sh` 会等到 STARTED 再冒烟）。
3. **必须用 CLI 升级**：现有 `extensions` 表里 `loadLocation` 写死了 `property-fee-1.0.0.jar`，手工替换 jar 会 404。
4. **响应式链里禁止 `.block()`**：Netty 事件循环上会抛异常，包在 try/catch 里会被静默吞掉 → 表现为"操作失败"且无日志（`wxBind` 绑定失败 bug 就是这么来的）。
5. **密钥只存服务器侧**：`data/property-fee/master.key`（0600）用于 AES-GCM 加密商户私钥/APIv3/短信 SecretKey/公众号 AppSecret，接口只回"已配置"，不回显明文。

## 版本记录（生产实测）

| 版本 | 内容 | 状态 |
|---|---|---|
| 1.1.0 | 应用市场上架审核 8 条整改；支付回调入账 11 用例验证；密钥加密存储 | 备份中，可回滚 |
| 1.2.0 | 前台改「登录即见名下房屋」（防同行寻价）、微信免验证码绑定、后台批量导入、JSAPI 支付链路修复、绑定写入 bug 修复 | **当前生产版本** |
