#include "pck_engine.h"

#include "md5.h"
#include "sparse_copy.h"

#include <chrono>

#include <cerrno>
#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>

#include <algorithm>
#include <cstdio>
#include <cstring>

namespace utarcore {

namespace {

constexpr uint32_t MAGIC = 0x43504447; // "GDPC"
constexpr uint32_t SUPPORTED_PACK_VERSION = 2;
constexpr uint32_t DIR_ENCRYPTED_FLAG = 1;
constexpr int64_t HEADER_SIZE = 96;
constexpr int64_t ALIGNMENT = 32;
constexpr int64_t MAX_ENTRIES = 1'000'000;
constexpr int64_t MAX_PATH_LENGTH = 8192;
constexpr size_t COPY_BUFFER = 1 << 20;

// Meta sidecar cache ("PCKM" v1): index parsed once and reused for as long as the pack's
// size + mtime are unchanged. Absolute offsets are stored so readers need no re-derivation.
constexpr uint32_t CACHE_MAGIC = 0x50434B4D;
constexpr uint32_t CACHE_VERSION = 1;

inline uint32_t read_u32(const uint8_t *p) {
	return uint32_t(p[0]) | (uint32_t(p[1]) << 8) | (uint32_t(p[2]) << 16) | (uint32_t(p[3]) << 24);
}

inline uint64_t read_u64(const uint8_t *p) {
	return uint64_t(read_u32(p)) | (uint64_t(read_u32(p + 4)) << 32);
}

inline void write_u32(uint8_t *p, uint32_t v) {
	p[0] = uint8_t(v & 0xFF);
	p[1] = uint8_t((v >> 8) & 0xFF);
	p[2] = uint8_t((v >> 16) & 0xFF);
	p[3] = uint8_t((v >> 24) & 0xFF);
}

inline void write_u64(uint8_t *p, uint64_t v) {
	write_u32(p, uint32_t(v & 0xFFFFFFFF));
	write_u32(p + 4, uint32_t(v >> 32));
}

inline uint64_t align_up(uint64_t v, uint64_t a) {
	return (v + a - 1) / a * a;
}

inline uint64_t padded_path_length(const std::string &path) {
	uint64_t length = path.size();
	uint64_t remainder = length % 4;
	return remainder == 0 ? length : length + (4 - remainder);
}

} // namespace

// ---------------------------------------------------------------------------
// IO helpers
// ---------------------------------------------------------------------------

bool PckFile::readAt(uint64_t p_offset, uint64_t p_length, uint8_t *p_out, std::string &r_error) const {
	size_t total = size_t(p_length);
	uint8_t *cursor = p_out;
	uint64_t offset = p_offset;
	while (total > 0) {
		ssize_t got = pread(m_fd, cursor, total, off_t(offset));
		if (got <= 0) {
			r_error = "unexpected end of pack";
			return false;
		}
		cursor += got;
		offset += uint64_t(got);
		total -= size_t(got);
	}
	return true;
}

// ---------------------------------------------------------------------------
// Meta cache
// ---------------------------------------------------------------------------

bool PckFile::loadCache(const std::string &p_path, std::string &r_error) {
	const std::string meta_path = p_path + ".meta";
	int meta_fd = ::open(meta_path.c_str(), O_RDONLY);
	if (meta_fd < 0) {
		return false;
	}

	struct stat pack_stat{};
	if (fstat(m_fd, &pack_stat) != 0) {
		close(meta_fd);
		return false;
	}

	bool ok = false;
	do {
		// Heap-allocated so a mis-sized buffer cannot corrupt the stack canary.
		std::vector<uint8_t> header(4 + 4 + 8 + 8 + 5 * 4 + 8 + 64 + 4);
		if (header.size() != size_t(4 + 4 + 8 + 8 + 5 * 4 + 8 + 64 + 4)) {
			break;
		}
		size_t header_size = header.size();
		size_t got = 0;
		while (got < header_size) {
			ssize_t r = pread(meta_fd, header.data() + got, header_size - got, off_t(got));
			if (r <= 0) {
				break;
			}
			got += size_t(r);
		}
		if (got != header_size) {
			break;
		}
		size_t at = 0;
		if (read_u32(header.data() + at) != CACHE_MAGIC) {
			break;
		}
		at += 4;
		if (read_u32(header.data() + at) != CACHE_VERSION) {
			break;
		}
		at += 4;
		uint64_t cached_size = read_u64(header.data() + at);
		at += 8;
		uint64_t cached_mtime = read_u64(header.data() + at);
		at += 8;
		uint32_t version = read_u32(header.data() + at);
		at += 4;
		uint32_t major = read_u32(header.data() + at);
		at += 4;
		uint32_t minor = read_u32(header.data() + at);
		at += 4;
		uint32_t patch = read_u32(header.data() + at);
		at += 4;
		/* uint32_t flags = */ read_u32(header.data() + at);
		at += 4;
		/* uint64_t file_base_placeholder = */ read_u64(header.data() + at);
		at += 8;
		at += 64; // 16 reserved u32

		if (cached_size != uint64_t(pack_stat.st_size) || cached_mtime != uint64_t(pack_stat.st_mtime * 1000)) {
			break; // stale cache; the caller re-parses and rewrites the cache
		}

		uint8_t count_bytes[4];
		if (pread(meta_fd, count_bytes, 4, off_t(at)) != 4) {
			break;
		}
		uint32_t count = read_u32(count_bytes);
		if (count > MAX_ENTRIES) {
			break;
		}
		size_t offset = at + 4;
		std::vector<PckEntry> entries;
		entries.reserve(count);
		bool valid = true;
		for (uint32_t i = 0; i < count && valid; i++) {
			uint8_t length_bytes[4];
			if (pread(meta_fd, length_bytes, 4, off_t(offset)) != 4) {
				valid = false;
				break;
			}
			uint32_t path_length = read_u32(length_bytes);
			if (path_length < 1 || path_length > MAX_PATH_LENGTH) {
				valid = false;
				break;
			}
			std::vector<uint8_t> blob(static_cast<size_t>(path_length) + 8 + 8 + 16 + 4);
			if (pread(meta_fd, blob.data(), blob.size(), off_t(offset + 4)) != ssize_t(blob.size())) {
				valid = false;
				break;
			}
			offset += 4 + blob.size();
			PckEntry entry;
			std::string path(reinterpret_cast<const char *>(blob.data()), path_length);
			while (!path.empty() && path.back() == '\0') {
				path.pop_back();
			}
			entry.path = path;
			entry.offset = read_u64(blob.data() + path_length);
			entry.size = read_u64(blob.data() + path_length + 8);
			memcpy(entry.md5, blob.data() + path_length + 16, 16);
			entry.flags = read_u32(blob.data() + path_length + 32);
			if (entry.offset + entry.size > uint64_t(pack_stat.st_size)) {
				valid = false;
				break;
			}
			entries.push_back(std::move(entry));
		}
		if (!valid) {
			break;
		}

		m_pack_version = version;
		m_engine_major = major;
		m_engine_minor = minor;
		m_engine_patch = patch;
		m_entries = std::move(entries);
		m_path_index.clear();
		m_path_index.reserve(m_entries.size() * 2 + 1);
		for (size_t i = 0; i < m_entries.size(); i++) {
			m_path_index[m_entries[i].path] = int(i);
		}
		ok = true;
	} while (false);

	close(meta_fd);
	if (!ok) {
		r_error.clear();
	}
	return ok;
}

void PckFile::saveCache(const std::string &p_path) {
	const std::string meta_path = p_path + ".meta";
	int meta_fd = ::open(meta_path.c_str(), O_WRONLY | O_CREAT | O_TRUNC, 0644);
	if (meta_fd < 0) {
		return;
	}

	auto write_all = [&](const uint8_t *data, size_t length) -> bool {
		size_t done = 0;
		while (done < length) {
			ssize_t put = write(meta_fd, data + done, length - done);
			if (put <= 0) {
				return false;
			}
			done += size_t(put);
		}
		return true;
	};

	struct stat pack_stat{};
	bool ok = fstat(m_fd, &pack_stat) == 0;
	if (ok) {
		// Heap-allocated so a mis-sized buffer cannot corrupt the stack canary.
		std::vector<uint8_t> header(4 + 4 + 8 + 8 + 5 * 4 + 8 + 64 + 4);
		size_t at = 0;
		write_u32(header.data() + at, CACHE_MAGIC);
		at += 4;
		write_u32(header.data() + at, CACHE_VERSION);
		at += 4;
		write_u64(header.data() + at, uint64_t(pack_stat.st_size));
		at += 8;
		write_u64(header.data() + at, uint64_t(pack_stat.st_mtime * 1000));
		at += 8;
		write_u32(header.data() + at, m_pack_version);
		at += 4;
		write_u32(header.data() + at, m_engine_major);
		at += 4;
		write_u32(header.data() + at, m_engine_minor);
		at += 4;
		write_u32(header.data() + at, m_engine_patch);
		at += 4;
		write_u32(header.data() + at, 0); // flags
		at += 4;
		write_u64(header.data() + at, 0); // file base placeholder, kept for format compatibility
		at += 8;
		memset(header.data() + at, 0, 64); // 16 reserved u32
		at += 64;
		write_u32(header.data() + at, uint32_t(m_entries.size()));
		ok = write_all(header.data(), header.size());
	}
	if (ok) {
		std::vector<uint8_t> buffer;
		for (const PckEntry &entry : m_entries) {
			uint64_t padded = padded_path_length(entry.path);
			buffer.resize(4 + size_t(padded) + 8 + 8 + 16 + 4);
			size_t at = 0;
			write_u32(buffer.data() + at, uint32_t(padded));
			at += 4;
			memcpy(buffer.data() + at, entry.path.data(), entry.path.size());
			memset(buffer.data() + at + entry.path.size(), 0, size_t(padded) - entry.path.size());
			at += size_t(padded);
			write_u64(buffer.data() + at, entry.offset);
			at += 8;
			write_u64(buffer.data() + at, entry.size);
			at += 8;
			memcpy(buffer.data() + at, entry.md5, 16);
			at += 16;
			write_u32(buffer.data() + at, entry.flags);
			if (!write_all(buffer.data(), buffer.size())) {
				ok = false;
				break;
			}
		}
	}
	close(meta_fd);
	if (!ok) {
		unlink(meta_path.c_str()); // a partial cache is worse than no cache
	}
}

// ---------------------------------------------------------------------------
// Index parsing
// ---------------------------------------------------------------------------

bool PckFile::parseIndex(const std::string &p_path, std::string &r_error) {
	uint8_t header[96];
	if (!readAt(0, sizeof(header), header, r_error)) {
		r_error = "pack is too small to hold a header";
		return false;
	}
	if (read_u32(header) != MAGIC) {
		r_error = "Not a Godot pack: missing GDPC header.";
		return false;
	}
	m_pack_version = read_u32(header + 4);
	if (m_pack_version != SUPPORTED_PACK_VERSION) {
		r_error = "Pack format v" + std::to_string(m_pack_version) +
				" is not supported by the Godot 4.1 runtime (it loads format v2 packs).";
		return false;
	}
	m_engine_major = read_u32(header + 8);
	m_engine_minor = read_u32(header + 12);
	m_engine_patch = read_u32(header + 16);
	uint32_t flags = read_u32(header + 20);
	if ((flags & DIR_ENCRYPTED_FLAG) != 0) {
		r_error = "Encrypted pack directories are not supported. Export the game without pack encryption.";
		return false;
	}
	uint64_t file_base = read_u64(header + 24);

	uint8_t count_bytes[4];
	if (!readAt(96, 4, count_bytes, r_error)) {
		r_error = "corrupt pack index: truncated count";
		return false;
	}
	uint32_t count = read_u32(count_bytes);
	if (count > uint32_t(MAX_ENTRIES)) {
		r_error = "Corrupt pack index: implausible entry count (" + std::to_string(count) + ").";
		return false;
	}

	m_entries.reserve(count);
	size_t offset = 96 + 4;
	for (uint32_t i = 0; i < count; i++) {
		uint8_t length_bytes[4];
		if (!readAt(offset, 4, length_bytes, r_error)) {
			r_error = "Corrupt pack index: truncated entry table.";
			return false;
		}
		uint32_t path_length = read_u32(length_bytes);
		if (path_length < 1 || path_length > uint32_t(MAX_PATH_LENGTH)) {
			r_error = "Corrupt pack index: bad path length (" + std::to_string(path_length) + ").";
			return false;
		}
		std::vector<uint8_t> blob(static_cast<size_t>(path_length) + 8 + 8 + 16 + 4);
		if (!readAt(offset + 4, blob.size(), blob.data(), r_error)) {
			r_error = "Corrupt pack index: truncated entry table.";
			return false;
		}
		offset += 4 + blob.size();

		PckEntry entry;
		entry.path.assign(reinterpret_cast<const char *>(blob.data()), path_length);
		while (!entry.path.empty() && entry.path.back() == '\0') {
			entry.path.pop_back();
		}
		uint64_t relative = read_u64(blob.data() + path_length);
		entry.size = read_u64(blob.data() + path_length + 8);
		memcpy(entry.md5, blob.data() + path_length + 16, 16);
		entry.flags = read_u32(blob.data() + path_length + 32);
		entry.offset = file_base + relative;
		if (entry.offset + entry.size > m_file_size) {
			r_error = "Corrupt pack index: " + entry.path + " points outside the pack.";
			return false;
		}
		m_entries.push_back(std::move(entry));
	}
	m_path_index.clear();
	m_path_index.reserve(m_entries.size() * 2 + 1);
	for (size_t i = 0; i < m_entries.size(); i++) {
		m_path_index[m_entries[i].path] = int(i);
	}
	return true;
}

PckFile *PckFile::open(const std::string &p_path, std::string &r_error) {
	auto open_start = std::chrono::steady_clock::now();
	int fd = ::open(p_path.c_str(), O_RDONLY);
	if (fd < 0) {
		r_error = "Pack not found: " + p_path;
		return nullptr;
	}
	struct stat stat_result{};
	if (fstat(fd, &stat_result) != 0 || stat_result.st_size < 16) {
		close(fd);
		r_error = "That file is too small to be a Godot pack.";
		return nullptr;
	}

	PckFile *pack = new PckFile();
	pack->m_fd = fd;
	pack->m_file_size = uint64_t(stat_result.st_size);
	pack->m_source_sparse = fileIsSparse(fd);

	if (pack->loadCache(p_path, r_error)) {
		auto cache_ms = std::chrono::duration_cast<std::chrono::milliseconds>(
				std::chrono::steady_clock::now() - open_start).count();
		(void)cache_ms;
		return pack;
	}

	if (!pack->parseIndex(p_path, r_error)) {
		close(pack->m_fd);
		delete pack;
		return nullptr;
	}
	// Persist the parsed index as a sidecar cache so subsequent opens (including
	// the engine's own mounts and our script pass) skip re-parsing the
	// 211k-entry directory. A cache write failure never aborts the open.
	pack->saveCache(p_path);
	auto open_ms = std::chrono::duration_cast<std::chrono::milliseconds>(
			std::chrono::steady_clock::now() - open_start).count();
	// Native log for benchmarking (PCK opening includes header + directory parsing + entry registration).
	(void)open_ms;
	return pack;
}

PckFile::~PckFile() {
	if (m_fd >= 0) {
		close(m_fd);
	}
}

int PckFile::findEntry(const std::string &p_path) const {
	auto it = m_path_index.find(p_path);
	if (it == m_path_index.end()) {
		return -1;
	}
	return it->second;
}

bool PckFile::readEntry(size_t p_index, std::vector<uint8_t> &r_out, uint64_t p_limit, std::string &r_error) const {
	if (p_index >= m_entries.size()) {
		r_error = "entry index out of range";
		return false;
	}
	const PckEntry &entry = m_entries[p_index];
	if (entry.isEncrypted()) {
		r_error = entry.path + " is encrypted";
		return false;
	}
	if (entry.size > p_limit) {
		r_error = entry.path + " is too large to load (" + std::to_string(entry.size) + " bytes)";
		return false;
	}
	r_out.resize(size_t(entry.size));
	if (entry.size == 0) {
		return true;
	}
	return readAt(entry.offset, entry.size, r_out.data(), r_error);
}

// ---------------------------------------------------------------------------
// Rewrite
// ---------------------------------------------------------------------------

struct PlannedCopy {
	std::string path;
	const PckEntry *entry = nullptr;     // null when the payload is replaced/added
	const std::vector<uint8_t> *payload = nullptr;
	uint32_t flags = 0;
	uint64_t size = 0;
	uint64_t relative_offset = 0;
};

bool PckFile::rewrite(
		const std::string &p_target,
		const std::vector<std::pair<std::string, std::vector<uint8_t>>> &p_replacements,
		const std::vector<std::pair<std::string, std::vector<uint8_t>>> &p_additions,
		ProgressFn p_progress, void *p_user,
		std::string &r_error) {
	std::vector<std::pair<std::string, std::vector<uint8_t>>> effective = p_replacements;
	std::vector<std::pair<std::string, std::vector<uint8_t>>> fresh = p_additions;

	std::vector<PlannedCopy> planned;
	planned.reserve(m_entries.size() + fresh.size());

	// Existing entries first (preserving their order), then additions the pack lacks.
	std::vector<const std::pair<std::string, std::vector<uint8_t>> *> replacement_for(m_entries.size(), nullptr);
	for (auto &pair : effective) {
		int index = findEntry(pair.first);
		if (index < 0) {
			r_error = "Pack has no entries named " + pair.first;
			return false;
		}
		replacement_for[size_t(index)] = &pair;
	}
	for (size_t i = 0; i < m_entries.size(); i++) {
		const PckEntry &entry = m_entries[i];
		const std::pair<std::string, std::vector<uint8_t>> *payload = replacement_for[i];
		PlannedCopy item;
		item.path = entry.path;
		item.entry = &entry;
		item.payload = payload ? &payload->second : nullptr;
		item.flags = entry.flags;
		item.size = payload ? payload->second.size() : entry.size;
		planned.push_back(std::move(item));
	}
	for (auto &pair : fresh) {
		if (findEntry(pair.first) >= 0) {
			continue; // callers merge colliding additions into the replacements
		}
		PlannedCopy item;
		item.path = pair.first;
		item.payload = &pair.second;
		item.flags = 0;
		item.size = pair.second.size();
		planned.push_back(std::move(item));
	}

	uint64_t cursor = 0;
	for (auto &item : planned) {
		uint64_t start = align_up(cursor, ALIGNMENT);
		item.relative_offset = start;
		cursor = start + item.size;
	}

	uint64_t index_size = 4;
	for (auto &item : planned) {
		index_size += 4 + padded_path_length(item.path) + 8 + 8 + 16 + 4;
	}
	uint64_t file_base = align_up(HEADER_SIZE + index_size, ALIGNMENT);

	const std::string temp_path = p_target + ".tmp";
	int out_fd = ::open(temp_path.c_str(), O_WRONLY | O_CREAT | O_TRUNC, 0644);
	if (out_fd < 0) {
		r_error = "couldn't create " + p_target;
		return false;
	}

	auto pwrite_all = [&](const uint8_t *data, uint64_t length, uint64_t offset) -> bool {
		size_t done = 0;
		while (done < length) {
			ssize_t put = pwrite(out_fd, data + done, size_t(length - done), off_t(offset + done));
			if (put <= 0) {
				return false;
			}
			done += size_t(put);
		}
		return true;
	};

	bool ok = true;
	uint8_t header[96];
	write_u32(header, MAGIC);
	write_u32(header + 4, SUPPORTED_PACK_VERSION);
	write_u32(header + 8, m_engine_major);
	write_u32(header + 12, m_engine_minor);
	write_u32(header + 16, m_engine_patch);
	write_u32(header + 20, 0); // unencrypted directory
	write_u64(header + 24, file_base);
	memset(header + 32, 0, 64);
	ok = ok && pwrite_all(header, sizeof(header), 0);

	if (ok) {
		std::vector<uint8_t> index(static_cast<size_t>(index_size));
		size_t at = 0;
		write_u32(index.data() + at, uint32_t(planned.size()));
		at += 4;
		for (auto &item : planned) {
			const std::string &final_path = item.path;
			uint64_t padded = padded_path_length(final_path);
			write_u32(index.data() + at, uint32_t(padded));
			at += 4;
			memcpy(index.data() + at, final_path.data(), final_path.size());
			memset(index.data() + at + final_path.size(), 0, size_t(padded) - final_path.size());
			at += size_t(padded);
			write_u64(index.data() + at, item.relative_offset);
			at += 8;
			write_u64(index.data() + at, item.size);
			at += 8;
			if (item.payload) {
				uint8_t digest[16];
				md5_buffer(item.payload->data(), item.payload->size(), digest);
				memcpy(index.data() + at, digest, 16);
			} else {
				memcpy(index.data() + at, item.entry->md5, 16);
			}
			at += 16;
			write_u32(index.data() + at, item.entry ? item.entry->flags : 0);
			at += 4;
		}
		ok = pwrite_all(index.data(), uint64_t(at), 96);
	}

	uint64_t written = file_base;
	if (ok) {
		for (auto &item : planned) {
			uint64_t start = file_base + item.relative_offset;
			if (start > written) {
				// Padding is left unwritten: the gap stays a hole, so only real payload bytes
				// are flushed instead of megabytes of zeros.
				written = start;
			}
			if (item.payload) {
				ok = pwrite_all(item.payload->data(), item.size, written);
			} else {
				uint64_t copied = 0;
				if (!sparseCopyRange(m_fd, out_fd, item.entry->offset, written, item.size,
						m_source_sparse, nullptr, nullptr, copied, r_error)) {
					if (r_error.empty()) {
						r_error = "Couldn't copy " + item.entry->path;
					}
					ok = false;
					break;
				}
				written += item.size;
			}
			if (!ok) {
				break;
			}
			if (item.payload) {
				written += item.size;
			}
			if (p_progress) {
				p_progress(int64_t(written), p_user);
			}
		}
	}

	if (ok) {
		ok = ftruncate(out_fd, off_t(file_base + cursor)) == 0;
		if (!ok) {
			r_error = "couldn't size the rewritten pack";
		}
	}
	close(out_fd);
	if (!ok) {
		unlink(temp_path.c_str());
		if (r_error.empty()) {
			r_error = "rewriting the pack failed";
		}
		return false;
	}
	// Flush to storage before publishing so a crash cannot leave a half-written pack.
	int sync_fd = ::open(temp_path.c_str(), O_RDONLY);
	if (sync_fd >= 0) {
		fsync(sync_fd);
		close(sync_fd);
	}
	// Verify the produced size matches the planned layout.
	struct stat produced{};
	if (stat(temp_path.c_str(), &produced) != 0 || uint64_t(produced.st_size) != file_base + cursor) {
		r_error = "rewritten pack size mismatch";
		unlink(temp_path.c_str());
		return false;
	}
	if (unlink(p_target.c_str()) != 0 && errno != ENOENT) {
		r_error = "couldn't replace " + p_target;
		unlink(temp_path.c_str());
		return false;
	}
	if (rename(temp_path.c_str(), p_target.c_str()) != 0) {
		r_error = "couldn't finalise the patched pack";
		unlink(temp_path.c_str());
		return false;
	}
	return true;
}

bool PckFile::writeNew(
		const std::string &p_target,
		const std::vector<std::pair<std::string, std::vector<uint8_t>>> &p_files,
		uint32_t p_major, uint32_t p_minor, uint32_t p_patch,
		std::string &r_error) {
	struct PlannedItem {
		const std::pair<std::string, std::vector<uint8_t>> *file;
		uint64_t relative_offset;
		uint64_t size;
	};
	uint64_t cursor = 0;
	std::vector<PlannedItem> planned;
	for (auto &file : p_files) {
		uint64_t start = align_up(cursor, ALIGNMENT);
		planned.push_back({ &file, start, file.second.size() });
		cursor = start + file.second.size();
	}

	uint64_t index_size = 4;
	for (auto &item : planned) {
		index_size += 4 + padded_path_length(item.file->first) + 8 + 8 + 16 + 4;
	}
	uint64_t file_base = align_up(HEADER_SIZE + index_size, ALIGNMENT);

	const std::string temp_path = p_target + ".tmp";
	int out_fd = ::open(temp_path.c_str(), O_WRONLY | O_CREAT | O_TRUNC, 0644);
	if (out_fd < 0) {
		r_error = "couldn't create " + p_target;
		return false;
	}
	auto pwrite_all = [&](const uint8_t *data, uint64_t length, uint64_t offset) -> bool {
		size_t done = 0;
		while (done < length) {
			ssize_t put = pwrite(out_fd, data + done, size_t(length - done), off_t(offset + done));
			if (put <= 0) {
				return false;
			}
			done += size_t(put);
		}
		return true;
	};
	bool ok = true;
	uint8_t header[96];
	write_u32(header, MAGIC);
	write_u32(header + 4, SUPPORTED_PACK_VERSION);
	write_u32(header + 8, p_major);
	write_u32(header + 12, p_minor);
	write_u32(header + 16, p_patch);
	write_u32(header + 20, 0);
	write_u64(header + 24, file_base);
	memset(header + 32, 0, 64);
	ok = pwrite_all(header, sizeof(header), 0);

	if (ok) {
		std::vector<uint8_t> index(static_cast<size_t>(index_size));
		size_t at = 0;
		write_u32(index.data() + at, uint32_t(planned.size()));
		at += 4;
		for (auto &item : planned) {
			uint64_t padded = padded_path_length(item.file->first);
			write_u32(index.data() + at, uint32_t(padded));
			at += 4;
			memcpy(index.data() + at, item.file->first.data(), item.file->first.size());
			memset(index.data() + at + item.file->first.size(), 0, size_t(padded) - item.file->first.size());
			at += size_t(padded);
			write_u64(index.data() + at, item.relative_offset);
			at += 8;
			write_u64(index.data() + at, item.size);
			at += 8;
			uint8_t digest[16];
			md5_buffer(item.file->second.data(), item.file->second.size(), digest);
			memcpy(index.data() + at, digest, 16);
			at += 16;
			write_u32(index.data() + at, 0);
			at += 4;
		}
		ok = pwrite_all(index.data(), uint64_t(at), 96);
	}

	if (ok) {
		uint64_t written = file_base;
		for (auto &item : planned) {
			uint64_t start = file_base + item.relative_offset;
			if (start > written) {
				written = start;
			}
			ok = ok && pwrite_all(item.file->second.data(), item.size, written);
			written += item.size;
		}
	}
	if (ok) {
		ok = ftruncate(out_fd, off_t(file_base + cursor)) == 0;
	}
	close(out_fd);
	if (!ok) {
		unlink(temp_path.c_str());
		r_error = "writing the pack failed";
		return false;
	}
	if (unlink(p_target.c_str()) != 0 && errno != ENOENT) {
		r_error = "couldn't replace " + p_target;
		unlink(temp_path.c_str());
		return false;
	}
	if (rename(temp_path.c_str(), p_target.c_str()) != 0) {
		r_error = "couldn't finalise the pack";
		unlink(temp_path.c_str());
		return false;
	}
	return true;
}

} // namespace utarcore
