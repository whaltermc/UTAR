#ifndef UTARCORE_GDEXT_REWRITER_H
#define UTARCORE_GDEXT_REWRITER_H

#include <string>
#include <vector>

namespace utarcore {

struct LibrarySet;

struct GdextPlan {
	std::string config_path;
	std::string text;
	/** ABI directory (e.g. "arm64-v8a") to the native library that satisfies this config. */
	std::vector<std::pair<std::string, std::string>> libraries;

	bool patched() const { return !libraries.empty(); }

	/** Serialises for the Kotlin side: u32 status; [len text, text]; u32 count; per entry [len abi, len lib]. */
	std::vector<uint8_t> toBlob() const;
};

/**
 * Rewrites a `.gdextension` config so the engine picks the Android build of the extension
 * instead of the desktop binary the game was exported with. Matching is decided by the
 * library's `entry_symbol` when the built libraries expose it, falling back to library name
 * prefixes. Returns the original text untouched when no library matches.
 */
GdextPlan gdextPlan(const std::string &config_path, const std::string &original, const LibrarySet &natives);

} // namespace utarcore

#endif // UTARCORE_GDEXT_REWRITER_H
