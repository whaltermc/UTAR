#ifndef UTARCORE_PCK_ENGINE_H
#define UTARCORE_PCK_ENGINE_H

#include <cstddef>
#include <cstdint>
#include <string>
#include <utility>
#include <unordered_map>
#include <vector>

namespace utarcore {

struct PckEntry {
	std::string path;
	uint64_t offset = 0; // absolute file offset of the payload
	uint64_t size = 0;
	uint8_t md5[16] = { 0 };
	uint32_t flags = 0;

	bool isEncrypted() const { return (flags & 1u) != 0; }
};

/** Progress callback for rewrites; receives bytes written so far. */
using ProgressFn = void (*)(int64_t written, void *user);

/**
 * Native reader/writer for Godot 4.1 `.pck` archives (format version 2).
 *
 * Replaces the previous pure-Kotlin implementation: index parsing, entry reads and the
 * streaming rewrite all run in-process without JVM byte-buffer churn, which is what kept
 * install/launch I/O bound on large packs.
 *
 * Mirrors the on-disk layout the engine writes:
 *   header  : magic "GDPC" | version | major | minor | patch | flags | file_base | 16 * u32 reserved
 *   index   : entry count, then per entry: padded path length | path | offset | size | md5 | flags
 *   padding : zero bytes up to file_base
 *   data    : file payloads, 32-byte aligned, offsets stored relative to file_base
 */
class PckFile {
public:
	/** Opens [p_path], parsing (or cached-loading) the index. Returns null and sets [r_error] on failure. */
	static PckFile *open(const std::string &p_path, std::string &r_error);

	~PckFile();

	size_t entryCount() const { return m_entries.size(); }
	const PckEntry &entry(size_t p_index) const { return m_entries[p_index]; }
	int findEntry(const std::string &p_path) const;

	uint32_t packVersion() const { return m_pack_version; }
	uint32_t engineMajor() const { return m_engine_major; }
	uint32_t engineMinor() const { return m_engine_minor; }
	uint32_t enginePatch() const { return m_engine_patch; }

	/** Reads one unencrypted entry into [r_out]. Returns false (and sets [r_error]) on failure. */
	bool readEntry(size_t p_index, std::vector<uint8_t> &r_out, uint64_t p_limit, std::string &r_error) const;

	/**
	 * Scans all pack entries for .ctex files with a version > 1 (too new for Godot 4.1.x)
	 * and returns replacement payloads with the version downgraded to 1. Callers should
	 * add these to their replacements map before calling rewrite().
	 */
	std::vector<std::pair<std::string, std::vector<uint8_t>>> patchCtexEntries();

	/**
	 * Streams a copy of this pack to [p_target], replacing the payloads named in
		[p_replacements] and appending the files in [p_additions] the pack does not already
		contain (callers merge additions that collide with existing paths first). Untouched
	 * payloads are copied byte for byte with their recorded MD5; rewritten/added payloads
	 * get freshly computed MD5s.
	 *
	 * Progress is reported through [p_progress] (may be null). Returns false and sets
	 * [r_error] on failure; the partially written target is removed.
	 */
	bool rewrite(
			const std::string &p_target,
			const std::vector<std::pair<std::string, std::vector<uint8_t>>> &p_replacements,
			const std::vector<std::pair<std::string, std::vector<uint8_t>>> &p_additions,
			ProgressFn p_progress, void *p_user,
			std::string &r_error);

	/**
	 * Writes a brand-new pack containing exactly [p_files] (res:// paths), in the same
	 * format the engine writes. Used for small override/patch packs.
	 */
	static bool writeNew(
			const std::string &p_target,
			const std::vector<std::pair<std::string, std::vector<uint8_t>>> &p_files,
			uint32_t p_major, uint32_t p_minor, uint32_t p_patch,
			std::string &r_error);

private:
	PckFile() = default;

	bool parseIndex(const std::string &p_path, std::string &r_error);
	bool loadCache(const std::string &p_path, std::string &r_error);
	void saveCache(const std::string &p_path);

	bool readAt(uint64_t p_offset, uint64_t p_length, uint8_t *p_out, std::string &r_error) const;

	int m_fd = -1;
	uint64_t m_file_size = 0;
	uint32_t m_pack_version = 0;
	uint32_t m_engine_major = 0;
	uint32_t m_engine_minor = 0;
	uint32_t m_engine_patch = 0;
	std::vector<PckEntry> m_entries;
	std::unordered_map<std::string, int> m_path_index;
};

} // namespace utarcore

#endif // UTARCORE_PCK_ENGINE_H
