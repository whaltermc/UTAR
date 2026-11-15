#ifndef UTARCORE_LIBRARY_SET_H
#define UTARCORE_LIBRARY_SET_H

#include <string>
#include <vector>

namespace utarcore {

struct ScannedLibrary {
	std::string fileName;
	std::vector<std::string> entrySymbols;
};

struct LibrarySet {
	/** ABI directory (e.g. "arm64-v8a") to the libraries available there. */
	std::vector<std::pair<std::string, std::vector<ScannedLibrary>>> abi_libraries;

	bool empty() const { return abi_libraries.empty(); }

	/** Serialises the set for the Kotlin side: u32 abiCount; per abi [len abi, u32 libCount, per lib [len name, u32 symCount, per sym [len sym]]]. */
	std::vector<uint8_t> toBlob() const;

	/** Parses a blob produced by [toBlob]. */
	static LibrarySet fromBlob(const std::vector<uint8_t> &p_blob);

	/**
	 * Scans the installed APKs (and splits) for extension libraries under `lib/<abi>/`,
	 * reading each library's ELF dynamic symbol table. Results are cached on disk in
	 * [p_cache_dir] (may be empty) keyed by APK identity, so an unchanged app skips the scan.
	 * Returns an empty set when nothing usable is found.
	 */
	static LibrarySet fromApks(const std::vector<std::string> &p_apk_paths, const std::string &p_abi_directory,
			const std::string &p_cache_dir);

	/** Same scan against a directory of extracted libraries (e.g. nativeLibraryDir). */
	static LibrarySet fromDirectory(const std::string &p_directory, const std::string &p_abi_directory);
};

} // namespace utarcore

#endif // UTARCORE_LIBRARY_SET_H
