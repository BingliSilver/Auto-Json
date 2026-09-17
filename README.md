# Auto-Json

<p align="center">
  <img src="src/main/resources/META-INF/pluginIcon@2x.png" width="80" height="80" alt="Auto-Json Logo">
</p>

Auto-Json 是一个 IntelliJ IDEA JSON 辅助插件。它可以把代码编辑器、运行控制台或终端日志中选中的 JSON 快速格式化到右侧工具窗，也支持在工具窗中直接输入和编辑 JSON。

## 功能

- 在 IDEA 右侧提供“JSON 序列化”工具窗。
- 选中代码、运行日志或终端日志后，按 `Alt+K` 格式化到右侧工具窗。
- 支持普通 JSON Object、Array 以及基础 JSON 值。
- 支持带外层引号的转义 JSON，例如 `"{\"code\":200}"`。
- 支持日志中缺少外层引号但仍保留内部转义的 JSON，例如 `{\"code\":200}`。
- JSON key、value、花括号使用不同语义颜色展示。
- 自动识别字符串形式的子 JSON，并使用黄色波浪线标记可展开的 value。
- 光标放在带波浪线的 value 上时，可通过 IDEA“显示意图动作”快捷键选择“将子 JSON 序列化”。
- 编辑器字体、字号和主题跟随 IDEA 全局编辑器设置。
- 左侧 gutter 显示行号和折叠标记，嵌套 Object 与 Array 可以分别展开或收起。
- 输入框右键菜单提供“格式化 JSON”“一键复制”和 `Minify JSON`。
- 输入框获得焦点时可按 `Ctrl+Z`，逐步撤回输入、格式化、压缩、子 JSON 展开或 `Alt+K` 写入操作。
- 编辑器上方提供 IDEA 原生风格的 A-Z、Z-A 小图标按钮；悬停可查看功能说明，Array 元素顺序保持不变。
- 工具窗不再显示顶部操作说明或底部状态提示，无效操作不会覆盖当前结果。

## 使用方式

### 从代码或终端格式化

1. 在代码编辑器、运行控制台或终端中选中 JSON 文本。
2. 按下 `Alt+K`。
3. 插件会自动打开右侧“JSON 序列化”工具窗并显示格式化结果。

如果快捷键与本地 Keymap 冲突，可以在 IDEA 的 `Settings | Keymap` 中搜索“格式化 JSON 到侧边栏”进行修改。

### 在工具窗中格式化

1. 打开右侧“JSON 序列化”工具窗。
2. 在编辑区域粘贴或输入 JSON。
3. 在输入框中点击右键，选择“格式化 JSON”。
4. 需要复制完整结果时，在右键菜单中选择“一键复制”。

右键菜单中的 `Minify JSON` 可以移除缩进和换行，将当前内容转换为单行 JSON。

使用编辑器上方的 A-Z 或 Z-A 小图标按钮，可以递归排序每一层 Object 的同级 key。鼠标移到按钮上会显示对应方向说明；排序后的结果会自动格式化并写回输入框，Array 的元素位置不会被调整。需要恢复排序前内容时可按 `Ctrl+Z`。

### 展开字符串子 JSON

当某个字符串 value 解码后是合法的 JSON Object 或 Array 时，插件会为整个 value 显示黄色波浪线。把光标放入该 value，按 IDEA 的“显示意图动作”快捷键（默认 `Alt+Enter`），再选择“将子 JSON 序列化”，该字符串就会转换为真正的嵌套结构，并自动重新格式化完整 JSON。

例如：

```json
{
  "data": {
    "content": "{\"code\":200,\"message\":\"success\"}"
  }
}
```

执行后：

```json
{
  "data": {
    "content": {
      "code": 200,
      "message": "success"
    }
  }
}
```

### 转义 JSON 示例

以下两种输入都可以格式化：

```text
"{\"code\":200,\"message\":\"success\",\"data\":{\"id\":1001,\"name\":\"Tony\",\"roles\":[\"admin\",\"user\"]}}"

{\"code\":200,\"message\":\"success\",\"data\":{\"id\":1001,\"name\":\"Tony\",\"roles\":[\"admin\",\"user\"]}}
```

输出：

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "id": 1001,
    "name": "Tony",
    "roles": [
      "admin",
      "user"
    ]
  }
}
```

## Object 与集合折叠

格式化后的多行 Object 和 Array 会在编辑区域左侧显示折叠标记。Object 折叠后显示为 `{…}`，Array 会显示顶层元素数量，例如 5 个元素折叠后显示为 `[...,5个]`；再次点击即可恢复。嵌套结构或字符串中的逗号不会影响集合数量，字符串 value 中出现的 `{`、`}`、`[` 或 `]` 也不会被误识别为折叠范围。

## 开发环境

- JDK 21
- Gradle Wrapper 9.6.1
- Kotlin 2.3.20
- IntelliJ Platform Gradle Plugin 2.18.1
- 编译基准 IDE：IntelliJ IDEA 2025.3.5（Build `253.33514.17`）
- 发布兼容范围：IntelliJ IDEA 2025.3.5 至 2026.2.x（包含 2026.2.1 Build `262.9437.185`）

## 本地运行

Windows：

```powershell
$env:JAVA_HOME = "C:\Program Files\Java\jdk-21"
.\gradlew.bat runIde
```

macOS / Linux：

```bash
./gradlew runIde
```

## 构建插件

Windows：

```powershell
.\gradlew.bat buildPlugin
```

macOS / Linux：

```bash
./gradlew buildPlugin
```

构建产物位于 `build/distributions/`。

## 项目结构

```text
src/main/kotlin/
├── FormatJsonAction.kt          Alt+K 动作与编辑器/终端选区读取
├── FormatCurrentJsonAction.kt   输入框右键格式化动作
├── CopyJsonAction.kt            输入框右键一键复制动作
├── JsonFormatter.kt             普通 JSON 与转义 JSON 解析、格式化
├── JsonToolWindowFactory.kt     右侧工具窗入口
├── JsonToolWindowService.kt     JSON 编辑器、语法配色、结构折叠与一键复制
├── MinifyJsonAction.kt          结果框右键单行压缩动作
└── MyMessageBundle.kt           国际化消息读取

src/main/resources/
├── META-INF/plugin.xml          插件、工具窗和快捷键注册
├── META-INF/pluginIcon*.png     插件 Logo
├── icons/autoJson*.png          工具窗 Logo
└── messages/                    界面文本
```

## 主要配置

- Gradle Group：`com.LazeroX`
- 插件 ID：`com.LazeroX.Auto-Json`
- 插件名称：`Auto-Json`
- 工具窗 ID：`JsonAssistant`
- 默认快捷键：`Alt+K`
