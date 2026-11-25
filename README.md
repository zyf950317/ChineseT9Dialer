# 中文 T9 拨号器扩展（LSPosed）

目标应用：`com.android.dialer`  
分析基准：用户提供的 `电话-2900000-23.0.apk`  
模块版本：0.1.0（实验性源码）

## 目前实现方式

Hook：
`com.android.dialer.smartdial.util.SmartDialPrefix.generateNamePrefixes(Context, String)`

原方法负责生成联系人姓名的 Smart Dial 索引前缀。Hook 在原方法执行后，使用 Android ICU 将中文姓名转成拼音，并调用原方法本身（通过 `XposedBridge.invokeOriginalMethod`，避免再次触发 Hook）生成拼音索引，然后合并回原返回值。

候选形式：
- 全拼：张三 -> `zhangsan`
- 首字母：张三 -> `zs`
- 带空格拼音：张三 -> `zhang san`

原拨号器继续负责将拉丁字母/数字转换成 T9 键位，因此理论上可同时支持：
- `zhangsan`
- `zs`
- `94264726`（zhangsan 对应的标准 T9 数字）
- `942` 等前缀数字查询
- 原有英文和电话号码查询

## 重要限制

1. 这是针对该 APK 结构的实验性 Hook 源码，不是已验证可安装的成品 APK。
2. 当前 ICU 默认读音对多音字不保证覆盖所有读法；例如“单/曾/重/解”等可能需要额外多音字候选表。
3. 本次运行环境没有 Android SDK/Gradle 构建环境，无法在这里可靠产出已编译 APK。请不要把源码 ZIP 当成可安装模块。
4. `generateNamePrefixes` 的方法签名已从用户提供的 APK DEX 方法表中确认；实际运行仍需在目标 ROM/拨号器上验证索引更新、查询命中及性能。

## 构建

需要 Android Studio 或安装 Android SDK + Gradle 8.9 及兼容 JDK。
在项目目录执行：

```bash
gradle :app:assembleDebug
```

模块 APK 输出位置：

`app/build/outputs/apk/debug/app-debug.apk`

依赖：
- Android Gradle Plugin 8.7.3
- Xposed API 82（编译时依赖需在 app/build.gradle 中添加 compileOnly `de.robv.android.xposed:api:82`）

Xposed API 以 `compileOnly` 方式声明，不会被打包进模块 APK。

安装后在 LSPosed 中启用模块，作用域只勾选“电话 / com.android.dialer”，然后强制停止拨号器并清除/重建其联系人搜索索引（如该版本提供相关机制）；若未立即生效，重启设备后再测试。
