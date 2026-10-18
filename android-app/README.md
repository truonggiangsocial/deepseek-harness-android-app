# android-app/ —— APK 构建工程

把 DSH 内核 + node 运行时 + 移动端适配打包成可直接安装的 Android APK（`DeepSeekHarness.apk`）。

## 目录结构

```
android-app/
├── build.sh              一键打包脚本（7 步：组装 payload → aapt → javac → d8 → 打包 → zipalign → 签名）
├── env.sh                编译工具链环境（可 export PREFIX 覆盖工具链位置）
├── AndroidManifest.xml   包名/targetSdk(28)/自由旋转/Shizuku 声明/版本号（versionCode/versionName）
├── libs/                 Shizuku 官方 aar（api/provider/aidl）
├── res/                  图标 + 字符串资源
├── sdk/                  放 platform android.jar（见 sdk/README.md，不入仓库）
├── src/.../MainActivity.java  Android 原生壳（权限引导页/加载页/引擎启动/看门狗）
└── release.jks           签名密钥（⚠️ 不入仓库，仅本机构建用）
```

## 构建环境（必备）

构建需要**完整开发环境**（不在本仓库内，见 `docs/开发指南.md` 第三节）：

- `runtime/` —— node v26 运行时（bionic 版：`bin/node` + `lib/*.so`）
- `dshroot/` —— DSH 内核（`@deepseek-ai/dsh`，含 Android 补丁）
- `build/` —— 编译工具链（aapt / javac / d8 / zipalign / apksigner）
- `.dsh/` —— DSH 配置（cordis.patch.yml / settings.yaml / profiles/web）
- `release.jks` —— 签名密钥

统一通过 `DSH_DEV_HOME` 指向（结构见 `docs/开发指南.md`）。App 内实例：`/data/user/0/com.deepseek.harness/files/buildenv/devhome`。

## 打包命令

```sh
export DSH_DEV_HOME=<devhome 路径>   # 含 runtime/dshroot/build/.dsh/rish
export JAVA_BIN=<java-17/bin 路径>   # javac 所在目录
export ANDROID_JAR=<android.jar 路径>
export KEYSTORE_PASS=<签名密码>
export KEYSTORE_ALIAS=dsh
sh build.sh
# 产物：android-app/DeepSeekHarness.apk
```

## GitHub Actions

`.github/workflows/build-apk.yml` 会在 `main` 分支的 APK 相关文件变更时构建，也可从 Actions 页面手动运行。构建使用 GitHub 托管的 Ubuntu runner；仓库不包含约 118 MB 的开发环境，因此需在仓库 **Settings → Secrets and variables → Actions** 配置：

- `DSH_DEV_HOME_URL`：可信 HTTPS 地址，直接返回 `.tar.gz` 开发环境归档。归档根目录应直接包含 `runtime/`、`dshroot/`、`.dsh/` 和 `rish/`，不要再套一层目录；至少提供 `.dsh` 下构建所需的配置文件。不得包含 API 凭证或 `.credentials.yaml`。
- `DSH_DEV_HOME_TOKEN`：可选的 Bearer token；公开或预签名下载地址可不设置。
- `ANDROID_KEYSTORE_BASE64`：`release.jks` 的单行 Base64 内容。
- `KEYSTORE_PASS`：签名密钥口令。
- `KEYSTORE_ALIAS`：可选；未设置时默认为 `dsh`。

构建成功后，在该次 workflow run 的 **Artifacts** 中下载 `DeepSeekHarness-<run number>`。APK 有效期为 14 天；签名密钥只在 runner 临时写入，且不会作为 artifact 上传。

## build.sh 关键点

- **第 0 步**自动注入移动端适配（`sh ../mobile-patch/inject.sh`），payload 随 APK 打包
- **安全检查**：payload 里发现 `sk-` 密钥或 `.credentials.yaml` 立即中止
- **防坏包**：javac 失败立即中止（不再吞错）；`classes.dex` 必须含 `MainActivity` 才放行（v1.2.0 曾因缺校验产出安装即闪退的坏包）
- **soname 实体化**：按 `LINKS.txt` 把版本化 .so 复制成同名实体文件（jar 打包会压平软链，否则 node 起不来）
- **版本标记**：`dshroot_revision.txt`（assets）用于 App 判断外部 `/sdcard/DeepSeekHarness/dshroot` 是否需要补齐

## 版本号修改

改 `AndroidManifest.xml` 的 `android:versionCode` / `android:versionName` 后重新打包（小改动可走快速重打包：`jar uf` 就地更新 + zipalign + 重签）。

## 注意

- ⚠️ **targetSdk 必须保持 28**（≥29 时 Android 把私有目录挂 noexec，node 起不来）
- `release.jks` 与密码**绝不提交仓库**（.gitignore 已排除）
- 安装包自测方法（防 ERR_CONNECTION_REFUSED）：抽出 payload 实测引擎 HTTP 200，见 `docs/开发指南.md` / `交接文档.md`
