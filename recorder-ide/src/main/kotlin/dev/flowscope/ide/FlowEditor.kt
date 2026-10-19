package dev.flowscope.ide

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import dev.flowscope.host.FlowSuiteParser
import dev.flowscope.host.FlowTestParser

/** Per-line state the gutter renders (design.md §5.6). */
internal enum class LineMark { None, Valid, Warning, Error }

@Composable
@ReadOnlyComposable
internal fun LineMark.color(): Color = when (this) {
    LineMark.None -> Color.Transparent
    LineMark.Valid -> SignalGreen
    LineMark.Warning -> SignalAmber
    LineMark.Error -> SignalRed
}

internal data class FlowValidation(
    val marks: List<LineMark>,
    val errorLine: Int?,
    val errorDetail: String?,
    val stepCount: Int,
) {
    val isValid: Boolean get() = errorDetail == null
}

/** Timeouts above this read as a smell worth flagging rather than an error. */
private const val LongTimeoutMillis = 60_000L

/**
 * Validates `.flow` source through the same [FlowTestParser] the CLI uses, so the editor can never
 * disagree with `flowscope test validate`. Warnings are editor-only judgements layered on top:
 * the parser accepts weak selectors and long timeouts, but the author should see them.
 */
internal fun validateFlow(source: String): FlowValidation {
    val lines = source.split("\n")
    val marks = MutableList(lines.size) { LineMark.None }

    lines.forEachIndexed { index, raw ->
        val line = raw.trim()
        if (line.isEmpty() || line.startsWith("#")) return@forEachIndexed
        marks[index] = when {
            isLifecycleLine(line) -> when (stabilityOfStep(line)) {
                SelectorStability.Low, SelectorStability.Unresolved ->
                    if (needsSelector(line)) LineMark.Warning else LineMark.Valid
                else -> LineMark.Valid
            }
            line.startsWith("timeout ") ->
                if ((line.removePrefix("timeout ").trim().toLongOrNull()
                        ?: 0L) > LongTimeoutMillis) LineMark.Warning else LineMark.Valid
            else -> LineMark.Valid
        }
    }

    return try {
        val parsed = FlowTestParser.parseSource(source, "editor.flow")
        FlowValidation(marks, null, null, parsed.steps().size)
    } catch (error: FlowTestParser.ParseError) {
        val line = error.line()
        if (line in 1..marks.size) marks[line - 1] = LineMark.Error
        FlowValidation(marks, line.takeIf { it > 0 }, error.detail(), 0)
    } catch (error: Exception) {
        FlowValidation(marks, null, error.message ?: "Could not parse this test", 0)
    }
}

/**
 * Validates `.suite` source through the same [FlowSuiteParser] the CLI uses. Returns the same
 * [FlowValidation] shape as [validateFlow] so the gutter, the validation chip, and the inline error
 * row need no branch of their own — `stepCount` carries the test count instead.
 */
internal fun validateSuite(source: String): FlowValidation {
    val lines = source.split("\n")
    val marks = MutableList(lines.size) { LineMark.None }

    lines.forEachIndexed { index, raw ->
        val line = raw.trim()
        if (line.isEmpty() || line.startsWith("#")) return@forEachIndexed
        marks[index] = LineMark.Valid
    }

    return try {
        val parsed = FlowSuiteParser.parseSource(source, "editor.suite")
        FlowValidation(marks, null, null, parsed.entries().size)
    } catch (error: FlowTestParser.ParseError) {
        val line = error.line()
        if (line in 1..marks.size) marks[line - 1] = LineMark.Error
        FlowValidation(marks, line.takeIf { it > 0 }, error.detail(), 0)
    } catch (error: Exception) {
        FlowValidation(marks, null, error.message ?: "Could not parse this suite", 0)
    }
}

/** Validates whichever format the open buffer is. */
internal fun validateSource(source: String, kind: EditorKind): FlowValidation =
    if (kind == EditorKind.Suite) validateSuite(source) else validateFlow(source)

/** Actions whose selector quality actually matters. `back`/`screenshot` carry none. */
private fun needsSelector(line: String): Boolean =
    listOf(
        "tap", "tap-if-visible", "wait-visible", "assert-visible", "assert-not-visible", "type",
        "long-press", "scroll-to", "clear-text",
    )
        .any { action -> listOf("before", "step", "after").any { line.startsWith("$it $action") } }

private fun isLifecycleLine(line: String): Boolean =
    listOf("before ", "step ", "after ").any(line::startsWith)

private val HeaderDirectives = setOf("flowscope", "name", "package", "mode", "timeout", "require")
private val StepKeywords = setOf(
    "before", "step", "after", "launch", "wait-visible", "tap", "tap-if-visible", "type",
    "scroll", "back", "long-press", "swipe", "scroll-to", "press-key", "clear-text", "hide-keyboard",
    "assert-visible", "assert-not-visible", "screenshot", "api-request",
    "set-date", "set-value", "transform", "secret",
    "clear-app-data", "force-stop", "grant-permission", "revoke-permission",
    "reset-app-permissions", "open-deep-link", "assert-deep-link-handler",
    "assert-app-link-verified", "install-apk", "uninstall-app", "assert-installed",
    "set-install-referrer", "clear-install-referrer",
    "web-wait", "web-tap", "web-type", "web-assert-visible", "web-assert-text",
    "web-assert-url", "web-screenshot", "web-assert-cookie", "web-clear-cookies",
    "web-assert-storage", "web-clear-storage", "web-assert-interface",
    "web-assert-secure-context", "web-snapshot",
)
private val SuiteHeaderDirectives = setOf("flowscope-suite", "name", "continue-on-failure", "require")
private val SuiteKeywords = setOf("test", "before", "after", "api-request", "set-date",
    "set-value", "transform", "secret")

/** The two line formats differ only in their word lists, so they share one highlighter. */
internal data class SyntaxWords(val header: Set<String>, val keywords: Set<String>)

internal val FlowWords = SyntaxWords(HeaderDirectives, StepKeywords)
internal val SuiteWords = SyntaxWords(SuiteHeaderDirectives, SuiteKeywords)

internal fun syntaxWordsFor(kind: EditorKind): SyntaxWords =
    if (kind == EditorKind.Suite) SuiteWords else FlowWords

/** design.md §5.6 — monochrome-dominant syntax colour; only values and directives take a hue. */
internal class FlowSyntax(
    private val colors: FlowColors,
    private val words: SyntaxWords = FlowWords,
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText =
        TransformedText(highlight(text.text, colors, words), OffsetMapping.Identity)
}

internal fun highlight(
    source: String,
    colors: FlowColors = DarkFlowColors,
    words: SyntaxWords = FlowWords,
): AnnotatedString = buildAnnotatedString {
    source.split("\n").forEachIndexed { index, line ->
        if (index > 0) append("\n")
        appendLine(line, colors, words)
    }
}

private fun androidx.compose.ui.text.AnnotatedString.Builder.appendLine(
    line: String,
    colors: FlowColors,
    words: SyntaxWords,
) {
    val trimmedStart = line.takeWhile { it.isWhitespace() }
    append(trimmedStart)
    val body = line.drop(trimmedStart.length)
    if (body.startsWith("#")) {
        withSpan(SpanStyle(color = colors.textLow)) { append(body) }
        return
    }
    var cursor = 0
    var firstToken = true
    while (cursor < body.length) {
        if (body[cursor].isWhitespace()) {
            append(body[cursor]); cursor++; continue
        }
        val start = cursor
        var quoted = false
        while (cursor < body.length && (quoted || !body[cursor].isWhitespace())) {
            if (body[cursor] == '"') quoted = !quoted
            cursor++
        }
        val token = body.substring(start, cursor)
        val equals = token.indexOf('=')
        when {
            equals > 0 -> {
                withSpan(SpanStyle(color = colors.textMid)) { append(token.substring(0, equals + 1)) }
                withSpan(SpanStyle(color = colors.signalIce)) { append(token.substring(equals + 1)) }
            }
            firstToken && token in words.header && token !in words.keywords ->
                withSpan(SpanStyle(color = colors.signalAmber)) { append(token) }
            token in words.keywords ->
                withSpan(SpanStyle(color = colors.textHi, fontWeight = FontWeight.Medium)) { append(token) }
            else -> withSpan(SpanStyle(color = colors.textHi)) { append(token) }
        }
        firstToken = false
    }
}

private inline fun androidx.compose.ui.text.AnnotatedString.Builder.withSpan(
    style: SpanStyle,
    block: () -> Unit,
) {
    pushStyle(style)
    block()
    pop()
}

/** A `step` line located in the source buffer (design.md §5.6 card view). */
internal data class StepLine(
    val lineIndex: Int,
    val number: Int,
    val text: String,
    val enabled: Boolean,
    val phase: String,
) {
    val body: String get() = if (enabled) text else text.removePrefix("#").trim()
}

private const val DisabledPrefix = "# "

/** Every step in the buffer, enabled or commented out, in source order. */
internal fun flowStepLines(source: String): List<StepLine> {
    var number = 0
    return source.split("\n").mapIndexedNotNull { index, raw ->
        val line = raw.trim()
        val body = line.removePrefix("#").trim()
        val phase = listOf("before", "step", "after").firstOrNull { body.startsWith("$it ") }
        val enabled = phase != null && !line.startsWith("#")
        val disabled = phase != null && line.startsWith("#")
        if (!enabled && !disabled) return@mapIndexedNotNull null
        number += 1
        StepLine(index, number, line, enabled, phase!!)
    }
}

private fun edit(source: String, block: (MutableList<String>) -> Unit): String {
    val lines = source.split("\n").toMutableList()
    block(lines)
    return lines.joinToString("\n")
}

/** Moves a step one position earlier or later, swapping with the neighbouring step line. */
internal fun moveStep(source: String, lineIndex: Int, delta: Int): String {
    val steps = flowStepLines(source)
    val position = steps.indexOfFirst { it.lineIndex == lineIndex }
    if (position < 0) return source
    val target = steps.getOrNull(position + delta)?.takeIf { it.phase == steps[position].phase }
        ?: return source
    return edit(source) { lines ->
        val moved = lines[lineIndex]
        lines[lineIndex] = lines[target.lineIndex]
        lines[target.lineIndex] = moved
    }
}

internal fun duplicateStep(source: String, lineIndex: Int): String = edit(source) { lines ->
    if (lineIndex in lines.indices) lines.add(lineIndex + 1, lines[lineIndex])
}

internal fun deleteStep(source: String, lineIndex: Int): String = edit(source) { lines ->
    if (lineIndex in lines.indices) lines.removeAt(lineIndex)
}

/** Disabling comments the step out, so the text stays in the file and round-trips. */
internal fun toggleStep(source: String, lineIndex: Int): String = edit(source) { lines ->
    val raw = lines.getOrNull(lineIndex) ?: return@edit
    val indent = raw.takeWhile { it.isWhitespace() }
    val line = raw.trim()
    lines[lineIndex] = if (line.startsWith("#")) {
        indent + line.removePrefix("#").trim()
    } else {
        indent + DisabledPrefix + line
    }
}

/** Plain-language summary of a step, for the card view. */
internal fun describeStep(stepLine: String): String {
    val lifecycle = stepLine.removePrefix("#").trim()
    val tokens = lifecycle.substringAfter(' ', "").trim()
    val action = tokens.substringBefore(' ').ifBlank { return "Empty step" }
    val selector = selectorOfStep(tokens)
    val value = Regex("""\bvalue=("[^"]*"|\S+)""").find(tokens)
        ?.groupValues?.get(1)?.trim('"')
    val direction = Regex("""\bdirection=(\S+)""").find(tokens)?.groupValues?.get(1)
    val key = Regex("""\bkey=("[^"]*"|\S+)""").find(tokens)?.groupValues?.get(1)?.trim('"')
    val target = selector?.let { readableSelector(it) }
    return when (action) {
        "launch" -> "Launch the app under test"
        "back" -> "Press the back button"
        "screenshot" -> "Capture a screenshot"
        "scroll" -> "Scroll ${direction ?: "down"}"
        "swipe" -> "Swipe ${direction ?: "up"}" + (target?.let { " from $it" } ?: "")
        "scroll-to" -> "Scroll until ${target ?: "an element"} is visible"
        "long-press" -> "Long-press ${target ?: "an element"}"
        "clear-text" -> "Clear the text in ${target ?: "a field"}"
        "press-key" -> "Press the ${key ?: "a"} key"
        "hide-keyboard" -> "Hide the keyboard"
        "tap" -> "Tap ${target ?: "an element"}"
        "tap-if-visible" -> "Tap ${target ?: "an element"} if visible"
        "type" -> "Type \"${value ?: ""}\"" + (target?.let { " into $it" } ?: "")
        "wait-visible" -> "Wait for ${target ?: "an element"}"
        "assert-visible" -> "Assert ${target ?: "an element"} is visible"
        "assert-not-visible" -> "Assert ${target ?: "an element"} is not visible"
        "api-request" -> "Call host API"
        "set-date" -> "Compute a runtime date"
        "set-value" -> "Create a runtime value"
        "transform" -> "Transform a runtime value"
        "secret" -> "Bind a secret value"
        "clear-app-data" -> "Clear target app data"
        "force-stop" -> "Force-stop the target app"
        "grant-permission" -> "Grant an app permission"
        "revoke-permission" -> "Revoke an app permission"
        "reset-app-permissions" -> "Reset target app permissions"
        "open-deep-link" -> "Open a deep link"
        "assert-deep-link-handler" -> "Assert the app handles a deep link"
        "assert-app-link-verified" -> "Assert an App Link host is verified"
        "install-apk" -> "Install an APK"
        "uninstall-app" -> "Uninstall the target app"
        "assert-installed" -> "Assert the installed app version"
        "set-install-referrer" -> "Inject a debug install referrer"
        "clear-install-referrer" -> "Clear the debug install referrer"
        "web-wait" -> "Wait for a WebView element"
        "web-tap" -> "Tap a WebView element"
        "web-type" -> "Type into a WebView element"
        "web-assert-visible" -> "Assert a WebView element is visible"
        "web-assert-text" -> "Assert WebView text"
        "web-assert-url" -> "Assert the WebView URL"
        "web-screenshot" -> "Capture a WebView screenshot"
        "web-assert-cookie" -> "Assert WebView cookie metadata"
        "web-clear-cookies" -> "Clear scoped WebView cookies"
        "web-assert-storage" -> "Assert a Web Storage entry"
        "web-clear-storage" -> "Clear scoped WebView storage"
        "web-assert-interface" -> "Assert a native JavaScript bridge"
        "web-assert-secure-context" -> "Assert WebView secure context"
        "web-snapshot" -> "Capture sanitized WebView state"
        else -> action.replaceFirstChar(Char::uppercase)
    }
}

private fun readableSelector(selector: String): String =
    selector.substringAfter('=').trim('"').ifBlank { selector }
