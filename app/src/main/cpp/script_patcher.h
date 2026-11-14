#ifndef UTARCORE_SCRIPT_PATCHER_H
#define UTARCORE_SCRIPT_PATCHER_H

#include <string>
#include <vector>

namespace utarcore {

struct ScriptFix {
	std::string rule;
	std::string original;
	std::string patched;
};

struct ScriptFixResult {
	std::string source;
	std::vector<ScriptFix> fixes;

	/** Serialises for the Kotlin side: u32 fixCount; per fix [len rule, len original, len patched]; [len text, text]. */
	std::vector<uint8_t> toBlob() const;
};

/**
 * Finds and fixes known GDScript API mismatches inside game scripts at install time:
 * a call taking more arguments than the rule allows is trimmed back to the documented
 * maximum (respecting nested (), [], {} and string literals), calls that already fit are
 * left byte-for-byte untouched, and every applied fix is reported.
 */
ScriptFixResult scriptFix(const std::string &source);

} // namespace utarcore

#endif // UTARCORE_SCRIPT_PATCHER_H
