#include "gdext_rewriter.h"

#include "library_set.h"

#include <cctype>

#include <algorithm>

namespace utarcore {

namespace {

const char *LIBRARIES_SECTION = "[libraries]";
const char *CONFIGURATION_SECTION = "[configuration]";
const char *ANDROID_PATH_ROOT = "res://utar_natives";

bool equalsIgnoreCase(const std::string &a, const std::string &b) {
	if (a.size() != b.size()) {
		return false;
	}
	for (size_t i = 0; i < a.size(); i++) {
		if (std::tolower(unsigned(a[i])) != std::tolower(unsigned(b[i]))) {
			return false;
		}
	}
	return true;
}

std::string lowercase(const std::string &value) {
	std::string out;
	out.reserve(value.size());
	for (char c : value) {
		out.push_back(char(std::tolower(unsigned(c))));
	}
	return out;
}

std::vector<std::string> linesOf(const std::string &text) {
	std::vector<std::string> lines;
	std::string current;
	for (char c : text) {
		if (c == '\n') {
			lines.push_back(current);
			current.clear();
		} else if (c != '\r') {
			current.push_back(c);
		}
	}
	lines.push_back(current);
	return lines;
}

std::string joinLines(const std::vector<std::string> &lines) {
	std::string out;
	for (size_t i = 0; i < lines.size(); i++) {
		if (i > 0) {
			out += '\n';
		}
		out += lines[i];
	}
	return out;
}

std::string trim(const std::string &value) {
	size_t begin = 0;
	size_t end = value.size();
	while (begin < end && std::isspace(unsigned(value[begin]))) {
		begin++;
	}
	while (end > begin && std::isspace(unsigned(value[end - 1]))) {
		end--;
	}
	return value.substr(begin, end - begin);
}

bool isSectionHeader(const std::string &line) {
	std::string t = trim(line);
	return t.size() >= 2 && t.front() == '[' && t.back() == ']';
}

bool startsWithIgnoreCase(const std::string &value, const std::string &prefix) {
	if (value.size() < prefix.size()) {
		return false;
	}
	return equalsIgnoreCase(value.substr(0, prefix.size()), prefix);
}

std::vector<std::string> sectionLines(const std::string &config, const char *section) {
	std::vector<std::string> result;
	std::string current;
	for (const std::string &line : linesOf(config)) {
		std::string t = trim(line);
		if (isSectionHeader(t)) {
			current = t;
			continue;
		}
		if (equalsIgnoreCase(current, section) && !t.empty() && t.find('=') != std::string::npos) {
			result.push_back(t);
		}
	}
	return result;
}

std::string substringBefore(const std::string &value, char sep) {
	size_t at = value.find(sep);
	return at == std::string::npos ? value : value.substr(0, at);
}

std::string substringAfter(const std::string &value, char sep) {
	size_t at = value.find(sep);
	return at == std::string::npos ? std::string() : value.substr(at + 1);
}

std::string substringAfterLast(const std::string &value, char sep) {
	size_t at = value.rfind(sep);
	return at == std::string::npos ? value : value.substr(at + 1);
}

std::string removeSuffix(const std::string &value, const std::string &suffix) {
	if (value.size() > suffix.size() && value.compare(value.size() - suffix.size(), suffix.size(), suffix) == 0) {
		return value.substr(0, value.size() - suffix.size());
	}
	return value;
}

/** First path segment of every declared library, e.g. `libfoo.windows.x86_64.dll` -> `libfoo`. */
std::string rootOf(const std::string &path) {
	std::string file_name = substringAfterLast(path, '/');
	std::string after_backslash = substringAfterLast(file_name, '\\');
	std::string without_extension = after_backslash;
	for (const char *suffix : { ".so", ".dll", ".dylib", ".lib", ".wasm" }) {
		without_extension = removeSuffix(without_extension, suffix);
	}
	return substringBefore(without_extension, '.');
}

/** Lowercased alphanumeric-only normalisation for tolerant prefix matching. */
std::string normalize(const std::string &value) {
	std::string lowered = lowercase(value);
	std::string out;
	for (char c : lowered) {
		if (std::isalnum(unsigned(c))) {
			out.push_back(c);
		}
	}
	return out;
}

/** Godot architecture feature tag for an Android ABI directory. */
std::string featureTag(const std::string &abi_directory) {
	if (abi_directory == "arm64-v8a") return "arm64";
	if (abi_directory == "armeabi-v7a" || abi_directory == "armeabi") return "arm32";
	if (abi_directory == "x86_64") return "x86_64";
	if (abi_directory == "x86") return "x86_32";
	return abi_directory;
}

std::string configurationValue(const std::string &config, const char *key) {
	for (const std::string &line : sectionLines(config, CONFIGURATION_SECTION)) {
		std::string name = trim(substringBefore(line, '='));
		if (equalsIgnoreCase(name, key)) {
			std::string value = trim(substringAfter(line, '='));
			if (value.size() >= 2 && value.front() == '"' && value.back() == '"') {
				value = value.substr(1, value.size() - 2);
			}
			return value;
		}
	}
	return std::string();
}

std::string trimStart(const std::string &value);

std::string stripAndroidEntries(const std::string &config) {
	std::vector<std::string> kept;
	for (const std::string &line : linesOf(config)) {
		if (!startsWithIgnoreCase(trimStart(line), "android.")) {
			kept.push_back(line);
		}
	}
	return joinLines(kept);
}

std::string trimStart(const std::string &value) {
	size_t begin = 0;
	while (begin < value.size() && std::isspace(unsigned(value[begin]))) {
		begin++;
	}
	return value.substr(begin);
}

std::string appendToLibraries(const std::string &config, const std::vector<std::string> &lines) {
	std::vector<std::string> all = linesOf(config);
	int64_t section_index = -1;
	for (size_t i = 0; i < all.size(); i++) {
		if (equalsIgnoreCase(trim(all[i]), LIBRARIES_SECTION)) {
			section_index = int64_t(i);
			break;
		}
	}

	if (section_index < 0) {
		std::string trimmed = config;
		while (!trimmed.empty() && trimmed.back() == '\n') {
			trimmed.pop_back();
		}
		std::string out = trimmed;
		if (!out.empty()) {
			out += "\n\n";
		}
		out += LIBRARIES_SECTION;
		out += '\n';
		for (const std::string &line : lines) {
			out += line;
			out += '\n';
		}
		return out;
	}

	size_t insert_at = size_t(section_index) + 1;
	while (insert_at < all.size() && !isSectionHeader(all[insert_at])) {
		insert_at++;
	}
	// Keep the blank separator that usually precedes the next section.
	while (insert_at > size_t(section_index) + 1 && trim(all[insert_at - 1]).empty()) {
		insert_at--;
	}
	all.insert(all.begin() + int64_t(insert_at), lines.begin(), lines.end());
	return joinLines(all);
}

} // namespace

std::vector<uint8_t> GdextPlan::toBlob() const {
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
	u32(patched() ? 1 : 0);
	put_str(text);
	u32(uint32_t(libraries.size()));
	for (const auto &entry : libraries) {
		put_str(entry.first);
		put_str(entry.second);
	}
	return out;
}

std::vector<std::pair<std::string, std::string>> matchByEntrySymbol(const LibrarySet &natives,
		const std::string &entry_symbol) {
	std::vector<std::pair<std::string, std::string>> matches;
	if (entry_symbol.empty()) {
		return matches;
	}
	for (const auto &group : natives.abi_libraries) {
		for (const ScannedLibrary &library : group.second) {
			if (std::find(library.entrySymbols.begin(), library.entrySymbols.end(), entry_symbol) !=
					library.entrySymbols.end()) {
				matches.emplace_back(group.first, library.fileName);
				break;
			}
		}
	}
	return matches;
}

std::vector<std::pair<std::string, std::string>> matchByLibraryName(const std::string &original,
		const LibrarySet &natives) {
	std::vector<std::pair<std::string, std::string>> matches;
	std::vector<std::string> roots;
	for (const std::string &line : sectionLines(original, LIBRARIES_SECTION)) {
		std::string path = trim(substringAfter(line, '='));
		if (path.size() >= 2 && path.front() == '"' && path.back() == '"') {
			path = path.substr(1, path.size() - 2);
		}
		if (!path.empty()) {
			std::string root = normalize(rootOf(path));
			if (!root.empty()) {
				roots.push_back(root);
			}
		}
	}

	// All libraries across every ABI, with their owning ABI attached.
	std::vector<std::pair<std::string, const ScannedLibrary *>> all;
	for (const auto &group : natives.abi_libraries) {
		for (const ScannedLibrary &library : group.second) {
			all.emplace_back(group.first, &library);
		}
	}
	if (all.empty()) {
		return matches;
	}

	std::vector<const std::pair<std::string, const ScannedLibrary *> *> chosen;
	for (const auto &entry : all) {
		std::string candidate = normalize(rootOf(entry.second->fileName));
		if (candidate.empty()) {
			continue;
		}
		for (const std::string &root : roots) {
			if (candidate.compare(0, root.size(), root) == 0 || root.compare(0, candidate.size(), candidate) == 0) {
				chosen.push_back(&entry);
				break;
			}
		}
	}
	if (chosen.empty() && all.size() == 1) {
		chosen.push_back(&all[0]); // A single Android build can only belong to this one config.
	}

	for (const auto &group : natives.abi_libraries) {
		for (const auto *entry : chosen) {
			if (entry->first != group.first) {
				continue;
			}
			for (const ScannedLibrary &library : group.second) {
				if (&library == entry->second) {
					matches.emplace_back(group.first, library.fileName);
					break;
				}
			}
		}
	}
	return matches;
}

GdextPlan gdextPlan(const std::string &config_path, const std::string &original, const LibrarySet &natives) {
	std::string entry_symbol = configurationValue(original, "entry_symbol");
	std::vector<std::pair<std::string, std::string>> matches = matchByEntrySymbol(natives, entry_symbol);
	if (matches.empty()) {
		matches = matchByLibraryName(original, natives);
	}
	GdextPlan plan;
	plan.config_path = config_path;
	if (matches.empty()) {
		plan.text = original;
		return plan;
	}

	std::vector<std::string> lines;
	for (const auto &match : matches) {
		lines.push_back("android." + featureTag(match.first) + " = \"" + ANDROID_PATH_ROOT + "/" +
				match.first + "/" + match.second + "\"");
	}
	plan.text = appendToLibraries(stripAndroidEntries(original), lines);
	plan.libraries = std::move(matches);
	return plan;
}

} // namespace utarcore
