package cn.uliang

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import java.util.Locale

/**
 * JSON 格式化结果。
 *
 * 使用显式结果类型区分成功与失败，可以把用户输入错误当作正常交互处理，
 * 避免用异常控制工具窗和快捷键动作的业务流程。
 */
internal sealed interface JsonFormatResult {
    /** JSON 已成功解析并完成美化。 */
    data class Success(val formattedJson: String) : JsonFormatResult

    /** JSON 无法解析，message 用于直接反馈给用户。 */
    data class Failure(val message: String) : JsonFormatResult
}

/**
 * 提供无状态的 JSON 格式化能力，供工具窗按钮和快捷键动作共同复用。
 */
internal object JsonFormatter {
    // 复用线程安全的 Gson 实例，避免每次按快捷键都重复创建格式化器。
    private val prettyGson = GsonBuilder()
        .setPrettyPrinting()
        // 不转义中文及常见 HTML 字符，让工具窗中的结果更适合直接阅读和复制。
        .disableHtmlEscaping()
        .create()

    // 单行输出与格式化输出采用相同的字符转义策略，仅关闭缩进和换行。
    private val compactGson = GsonBuilder()
        .disableHtmlEscaping()
        .create()

    /**
     * 将用户提供的 JSON 文本转换为带缩进和换行的标准 JSON。
     *
     * @param sourceJson 工具窗输入内容或编辑器当前选中的 JSON 文本
     * @return 成功时返回格式化后的 JSON，失败时返回适合展示的错误信息
     */
    fun format(sourceJson: String): JsonFormatResult {
        return serializeJson(sourceJson, prettyGson)
    }

    /**
     * 将用户提供的 JSON 文本压缩为不带缩进和换行的单行 JSON。
     *
     * @param sourceJson 工具窗中的普通 JSON 或转义 JSON 文本
     * @return 成功时返回单行 JSON，失败时返回适合展示的错误信息
     */
    fun minify(sourceJson: String): JsonFormatResult {
        return serializeJson(sourceJson, compactGson)
    }

    /**
     * 递归排序 JSON 中每一层 Object 的同级 key，并输出格式化后的完整 JSON。
     *
     * Array 自身的元素顺序不会改变，但其中包含的 Object 仍会递归排序。比较 key 时忽略
     * 英文字母大小写，再用原始 key 保证结果稳定，从而更符合用户对 A-Z/Z-A 的直观预期。
     *
     * @param sourceJson 普通 JSON 或受支持的转义 JSON 文本
     * @param ascending true 表示 A-Z 升序，false 表示 Z-A 降序
     * @return 成功时返回排序并格式化的 JSON，失败时返回原有语法错误信息
     */
    fun sortKeys(sourceJson: String, ascending: Boolean): JsonFormatResult {
        if (sourceJson.isBlank()) {
            return JsonFormatResult.Failure(MyMessageBundle.message("json.format.error.empty"))
        }

        return try {
            // 复用现有解析入口，确保排序同样支持普通 JSON 和日志中的转义 JSON。
            val jsonElement = parseJsonElement(sourceJson)
            val ascendingComparator = compareBy<String>(
                { key -> key.lowercase(Locale.ROOT) },
                { key -> key }
            )
            val keyComparator = if (ascending) {
                ascendingComparator
            } else {
                ascendingComparator.reversed()
            }
            val sortedElement = sortJsonKeysRecursively(jsonElement, keyComparator)
            // 排序按钮要求直接输出可阅读结果，因此始终使用带缩进的格式化器。
            JsonFormatResult.Success(prettyGson.toJson(sortedElement))
        } catch (exception: JsonParseException) {
            // 排序失败不得覆盖输入框中的旧内容，错误结果交由调用方决定是否展示。
            JsonFormatResult.Failure(
                MyMessageBundle.message(
                    "json.format.error.invalid",
                    exception.message ?: MyMessageBundle.message("json.format.error.unknown")
                )
            )
        }
    }

    /**
     * 复制 JSON 树并递归排序其中所有 Object 的 key。
     *
     * @param jsonElement 当前递归层级的 JSON 节点
     * @param keyComparator 当前按钮对应的 key 排序方向
     * @return Object key 已排序、Array 元素位置保持不变的新 JSON 节点
     */
    private fun sortJsonKeysRecursively(
        jsonElement: JsonElement,
        keyComparator: Comparator<String>
    ): JsonElement {
        return when {
            jsonElement.isJsonObject -> {
                val sortedObject = JsonObject()
                // 只比较当前 Object 的直接属性，因此每一层都保持独立的“同级排序”语义。
                jsonElement.asJsonObject.entrySet()
                    .sortedWith(
                        Comparator { leftEntry, rightEntry ->
                            keyComparator.compare(leftEntry.key, rightEntry.key)
                        }
                    )
                    .forEach { entry ->
                        // value 继续递归，保证嵌套 Object 以及 Array 内 Object 也执行相同排序。
                        sortedObject.add(
                            entry.key,
                            sortJsonKeysRecursively(entry.value, keyComparator)
                        )
                    }
                sortedObject
            }

            jsonElement.isJsonArray -> {
                val sortedArray = JsonArray()
                jsonElement.asJsonArray.forEach { arrayElement ->
                    // 按原遍历顺序添加元素，排序 key 时绝不改变集合业务顺序。
                    sortedArray.add(sortJsonKeysRecursively(arrayElement, keyComparator))
                }
                sortedArray
            }

            // JsonPrimitive 与 JsonNull 不包含 key，可安全复用原节点且不会改变值。
            else -> jsonElement
        }
    }

    /**
     * 尝试把一个完整的 JSON 字符串 token 解码为 Object 或 Array 文本。
     *
     * 该方法只服务于工具窗中的“字符串子 JSON”识别。例如传入
     * `"{\"code\":200}"` 时返回 `{"code":200}`；普通字符串、基础类型和非法 JSON
     * 均返回 null，避免把业务文本误提示为可展开结构。
     *
     * @param jsonStringToken 包含起止双引号的完整 JSON 字符串 token
     * @return 规范化后的单行 Object 或 Array JSON；无法展开时返回 null
     */
    fun decodeNestedContainerString(jsonStringToken: String): String? {
        return try {
            // 第一层必须是字符串，防止调用方误把已经展开的 Object 或 Array 再次处理。
            val stringElement = JsonParser.parseString(jsonStringToken)
            if (!stringElement.isJsonPrimitive || !stringElement.asJsonPrimitive.isString) {
                return null
            }

            val decodedText = stringElement.asString.trim()
            // 先检查首字符可以快速排除绝大多数普通业务字符串，也限定功能只处理容器结构。
            if (!decodedText.startsWith("{") && !decodedText.startsWith("[")) {
                return null
            }

            val nestedElement = JsonParser.parseString(decodedText)
            if (!nestedElement.isJsonObject && !nestedElement.isJsonArray) {
                return null
            }

            // 使用单行形式作为替换片段；完整文档替换成功后会再统一执行美化格式化。
            compactGson.toJson(nestedElement)
        } catch (_: JsonParseException) {
            // 用户编辑过程中的不完整转义字符串属于正常状态，不记录异常日志也不显示误导性提示。
            null
        } catch (_: IllegalStateException) {
            // Gson 类型读取失败同样表示当前 token 不符合可展开字符串约束。
            null
        }
    }

    /**
     * 使用指定 Gson 输出配置解析并序列化 JSON。
     *
     * @param sourceJson 普通 JSON 或转义 JSON 文本
     * @param gson 控制美化输出或单行输出的 Gson 实例
     * @return 统一的 JSON 处理结果
     */
    private fun serializeJson(sourceJson: String, gson: Gson): JsonFormatResult {
        // 空白内容不具备可格式化语义，单独提示可以避免显示晦涩的解析异常。
        if (sourceJson.isBlank()) {
            return JsonFormatResult.Failure(MyMessageBundle.message("json.format.error.empty"))
        }

        return try {
            // 先解析并按需解除一层字符串转义，兼容日志中常见的 JSON 字符串形式。
            val jsonElement = parseJsonElement(sourceJson)
            // 解析入口完全一致，只由传入的 Gson 决定最终是否保留缩进和换行。
            JsonFormatResult.Success(gson.toJson(jsonElement))
        } catch (exception: JsonParseException) {
            // 语法错误属于可预期的用户输入，不打印异常堆栈，直接给出可读反馈。
            JsonFormatResult.Failure(
                MyMessageBundle.message(
                    "json.format.error.invalid",
                    exception.message ?: MyMessageBundle.message("json.format.error.unknown")
                )
            )
        }
    }

    /**
     * 解析普通 JSON，或解析被编码在 JSON 字符串中的对象和数组。
     *
     * 例如输入 `"{\"code\":200}"` 时，第一次解析得到字符串，第二次解析该字符串内容后
     * 得到真正的 JSON 对象。普通字符串不会进入第二次解析，避免改变其原有语义。
     *
     * @param sourceJson 原始 JSON 或带外层引号和反斜杠转义的 JSON 字符串
     * @return 可直接交给 Gson 美化输出的 JSON 元素
     * @throws JsonParseException 任意一层存在 JSON 语法错误时抛出
     */
    private fun parseJsonElement(sourceJson: String): JsonElement {
        val normalizedJson = sourceJson.trim()
        // 优先按标准 JSON 解析，避免回退逻辑改变普通 JSON 的任何合法转义语义。
        val parsedElement = try {
            JsonParser.parseString(normalizedJson)
        } catch (exception: JsonParseException) {
            // 日志有时会移除字符串最外层引号但保留内部转义，此时受限地解除一层转义。
            return parseEscapedContainerWithoutOuterQuotes(normalizedJson, exception)
        }
        if (!parsedElement.isJsonPrimitive || !parsedElement.asJsonPrimitive.isString) {
            return parsedElement
        }

        val decodedText = parsedElement.asString.trim()
        // 仅对象和数组需要解除外层字符串编码；普通字符串继续按普通 JSON 字符串输出。
        if (!decodedText.startsWith("{") && !decodedText.startsWith("[")) {
            return parsedElement
        }

        // 第二次解析把已经解除转义的文本转换为对象或数组，以便正常缩进和换行。
        return JsonParser.parseString(decodedText)
    }

    /**
     * 解析缺少外层引号、但内部双引号仍以 `\"` 转义的 Object 或 Array。
     *
     * 例如 `{\"code\":200}` 会先临时包装成合法 JSON 字符串，解除转义得到
     * `{"code":200}`，再解析为真正的 JSON Object。
     *
     * @param escapedJson 缺少外层引号的转义 JSON 文本
     * @param originalException 标准 JSON 解析阶段产生的原始异常
     * @return 解除转义后解析得到的 JSON Object 或 Array
     * @throws JsonParseException 输入不符合该回退形式或解除转义后仍不是合法 JSON 时抛出
     */
    private fun parseEscapedContainerWithoutOuterQuotes(
        escapedJson: String,
        originalException: JsonParseException
    ): JsonElement {
        // 只允许 Object 或 Array 外形进入回退，普通非法文本继续返回原始解析错误。
        val isObject = escapedJson.startsWith("{") && escapedJson.endsWith("}")
        val isArray = escapedJson.startsWith("[") && escapedJson.endsWith("]")
        if (!isObject && !isArray) {
            throw originalException
        }

        // 补回日志中丢失的字符串外层引号，让 Gson 安全处理 \"、\\、\n 等转义序列。
        val wrappedJsonString = "\"$escapedJson\""
        val decodedJson = JsonParser.parseString(wrappedJsonString).asString
        return JsonParser.parseString(decodedJson)
    }
}
