#include "script_patcher.h"

#include <cctype>
#include <cstring>

#include <algorithm>
#include <string>

namespace utarcore {

namespace {

struct Rule {
	const char *needle;
	int max_args;
	const char *reason;
	// Argument index to replacement value applied to the trimmed call.
	std::vector<std::pair<int, const char *>> force_args;
};

// Godot 4.1.4's load_threaded_request(path, type_hint, use_sub_threads, cache_mode) accepts
// at most 4 arguments; a 5th is a parse error and freezes the scene load. The game's scripts
// come from a Godot 4.2+ codebase whose 5th argument asks for a fully background load. On
// 4.1.4 the trimmed call also keeps use_sub_threads=false, so every dependency of the
// requested scene is loaded sequentially on a single worker thread — the main thread blocks
// in load_threaded_get for minutes on mobile storage, freezing the engine (audio, input,
// render) until the load happens to finish. use_sub_threads=true distributes the dependency
// loads across the worker pool, which is the closest 4.1.4 behaviour to what the game asked
// for.
const Rule RULES[] = {
	{ "load_threaded_request(", 4, "load_threaded_request takes at most 4 arguments", { { 2, "true" } } },
};

std::string lowercase(const std::string &value) {
	std::string out;
	out.reserve(value.size());
	for (char c : value) {
		out.push_back(char(std::tolower(unsigned(c))));
	}
	return out;
}

/** Case-insensitive indexOf over the pre-lowercased source, starting at from. */
int64_t indexOfIgnoreCase(const std::string &source_lower, size_t from, const std::string &needle_lower) {
	if (needle_lower.empty() || source_lower.size() < needle_lower.size() || from > source_lower.size() - needle_lower.size()) {
		return -1;
	}
	for (size_t pos = from; pos <= source_lower.size() - needle_lower.size(); pos++) {
		if (source_lower.compare(pos, needle_lower.size(), needle_lower) == 0) {
			return int64_t(pos);
		}
	}
	return -1;
}

/** Skips a string literal starting at quote_idx. Returns the index of the closing quote, or -1. */
int64_t skipStringLiteral(const std::string &source, size_t quote_idx) {
	char quote = source[quote_idx];
	size_t i = quote_idx + 1;
	while (i < source.size()) {
		char c = source[i];
		if (c == '\\') {
			i += 2; // skip escaped char
		} else if (c == quote) {
			return int64_t(i);
		} else {
			i++;
		}
	}
	return -1;
}

/** Finds the matching close for an open bracket/brace at open_idx. */
int64_t findMatchingClose(const std::string &source, size_t open_idx, char open_char) {
	char close_char;
	if (open_char == '[') {
		close_char = ']';
	} else if (open_char == '{') {
		close_char = '}';
	} else {
		return -1;
	}
	int depth = 1;
	size_t i = open_idx + 1;
	while (i < source.size()) {
		char c = source[i];
		if (c == '"' || c == '\'') {
			int64_t skip = skipStringLiteral(source, i);
			if (skip < 0) {
				return -1;
			}
			i = size_t(skip);
			i++;
			continue;
		}
		if (c == open_char) {
			depth++;
		} else if (c == close_char) {
			depth--;
			if (depth == 0) {
				return int64_t(i);
			}
		} else if (c == '(' || c == '[' || c == '{') {
			if (c != open_char) {
				int64_t inner_close = findMatchingClose(source, i, c);
				if (inner_close < 0) {
					return -1;
				}
				i = size_t(inner_close);
			}
		}
		i++;
	}
	return -1;
}

/**
 * Finds the index of the matching closing parenthesis for an open paren at open_idx,
 * respecting nested (), [], {}, and string literals.
 */
int64_t findMatchingCloseParen(const std::string &source, size_t open_idx) {
	int depth = 1;
	size_t i = open_idx + 1;
	while (i < source.size()) {
		char c = source[i];
		if (c == '"' || c == '\'') {
			int64_t skip = skipStringLiteral(source, i);
			if (skip < 0) {
				return -1;
			}
			i = size_t(skip);
		} else if (c == '(') {
			depth++;
		} else if (c == ')') {
			depth--;
			if (depth == 0) {
				return int64_t(i);
			}
		} else if (c == '[' || c == '{') {
			int64_t close = findMatchingClose(source, i, c);
			if (close < 0) {
				return -1;
			}
			i = size_t(close);
		}
		i++;
	}
	return -1;
}

/** Appends a string literal (with escape handling) to sb, starting at idx. Returns the index of the closing quote. */
int64_t skipStringLiteralInArgs(const std::string &args, size_t idx, std::string &sb) {
	char quote = args[idx];
	size_t i = idx + 1;
	while (i < args.size()) {
		char c = args[i];
		if (c == '\\') {
			sb.push_back(c);
			if (i + 1 < args.size()) {
				sb.push_back(args[i + 1]);
				i += 2;
			} else {
				i++;
			}
		} else if (c == quote) {
			sb.push_back(c);
			return int64_t(i);
		} else {
			sb.push_back(c);
			i++;
		}
	}
	return -1;
}

/**
 * Splits args into top-level arguments, respecting nested (), [], {} and strings.
 * Commas inside nested structures or strings are not treated as separators.
 */
std::vector<std::string> splitTopLevelArgs(const std::string &args) {
	std::vector<std::string> result;
	int depth = 0;
	std::string current;
	size_t i = 0;
	while (i < args.size()) {
		char c = args[i];
		if (c == '"' || c == '\'') {
			current.push_back(c);
			int64_t close = skipStringLiteralInArgs(args, i, current);
			if (close < 0) {
				result.push_back(current);
				return result;
			}
			i = size_t(close);
			i++;
			continue;
		}
		if (c == '(' || c == '[' || c == '{') {
			depth++;
			current.push_back(c);
			i++;
			continue;
		}
		if (c == ')' || c == ']' || c == '}') {
			depth = std::max(0, depth - 1);
			current.push_back(c);
			i++;
			continue;
		}
		if (c == ',' && depth == 0) {
			size_t begin = 0;
			size_t end = current.size();
			while (begin < end && std::isspace(unsigned(current[begin]))) begin++;
			while (end > begin && std::isspace(unsigned(current[end - 1]))) end--;
			result.push_back(current.substr(begin, end - begin));
			current.clear();
			i++;
			continue;
		}
		current.push_back(c);
		i++;
	}
	if (!current.empty()) {
		size_t begin = 0;
		size_t end = current.size();
		while (begin < end && std::isspace(unsigned(current[begin]))) begin++;
		while (end > begin && std::isspace(unsigned(current[end - 1]))) end--;
		result.push_back(current.substr(begin, end - begin));
	}
	return result;
}

/** Applies exact source-code block replacements to [patched], recording each in [fixes]. */
void applyCodeBlockReplacements(std::string &patched, std::vector<ScriptFix> &fixes) {
	for (const CodeBlockRule &rule : CODE_BLOCK_RULES) {
		const char *search = rule.search;
		const char *replace = rule.replace;
		size_t search_len = std::strlen(search);
		size_t pos = patched.find(search);
		if (pos == std::string::npos) {
			continue; // already patched or not present
		}
		size_t replace_start = pos;
		size_t replace_len = search_len;
		if (rule.replace_through_close_paren) {
			int64_t close_idx = findMatchingCloseParen(patched, pos);
			if (close_idx >= 0) {
				replace_len = size_t(close_idx) - pos + 1;
			}
		}
		std::string original_block = patched.substr(replace_start, replace_len);
		fixes.push_back({ rule.reason, original_block, replace });
		patched.replace(replace_start, replace_len, replace);
	}
}

} // namespace

std::vector<uint8_t> ScriptFixResult::toBlob() const {
	std::vector<uint8_t> out;
	auto u32 = [&](uint32_t value) {
		out.push_back(uint8_t(value & 0xFF));
		out.push_back(uint8_t((value >> 8) & 0xFF));
		out.push_back(uint8_t((value >> 16) & 0xFF));
		out.push_back(uint8_t((value >> 24) & 0xFF));
	};
	auto put_str = [&](const std::string &value) {
		u32(uint32_t(value.size()));
		out.insert(out.end(), value.begin(), value.end());
	};
	u32(uint32_t(fixes.size()));
	for (const ScriptFix &fix : fixes) {
		put_str(fix.rule);
		put_str(fix.original);
		put_str(fix.patched);
	}
	put_str(source);
	return out;
}

ScriptFixResult scriptFix(const std::string &source) {
	ScriptFixResult result;
	if (source.empty()) {
		return result;
	}
	std::string source_lower = lowercase(source);
	std::vector<std::string> needles_lower;
	for (const Rule &rule : RULES) {
		needles_lower.push_back(lowercase(rule.needle));
	}

	std::string patched;
	patched.reserve(source.size());
	size_t i = 0;
	while (i < source.size()) {
		int64_t best_idx = -1;
		int best_rule_index = -1;
		for (size_t index = 0; index < needles_lower.size(); index++) {
			int64_t idx = indexOfIgnoreCase(source_lower, i, needles_lower[index]);
			if (idx >= 0 && (best_idx < 0 || idx < best_idx)) {
				best_idx = idx;
				best_rule_index = int(index);
			}
		}
		if (best_rule_index < 0 || best_idx < 0) {
			patched.append(source, i, source.size() - i);
			break;
		}
		const Rule &rule = RULES[size_t(best_rule_index)];

		patched.append(source, i, size_t(best_idx) - i);
		// The needle ends with '(' — the scan starts from the paren itself, the
		// arguments from the character after it.
		size_t paren_idx = size_t(best_idx) + strlen(rule.needle) - 1;
		size_t args_start = paren_idx + 1;
		int64_t close_idx = findMatchingCloseParen(source, paren_idx);
		if (close_idx < 0) {
			// Unbalanced parens; copy the rest as-is.
			patched.append(source, size_t(best_idx), source.size() - size_t(best_idx));
			break;
		}

		std::vector<std::string> args = splitTopLevelArgs(source.substr(args_start, size_t(close_idx) - args_start));
		if (int(args.size()) <= rule.max_args) {
			patched.append(source, size_t(best_idx), size_t(close_idx) + 1 - size_t(best_idx));
		} else {
			// Keep any `Foo.` receiver already copied into `patched`: a bare call to a singleton method
			// (e.g. load_threaded_request without ResourceLoader.) is not valid GDScript. The recorded
			// call text starts at the function name.
			std::string original_call = source.substr(size_t(best_idx), size_t(close_idx) + 1 - size_t(best_idx));
			std::vector<std::string> kept_args;
			kept_args.reserve(std::min(size_t(rule.max_args), args.size()));
			for (size_t k = 0; k < args.size() && int(k) < rule.max_args; k++) {
				kept_args.push_back(args[k]);
			}
			for (const auto &force : rule.force_args) {
				if (force.first >= 0 && size_t(force.first) < kept_args.size()) {
					kept_args[size_t(force.first)] = force.second;
				}
			}
			std::string trimmed_call = rule.needle;
			for (size_t k = 0; k < kept_args.size(); k++) {
				if (k > 0) {
					trimmed_call += ", ";
				}
				trimmed_call += kept_args[k];
			}
			trimmed_call += ")";
			result.fixes.push_back({ rule.reason, original_call, trimmed_call });
			patched += trimmed_call;
		}
		i = size_t(close_idx) + 1;
	}
	applyCodeBlockReplacements(patched, result.fixes);
	result.source = std::move(patched);
	return result;
}

} // namespace utarcore
