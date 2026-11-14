package com.utar.runner.core

/**
 * Finds and fixes known GDScript API mismatches inside game scripts at install time.
 *
 * Each rule describes an engine function whose argument list the exported game overfills —
 * a parse error on the target Godot build that keeps the script from ever compiling (the
 * `load_threaded_request` 5-argument issue). A call taking more arguments than the rule
 * allows is trimmed back to the documented maximum, keeping the call itself intact; calls
 * that already fit are left byte-for-byte untouched. Every applied fix is reported so the
 * install log shows exactly what changed.
 *
 * Argument splitting respects nested (), [], {}, and "..." / '...' strings so that commas
 * inside nested calls or string literals are not mistaken for arg separators.
 */
object ScriptPatcher {

    /**
     * One known script issue: [needle] is matched case-insensitively; over-argued calls get
     * trimmed to [maxArgs]. [forceArgs] rewrites arguments of the trimmed call, mapping an
     * argument index to its replacement value (e.g. switching a threaded load to distribute
     * its dependency loads across the worker pool).
     */
    private data class Rule(
        val needle: String,
        val maxArgs: Int,
        val reason: String,
        val forceArgs: Map<Int, String> = emptyMap(),
    )

    private val rules = listOf(
        // Godot 4.1.4's load_threaded_request(path, type_hint, use_sub_threads, cache_mode)
        // accepts at most 4 arguments; a 5th is a parse error and freezes the scene load.
        // The game's scripts come from a Godot 4.2+ codebase whose 5th argument asks for a
        // fully background load. On 4.1.4 the trimmed call also keeps use_sub_threads=false,
        // so every dependency of the requested scene is loaded sequentially on a single
        // worker thread — the main thread blocks in load_threaded_get for minutes on mobile
        // storage, freezing the engine (audio, input, render) until the load happens to
        // finish. use_sub_threads=true distributes the dependency loads across the worker
        // pool, which is the closest 4.1.4 behaviour to what the game asked for.
        Rule(
            "load_threaded_request(",
            4,
            "load_threaded_request takes at most 4 arguments",
            forceArgs = mapOf(2 to "true"),
        ),
    )

    /** A single applied fix, reported for the install log. */
    data class Fix(val rule: String, val original: String, val patched: String)

    /** Rewrites [source], fixing every known issue. Returns the fixed source plus applied fixes. */
    fun fix(source: String): Pair<String, List<Fix>> {
        val fixes = mutableListOf<Fix>()
        if (source.isEmpty()) return source to fixes
        val sourceLower = source.lowercase()
        val needlesLower = rules.map { it.needle.lowercase() }

        val result = StringBuilder(source.length)
        var i = 0
        while (i < source.length) {
            var bestIdx = -1
            var bestRuleIndex = -1
            for ((index, needleLower) in needlesLower.withIndex()) {
                val idx = indexOfIgnoreCase(sourceLower, i, needleLower)
                if (idx >= 0 && (bestIdx < 0 || idx < bestIdx)) {
                    bestIdx = idx
                    bestRuleIndex = index
                }
            }
            val rule = rules.getOrNull(bestRuleIndex)
            if (rule == null || bestIdx < 0) {
                result.append(source.substring(i))
                break
            }

            result.append(source, i, bestIdx)
            // The needle ends with '(' — the scan starts from the paren itself, the
            // arguments from the character after it.
            val parenIdx = bestIdx + rule.needle.length - 1
            val argsStart = parenIdx + 1
            val closeIdx = findMatchingCloseParen(source, parenIdx)
            if (closeIdx < 0) {
                // Unbalanced parens; copy the rest as-is.
                result.append(source.substring(bestIdx))
                break
            }

            val args = splitTopLevelArgs(source.substring(argsStart, closeIdx))
            if (args.size <= rule.maxArgs) {
                result.append(source, bestIdx, closeIdx + 1)
            } else {
                // Trimmed calls drop a `Foo.` receiver: the bare singleton call resolves
                // identically, and the recorded call text starts at the function name.
                var callStart = bestIdx
                var scan = bestIdx
                if (scan > i && source[scan - 1] == '.') {
                    scan--
                    while (scan > i && (source[scan - 1].isLetterOrDigit() || source[scan - 1] == '_')) scan--
                    callStart = scan
                }

                result.setLength(result.length - (bestIdx - callStart))
                val originalCall = source.substring(bestIdx, closeIdx + 1)
                val keptArgs = args.take(rule.maxArgs).toMutableList()
                rule.forceArgs.forEach { (index, value) ->
                    if (index in keptArgs.indices) keptArgs[index] = value
                }
                val trimmedCall = rule.needle + keptArgs.joinToString(", ") + ")"
                fixes += Fix(rule.reason, originalCall, trimmedCall)
                result.append(trimmedCall)
            }
            i = closeIdx + 1
        }
        return result.toString() to fixes
    }

    /** Backwards-compatible view over [fix]: (original call, patched call) pairs. */
    fun trimLoadThreadedRequestArgs(source: String): Pair<String, List<Pair<String, String>>> {
        val (patched, fixes) = fix(source)
        return patched to fixes.map { it.original to it.patched }
    }

    /** Case-insensitive indexOf over the pre-lowercased [sourceLower], starting at [from]. */
    private fun indexOfIgnoreCase(sourceLower: String, from: Int, needleLower: String): Int {
        var pos = from
        val last = sourceLower.length - needleLower.length
        while (pos <= last) {
            if (sourceLower.regionMatches(pos, needleLower, 0, needleLower.length)) {
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
