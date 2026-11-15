#include "library_set.h"

#include "md5.h"

#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>
#include <zlib.h>

#include <algorithm>
#include <cctype>
#include <cstring>
#include <dirent.h>

namespace utarcore {

std::vector<std::string> elfExportedSymbolsFromBytes(const std::vector<uint8_t> &data);

namespace {

// Libraries that belong to the engine or its runtime dependencies rather than to a game
// extension. FMOD's core/studio libraries are packaged next to the extension that links them,
// and libutarcore is the runner itself, so none of them may be offered as GDExtension matches.
const char *ENGINE_LIBRARIES[] = {
	"libgodot_android.so",
	"libc++_shared.so",
	"libc++abi.so",
	"libandroid_support.so",
	"libmain.so",
	"libfmod.so",
	"libfmodstudio.so",
	"libutarcore.so",
};

constexpr uint32_t SHT_DYNSYM = 11;
constexpr size_t ELF64_HEADER_SIZE = 64;
constexpr size_t ELF64_SECTION_SIZE = 64;
constexpr size_t ELF64_SYMBOL_SIZE = 24;
constexpr uint64_t MAX_SECTION_BYTES = 8ULL * 1024 * 1024;
constexpr uint64_t MAX_SECTION_TABLE_BYTES = 8ULL * 1024 * 1024;

// NLSC v1 disk cache: ELF scan results keyed by APK identity.
constexpr uint32_t CACHE_MAGIC = 0x4E4C5343;
constexpr uint32_t CACHE_VERSION = 1;

bool isEngineLibrary(const std::string &name) {
	for (const char *candidate : ENGINE_LIBRARIES) {
		if (name == candidate) {
			return true;
		}
	}
	return false;
}

// --- blob helpers -----------------------------------------------------------

void blobWriteU32(std::vector<uint8_t> &out, uint32_t value) {
	out.push_back(uint8_t(value & 0xFF));
	out.push_back(uint8_t((value >> 8) & 0xFF));
	out.push_back(uint8_t((value >> 16) & 0xFF));
	out.push_back(uint8_t((value >> 24) & 0xFF));
}

void blobWriteU64(std::vector<uint8_t> &out, uint64_t value) {
	blobWriteU32(out, uint32_t(value & 0xFFFFFFFF));
	blobWriteU32(out, uint32_t(value >> 32));
}

void blobWriteString(std::vector<uint8_t> &out, const std::string &value) {
	blobWriteU32(out, uint32_t(value.size()));
	out.insert(out.end(), value.begin(), value.end());
}

struct BlobReader {
	const std::vector<uint8_t> &data;
	size_t at = 0;
	bool ok = true;

	uint32_t u32() {
		if (!ok || at + 4 > data.size()) {
			ok = false;
			return 0;
		}
		uint32_t value = uint32_t(data[at]) | (uint32_t(data[at + 1]) << 8) |
				(uint32_t(data[at + 2]) << 16) | (uint32_t(data[at + 3]) << 24);
		at += 4;
		return value;
	}

	std::string str() {
		uint32_t length = u32();
		if (!ok || at + length > data.size()) {
			ok = false;
			return std::string();
		}
		std::string value(reinterpret_cast<const char *>(data.data()) + at, length);
		at += length;
		return value;
	}
};

// --- ELF64 dynsym scan ------------------------------------------------------

bool readFully(int fd, uint64_t offset, uint64_t length, std::vector<uint8_t> &out) {
	if (length == 0) {
		return true;
	}
	out.resize(size_t(length));
	size_t done = 0;
	while (done < size_t(length)) {
		ssize_t got = pread(fd, out.data() + done, size_t(length - done), off_t(offset + done));
		if (got <= 0) {
			return false;
		}
		done += size_t(got);
	}
	return true;
}

std::vector<std::string> elfExportedSymbols(const std::string &path) {
	std::vector<std::string> symbols;
	int fd = open(path.c_str(), O_RDONLY);
	if (fd < 0) {
		return symbols;
	}
	struct stat stat_result{};
	if (fstat(fd, &stat_result) != 0 || uint64_t(stat_result.st_size) < ELF64_HEADER_SIZE) {
		close(fd);
		return symbols;
	}
	std::vector<uint8_t> header;
	if (!readFully(fd, 0, ELF64_HEADER_SIZE, header) ||
			header[0] != 0x7f || header[1] != 'E' || header[2] != 'L' || header[3] != 'F' ||
			header[4] != 2 || header[5] != 1) { // 64-bit little-endian only (arm64-v8a / x86_64)
		close(fd);
		return symbols;
	}
	uint64_t section_table_offset =
			uint64_t(header[0x28]) | (uint64_t(header[0x29]) << 8) | (uint64_t(header[0x2A]) << 16) |
			(uint64_t(header[0x2B]) << 24) | (uint64_t(header[0x2C]) << 32) | (uint64_t(header[0x2D]) << 40) |
			(uint64_t(header[0x2E]) << 48) | (uint64_t(header[0x2F]) << 56);
	uint32_t section_entry_size = uint32_t(header[0x3A]) | (uint32_t(header[0x3B]) << 8);
	uint32_t section_count = uint32_t(header[0x3C]) | (uint32_t(header[0x3D]) << 8);
	if (section_table_offset == 0 || section_entry_size < ELF64_SECTION_SIZE || section_count == 0) {
		close(fd);
		return symbols;
	}
	uint64_t table_size = uint64_t(section_entry_size) * section_count;
	if (table_size > MAX_SECTION_TABLE_BYTES) {
		close(fd);
		return symbols;
	}
	std::vector<uint8_t> table;
	if (!readFully(fd, section_table_offset, table_size, table)) {
		close(fd);
		return symbols;
	}

	auto sectionFieldU32 = [&](uint32_t index, size_t offset) -> uint32_t {
		size_t at = size_t(index) * section_entry_size + offset;
		return uint32_t(table[at]) | (uint32_t(table[at + 1]) << 8) | (uint32_t(table[at + 2]) << 16) |
				(uint32_t(table[at + 3]) << 24);
	};
	auto sectionFieldI32 = [&](uint32_t index, size_t offset) -> int32_t {
		return int32_t(sectionFieldU32(index, offset));
	};
	auto sectionOffset = [&](uint32_t index) -> uint64_t {
		size_t at = size_t(index) * section_entry_size + 24;
		uint64_t value = 0;
		for (int b = 0; b < 8; b++) {
			value |= uint64_t(table[at + b]) << (8 * b);
		}
		return value;
	};
	auto sectionSize = [&](uint32_t index) -> uint64_t {
		size_t at = size_t(index) * section_entry_size + 32;
		uint64_t value = 0;
		for (int b = 0; b < 8; b++) {
			value |= uint64_t(table[at + b]) << (8 * b);
		}
		return value;
	};

	for (uint32_t index = 0; index < section_count; index++) {
		if (sectionFieldU32(index, 4) != SHT_DYNSYM) {
			continue;
		}
		int32_t link = sectionFieldI32(index, 40);
		uint64_t names_offset = sectionOffset(uint32_t(link));
		uint64_t names_size = sectionSize(uint32_t(link));
		uint64_t entries_offset = sectionOffset(index);
		uint64_t entries_size = sectionSize(index);
		if (link < 0 || names_offset == 0 || names_size == 0 || names_size > MAX_SECTION_BYTES ||
				entries_offset == 0 || entries_size == 0 || entries_size > MAX_SECTION_BYTES) {
			continue;
		}
		std::vector<uint8_t> names;
		std::vector<uint8_t> entries;
		if (!readFully(fd, names_offset, names_size, names) ||
				!readFully(fd, entries_offset, entries_size, entries)) {
			continue;
		}
		for (size_t position = 0; position + ELF64_SYMBOL_SIZE <= entries.size(); position += ELF64_SYMBOL_SIZE) {
			uint32_t name_offset = uint32_t(entries[position]) | (uint32_t(entries[position + 1]) << 8) |
					(uint32_t(entries[position + 2]) << 16) | (uint32_t(entries[position + 3]) << 24);
			uint16_t shndx = uint16_t(entries[position + 6]) | (uint16_t(entries[position + 7]) << 8);
			if (shndx == 0 || name_offset >= names.size()) {
				continue; // undefined symbol or out-of-range name
			}
			size_t end = name_offset;
			while (end < names.size() && names[end] != 0) {
				end++;
			}
			if (end > name_offset) {
				symbols.emplace_back(reinterpret_cast<const char *>(names.data()) + name_offset, end - name_offset);
			}
		}
	}
	close(fd);
	return symbols;
}

// --- APK zip scan -----------------------------------------------------------

struct ZipEntryInfo {
	std::string name;
	uint16_t method = 0;
	uint64_t compressed_size = 0;
	uint64_t uncompressed_size = 0;
	uint64_t local_offset = 0;
};

std::vector<uint8_t> zipReadData(int fd, const ZipEntryInfo &info) {
	std::vector<uint8_t> local_header(30);
	if (!readFully(fd, info.local_offset, 30, local_header)) {
		return {};
	}
	if (!(local_header[0] == 'P' && local_header[1] == 'K' && local_header[2] == 3 && local_header[3] == 4)) {
		return {};
	}
	uint16_t local_name_length = uint16_t(local_header[26]) | (uint16_t(local_header[27]) << 8);
	uint16_t local_extra_length = uint16_t(local_header[28]) | (uint16_t(local_header[29]) << 8);
	uint64_t data_offset = info.local_offset + 30 + local_name_length + local_extra_length;

	std::vector<uint8_t> compressed;
	if (!readFully(fd, data_offset, info.compressed_size, compressed)) {
		return {};
	}
	if (info.method == 0) { // stored
		return compressed;
	}
	if (info.method != 8) { // unknown compression
		return {};
	}
	std::vector<uint8_t> out(size_t(info.uncompressed_size));
	z_stream stream{};
	if (inflateInit2(&stream, -15) != Z_OK) { // raw deflate (no zlib header in zip)
		return {};
	}
	stream.next_in = compressed.data();
	stream.avail_in = uInt(compressed.size());
	stream.next_out = out.data();
	stream.avail_out = uInt(out.size());
	int status = Z_OK;
	while (status != Z_STREAM_END) {
		status = inflate(&stream, Z_NO_FLUSH);
		if (status != Z_OK && status != Z_STREAM_END && status != Z_BUF_ERROR) {
			inflateEnd(&stream);
			return {};
		}
		if (status == Z_BUF_ERROR && stream.avail_in == 0) {
			break; // truncated entry; return what we have
		}
		if (stream.avail_in == 0 && stream.avail_out != 0 && status != Z_STREAM_END) {
			break;
		}
	}
	inflateEnd(&stream);
	out.resize(stream.total_out);
	return out;
}

bool zipScan(const std::string &path, const std::string &abi_prefix, std::vector<ScannedLibrary> &r_out) {
	int fd = open(path.c_str(), O_RDONLY);
	if (fd < 0) {
		return false;
	}
	struct stat stat_result{};
	if (fstat(fd, &stat_result) != 0) {
		close(fd);
		return false;
	}
	uint64_t file_size = uint64_t(stat_result.st_size);
	if (file_size < 22) {
		close(fd);
		return false;
	}

	// Locate the end-of-central-directory record in the last 64 KiB + 22 bytes.
	uint64_t scan_length = std::min<uint64_t>(file_size, 64ULL * 1024 + 22);
	std::vector<uint8_t> tail;
	if (!readFully(fd, file_size - scan_length, scan_length, tail)) {
		close(fd);
		return false;
	}
	int64_t eocd = -1;
	for (int64_t i = int64_t(tail.size()) - 22; i >= 0; i--) {
		if (tail[i] == 'P' && tail[i + 1] == 'K' && tail[i + 2] == 5 && tail[i + 3] == 6) {
			eocd = i;
			break;
		}
	}
	if (eocd < 0) {
		close(fd);
		return false;
	}
	uint32_t entry_count = uint32_t(tail[eocd + 10]) | (uint32_t(tail[eocd + 11]) << 8);
	uint64_t central_size = uint64_t(tail[eocd + 12]) | (uint64_t(tail[eocd + 13]) << 8) |
			(uint64_t(tail[eocd + 14]) << 16) | (uint64_t(tail[eocd + 15]) << 24);
	uint64_t central_offset = uint64_t(tail[eocd + 16]) | (uint64_t(tail[eocd + 17]) << 8) |
			(uint64_t(tail[eocd + 18]) << 16) | (uint64_t(tail[eocd + 19]) << 24);
	if (central_offset + central_size > file_size) {
		close(fd);
		return false;
	}

	std::vector<uint8_t> central;
	if (!readFully(fd, central_offset, central_size, central)) {
		close(fd);
		return false;
	}

	size_t at = 0;
	for (uint32_t i = 0; i < entry_count && at + 46 <= central.size(); i++) {
		if (!(central[at] == 'P' && central[at + 1] == 'K' && central[at + 2] == 1 && central[at + 3] == 2)) {
			break;
		}
		ZipEntryInfo info;
		info.method = uint16_t(central[at + 10]) | (uint16_t(central[at + 11]) << 8);
		info.compressed_size = uint64_t(central[at + 20]) | (uint64_t(central[at + 21]) << 8) |
				(uint64_t(central[at + 22]) << 16) | (uint64_t(central[at + 23]) << 24);
		info.uncompressed_size = uint64_t(central[at + 24]) | (uint64_t(central[at + 25]) << 8) |
				(uint64_t(central[at + 26]) << 16) | (uint64_t(central[at + 27]) << 24);
		uint16_t name_length = uint16_t(central[at + 28]) | (uint16_t(central[at + 29]) << 8);
		uint16_t extra_length = uint16_t(central[at + 30]) | (uint16_t(central[at + 31]) << 8);
		uint16_t comment_length = uint16_t(central[at + 32]) | (uint16_t(central[at + 33]) << 8);
		info.local_offset = uint64_t(central[at + 42]) | (uint64_t(central[at + 43]) << 8) |
				(uint64_t(central[at + 44]) << 16) | (uint64_t(central[at + 45]) << 24);
		if (at + 46 + name_length > central.size()) {
			break;
		}
		info.name.assign(reinterpret_cast<const char *>(central.data()) + at + 46, name_length);
		at += 46 + size_t(name_length) + extra_length + comment_length;

		if (info.name.compare(0, abi_prefix.size(), abi_prefix) != 0 ||
				info.name.size() <= abi_prefix.size() ||
				info.name.compare(info.name.size() - 3, 3, ".so") != 0) {
			continue;
		}
		std::string file_name = info.name.substr(abi_prefix.size());
		if (file_name.find('/') != std::string::npos || isEngineLibrary(file_name)) {
			continue;
		}
		if (std::any_of(r_out.begin(), r_out.end(), [&](const ScannedLibrary &lib) { return lib.fileName == file_name; })) {
			continue;
		}
		std::vector<uint8_t> data = zipReadData(fd, info);
		if (data.empty()) {
			continue;
		}
		// ELF symbol scan happens on the extracted bytes below.
		r_out.push_back({ file_name, elfExportedSymbolsFromBytes(data) });
	}
	close(fd);
	return true;
}

// --- disk cache -------------------------------------------------------------

std::string cacheKey(const std::string &abi, const std::vector<std::string> &apk_paths) {
	std::vector<uint8_t> digest_input;
	blobWriteString(digest_input, abi);
	std::vector<std::string> sorted = apk_paths;
	std::sort(sorted.begin(), sorted.end());
	for (const std::string &path : sorted) {
		blobWriteString(digest_input, path);
		struct stat stat_result{};
		if (stat(path.c_str(), &stat_result) == 0) {
			blobWriteString(digest_input, std::to_string(uint64_t(stat_result.st_size)));
			blobWriteString(digest_input, std::to_string(uint64_t(stat_result.st_mtime * 1000)));
		} else {
			blobWriteString(digest_input, "missing");
		}
	}
	uint8_t digest[16];
	md5_buffer(digest_input.data(), digest_input.size(), digest);
	return md5_hex(digest);
}

std::vector<uint8_t> readFileBytes(const std::string &path) {
	int fd = open(path.c_str(), O_RDONLY);
	if (fd < 0) {
		return {};
	}
	std::vector<uint8_t> out;
	uint8_t buffer[1 << 16];
	while (true) {
		ssize_t got = read(fd, buffer, sizeof(buffer));
		if (got < 0) {
			close(fd);
			return {};
		}
		if (got == 0) {
			break;
		}
		out.insert(out.end(), buffer, buffer + got);
	}
	close(fd);
	return out;
}

} // namespace

// ELF scan over already-extracted bytes (used by the zip scanner).
std::vector<std::string> elfExportedSymbolsFromBytes(const std::vector<uint8_t> &data) {
	std::vector<std::string> symbols;
	if (data.size() < ELF64_HEADER_SIZE) {
		return symbols;
	}
	auto read_u64_at = [&](size_t at) {
		uint64_t value = 0;
		for (int b = 0; b < 8; b++) {
			value |= uint64_t(data[at + b]) << (8 * b);
		}
		return value;
	};
	auto read_u32_at = [&](size_t at) {
		return uint32_t(data[at]) | (uint32_t(data[at + 1]) << 8) | (uint32_t(data[at + 2]) << 16) |
				(uint32_t(data[at + 3]) << 24);
	};
	auto read_u16_at = [&](size_t at) {
		return uint16_t(data[at]) | (uint16_t(data[at + 1]) << 8);
	};
	if (data[0] != 0x7f || data[1] != 'E' || data[2] != 'L' || data[3] != 'F' || data[4] != 2 || data[5] != 1) {
		return symbols;
	}
	uint64_t section_table_offset = read_u64_at(0x28);
	uint32_t section_entry_size = read_u16_at(0x3A);
	uint32_t section_count = read_u16_at(0x3C);
	if (section_table_offset == 0 || section_entry_size < ELF64_SECTION_SIZE || section_count == 0) {
		return symbols;
	}
	uint64_t table_size = uint64_t(section_entry_size) * section_count;
	if (table_size > MAX_SECTION_TABLE_BYTES || section_table_offset + table_size > data.size()) {
		return symbols;
	}
	auto section_at = [&](uint32_t index) -> size_t {
		return size_t(index) * section_entry_size;
	};
	for (uint32_t index = 0; index < section_count; index++) {
		size_t section = section_at(index);
		if (read_u32_at(section + 4) != SHT_DYNSYM) {
			continue;
		}
		int32_t link = int32_t(read_u32_at(section + 40));
		if (link < 0) {
			continue;
		}
		size_t names_section = section_at(uint32_t(link));
		uint64_t names_offset = read_u64_at(names_section + 24);
		uint64_t names_size = read_u64_at(names_section + 32);
		uint64_t entries_offset = read_u64_at(section + 24);
		uint64_t entries_size = read_u64_at(section + 32);
		if (names_offset == 0 || names_size == 0 || names_size > MAX_SECTION_BYTES ||
				entries_offset == 0 || entries_size == 0 || entries_size > MAX_SECTION_BYTES ||
				names_offset + names_size > data.size() || entries_offset + entries_size > data.size()) {
			continue;
		}
		for (uint64_t position = 0; position + ELF64_SYMBOL_SIZE <= entries_size; position += ELF64_SYMBOL_SIZE) {
			size_t at = size_t(entries_offset + position);
			uint32_t name_offset = read_u32_at(at);
			uint16_t shndx = read_u16_at(at + 6);
			if (shndx == 0 || name_offset >= names_size) {
				continue;
			}
			size_t end = size_t(names_offset + name_offset);
			size_t start = size_t(names_offset + name_offset);
			while (end < size_t(names_offset + names_size) && data[end] != 0) {
				end++;
			}
			if (end > start) {
				symbols.emplace_back(reinterpret_cast<const char *>(data.data()) + start, end - start);
			}
		}
	}
	return symbols;
}

// --- LibrarySet -------------------------------------------------------------

std::vector<uint8_t> LibrarySet::toBlob() const {
	std::vector<uint8_t> out;
	blobWriteU32(out, uint32_t(abi_libraries.size()));
	for (const auto &group : abi_libraries) {
		blobWriteString(out, group.first);
		blobWriteU32(out, uint32_t(group.second.size()));
		for (const ScannedLibrary &library : group.second) {
			blobWriteString(out, library.fileName);
			blobWriteU32(out, uint32_t(library.entrySymbols.size()));
			for (const std::string &symbol : library.entrySymbols) {
				blobWriteString(out, symbol);
			}
		}
	}
	return out;
}

LibrarySet LibrarySet::fromBlob(const std::vector<uint8_t> &p_blob) {
	LibrarySet result;
	BlobReader reader{ p_blob };
	uint32_t abi_count = reader.u32();
	if (abi_count > 8) {
		return result;
	}
	for (uint32_t abi = 0; abi < abi_count && reader.ok; abi++) {
		std::string abi_name = reader.str();
		uint32_t lib_count = reader.u32();
		if (lib_count > 1024) {
			return LibrarySet();
		}
		std::vector<ScannedLibrary> libraries;
		for (uint32_t lib = 0; lib < lib_count && reader.ok; lib++) {
			ScannedLibrary library;
			library.fileName = reader.str();
			uint32_t sym_count = reader.u32();
			if (sym_count > 65536) {
				return LibrarySet();
			}
			for (uint32_t sym = 0; sym < sym_count && reader.ok; sym++) {
				library.entrySymbols.push_back(reader.str());
			}
			if (!reader.ok) {
				break;
			}
			libraries.push_back(std::move(library));
		}
		if (!reader.ok) {
			return LibrarySet();
		}
		result.abi_libraries.emplace_back(std::move(abi_name), std::move(libraries));
	}
	if (!reader.ok) {
		return LibrarySet();
	}
	return result;
}

LibrarySet LibrarySet::fromApks(const std::vector<std::string> &p_apk_paths, const std::string &p_abi_directory,
		const std::string &p_cache_dir) {
	if (!p_cache_dir.empty()) {
		std::string cache_path = p_cache_dir + "/native_libs_" + cacheKey(p_abi_directory, p_apk_paths) + ".cache";
		std::vector<uint8_t> cached = readFileBytes(cache_path);
		if (cached.size() > 8) {
			BlobReader reader{ cached };
			if (reader.u32() == CACHE_MAGIC && reader.u32() == CACHE_VERSION) {
				std::vector<uint8_t> payload(cached.begin() + 8, cached.end());
				LibrarySet set = LibrarySet::fromBlob(payload);
				if (!set.empty()) {
					return set;
				}
			}
		}
	}

	LibrarySet result;
	std::vector<ScannedLibrary> libraries;
	for (const std::string &apk : p_apk_paths) {
		zipScan(apk, "lib/" + p_abi_directory + "/", libraries);
	}
	std::sort(libraries.begin(), libraries.end(), [](const ScannedLibrary &a, const ScannedLibrary &b) {
		return a.fileName < b.fileName;
	});
	if (!libraries.empty()) {
		result.abi_libraries.emplace_back(p_abi_directory, std::move(libraries));
	}

	if (!p_cache_dir.empty() && !result.empty()) {
		std::string cache_path = p_cache_dir + "/native_libs_" + cacheKey(p_abi_directory, p_apk_paths) + ".cache";
		std::vector<uint8_t> blob = result.toBlob();
		std::vector<uint8_t> file_data;
		blobWriteU32(file_data, CACHE_MAGIC);
		blobWriteU32(file_data, CACHE_VERSION);
		file_data.insert(file_data.end(), blob.begin(), blob.end());
		int fd = open(cache_path.c_str(), O_WRONLY | O_CREAT | O_TRUNC, 0644);
		if (fd >= 0) {
			size_t done = 0;
			while (done < file_data.size()) {
				ssize_t put = write(fd, file_data.data() + done, file_data.size() - done);
				if (put <= 0) {
					break;
				}
				done += size_t(put);
			}
			close(fd);
		}
	}
	return result;
}

LibrarySet LibrarySet::fromDirectory(const std::string &p_directory, const std::string &p_abi_directory) {
	LibrarySet result;
	DIR *dir = opendir(p_directory.c_str());
	if (dir == nullptr) {
		return result;
	}
	std::vector<std::string> names;
	while (dirent *entry = readdir(dir)) {
		std::string name = entry->d_name;
		if (name.size() > 3 && name.substr(name.size() - 3) == ".so" && !isEngineLibrary(name)) {
			names.push_back(name);
		}
	}
	closedir(dir);
	std::sort(names.begin(), names.end());
	std::vector<ScannedLibrary> libraries;
	for (const std::string &name : names) {
		std::vector<std::string> symbols = elfExportedSymbols(p_directory + "/" + name);
		libraries.push_back({ name, std::move(symbols) });
	}
	if (!libraries.empty()) {
		result.abi_libraries.emplace_back(p_abi_directory, std::move(libraries));
	}
	return result;
}

} // namespace utarcore
