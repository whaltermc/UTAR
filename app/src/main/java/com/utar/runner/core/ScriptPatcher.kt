package com.utar.runner.core

/**
 * Replaces `ResourceLoader.load_threaded_request(` calls with synchronous
 * `ResourceLoader.load(` calls.
 *
 * Godot 4.1.4's `load_threaded_request()` accepts at most 4 arguments, but some
 * GDScript files pass a 5th argument, which causes a parse error and the script
 * never compiles, freezing the scene load. Replacing with the synchronous
 * `load()` avoids the issue entirely.
 *
 * Argument splitting respects nested (), [], {}, and "..." / '...' strings so that
 * commas inside nested calls or string literals are not mistaken for arg separators.
 */
object ScriptPatcher {

    private const val LOAD_THREADED_REQUEST = "load_threaded_request("

    /**
     * Replaces [load_threaded_request] calls with [load] calls in [source].
     *
     * The first argument (the pack path) is preserved; all other arguments are dropped
     * since [ResourceLoader.load()] only takes the path.
     *
     * Returns the patched source and a list of (originalCall, trimmedCall) pairs for logging.
     */
    fun trimLoadThreadedRequestArgs(source: String): Pair<String, List<Pair<String, String>>> {
        val changes = mutableListOf<Pair<String, String>>()
        val result = StringBuilder()
        var i = 0
        while (i < source.length) {
            val idx = indexOfIgnoreCase(source, i, LOAD_THREADED_REQUEST)
            if (idx < 0) {
                result.append(source.substring(i))
                break
            }
            result.append(source, i, idx)
            result.append("load(")

            // Find the matching closing paren at the top level to extract the path arg.
            val callStart = idx + LOAD_THREADED_REQUEST.length
            val closeIdx = findMatchingCloseParen(source, callStart)
            if (closeIdx < 0) {
                // Unbalanced parens; copy the rest as-is.
                result.append(source.substring(callStart))
                break
            }

            val argsText = source.substring(callStart, closeIdx)
            val args = splitTopLevelArgs(argsText)

            val pathArg = if (args.isNotEmpty()) args[0] else "\"\""
            val originalCall = "load_threaded_request($argsText)"
            val trimmedCall = "load($pathArg)"
            changes.add(originalCall to trimmedCall)
            result.append(trimmedCall)

            i = closeIdx + 1
        }
        return result.toString() to changes
    }

    /** Case-insensitive indexOf starting at [from]. */
    private fun indexOfIgnoreCase(source: String, from: Int, target: String): Int {
        val targetLower = target.lowercase()
        val sourceLower = source.lowercase()
        var pos = from
        while (pos <= source.length - target.length) {
            if (sourceLower.regionMatches(pos, targetLower, 0, target.length)) {
                return pos
            }
            pos++
        }
        return -1
    }

    /**
     * Finds the index of the matching closing parenthesis for an open paren at [openIdx],
     * respecting nested (), [], {}, and string literals ("..." / '...').
     *
     * Returns the index of the matching `)`, or -1 if not found.
     */
    private fun findMatchingCloseParen(source: String, openIdx: Int): Int {
        var depth = 1
        var i = openIdx + 1
        while (i < source.length) {
            val c = source[i]
            when {
                c == '"' || c == '\'' -> {
                    i = skipStringLiteral(source, i)
                    if (i < 0) return -1
                }
                c == '(' -> depth++
                c == ')' -> {
                    depth--
                    if (depth == 0) return i
                }
                c == '[' || c == '{' -> {
                    val close = findMatchingClose(source, i, c)
                    if (close < 0) return -1
                    i = close
                }
            }
            i++
        }
        return -1
    }

    /** Skips a string literal starting at [quoteIdx]. Returns the index of the closing quote. */
    private fun skipStringLiteral(source: String, quoteIdx: Int): Int {
        val quote = source[quoteIdx]
        var i = quoteIdx + 1
        while (i < source.length) {
            val c = source[i]
            if (c == '\\') {
                i += 2 // skip escaped char
            } else if (c == quote) {
                return i
            } else {
                i++
            }
        }
        return -1
    }

    /** Finds the matching close for an open bracket/brace at [openIdx]. */
    private fun findMatchingClose(source: String, openIdx: Int, openChar: Char): Int {
        val closeChar = when (openChar) {
            '[' -> ']'
            '{' -> '}'
            else -> return -1
        }
        var depth = 1
        var i = openIdx + 1
        while (i < source.length) {
            val c = source[i]
            when {
                c == '"' || c == '\'' -> {
                    i = skipStringLiteral(source, i)
                    if (i < 0) return -1
                }
                c == openChar -> depth++
                c == closeChar -> {
                    depth--
                    if (depth == 0) return i
                }
                c == '(' || c == '[' || c == '{' -> {
                    if (c != openChar) {
                        val innerClose = findMatchingClose(source, i, c)
                        if (innerClose < 0) return -1
                        i = innerClose
                    }
                }
            }
            i++
        }
        return -1
    }

    /**
     * Splits [args] into top-level arguments, respecting nested (), [], {},
     * and "..." / '...' strings. Commas inside nested structures or strings
     * are not treated as separators.
     */
    private fun splitTopLevelArgs(args: String): List<String> {
        val result = mutableListOf<String>()
        var depth = 0
        var current = StringBuilder()
        var i = 0
        while (i < args.length) {
            val c = args[i]
            when {
                c == '"' || c == '\'' -> {
                    current.append(c)
                    i = skipStringLiteralInArgs(args, i, current)
                    if (i < 0) {
                        result.add(current.toString())
                        return result
                    }
                    i++
                }
                c == '(' || c == '[' || c == '{' -> {
                    depth++
                    current.append(c)
                    i++
                }
                c == ')' || c == ']' || c == '}' -> {
                    depth = maxOf(0, depth - 1)
                    current.append(c)
                    i++
                }
                c == ',' && depth == 0 -> {
                    result.add(current.toString().trim())
                    current = StringBuilder()
                    i++
                }
                else -> {
                    current.append(c)
                    i++
                }
            }
        }
        if (current.isNotEmpty()) {
            result.add(current.toString().trim())
        }
        return result
    }

    /** Appends a string literal (with escape handling) to [sb], starting at [idx]. Returns the index of the closing quote. */
    private fun skipStringLiteralInArgs(args: String, idx: Int, sb: StringBuilder): Int {
        val quote = args[idx]
        var i = idx + 1
        while (i < args.length) {
            val c = args[i]
            if (c == '\\') {
                sb.append(c)
                if (i + 1 < args.length) {
                    sb.append(args[i + 1])
                    i += 2
                } else {
                    i++
                }
            } else if (c == quote) {
                sb.append(c)
                return i
            } else {
                sb.append(c)
                i++
            }
        }
        return -1
    }
}
