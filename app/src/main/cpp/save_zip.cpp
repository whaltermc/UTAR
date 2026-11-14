#include "save_zip.h"

#include <dirent.h>
#include <fcntl.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <time.h>
#include <unistd.h>
#include <zlib.h>

#include <algorithm>
#include <cstring>
#include <vector>

namespace utarcore {

namespace {

constexpr size_t BUFFER_SIZE = 1 << 16;

// Only game-owned data travels in the archive; the same scope is cleared on restore.
bool excluded(const std::string &relative) {
	auto startsWith = [&](const char *prefix) {
		return relative.compare(0, strlen(prefix), prefix) == 0;
	};
	if (relative == "game" || startsWith("game/")) return true;
	if (relative == "logs" || startsWith("logs/")) return true;
	if (relative == "session.log" || relative == "session.log.1" || relative == "game.pid") return true;
	if (relative == ".godot/shader_cache" || startsWith(".godot/shader_cache/")) return true;
	return false;
}

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

// --- zip output -------------------------------------------------------------

void putU16(std::vector<uint8_t> &out, uint16_t value) {
	out.push_back(uint8_t(value & 0xFF));
	out.push_back(uint8_t(value >> 8));
}

void putU32(std::vector<uint8_t> &out, uint32_t value) {
	putU16(out, uint16_t(value & 0xFFFF));
	putU16(out, uint16_t(value >> 16));
}

void putU64(std::vector<uint8_t> &out, uint64_t value) {
	putU32(out, uint32_t(value & 0xFFFFFFFF));
	putU32(out, uint32_t(value >> 32));
}

void putBytes(std::vector<uint8_t> &out, const void *data, size_t length) {
	const uint8_t *bytes = reinterpret_cast<const uint8_t *>(data);
	out.insert(out.end(), bytes, bytes + length);
}

void dosDateTime(uint64_t mtime_millis, uint16_t &r_time, uint16_t &r_date) {
	time_t seconds = time_t(mtime_millis / 1000);
	tm broken{};
	localtime_r(&seconds, &broken);
	if (broken.tm_year + 1900 < 1980) {
		r_time = 0;
		r_date = (1 << 5) | 1; // 1980-01-01
		return;
	}
	r_time = uint16_t((broken.tm_hour << 11) | (broken.tm_min << 5) | (broken.tm_sec >> 1));
	r_date = uint16_t(((broken.tm_year + 1900 - 1980) << 9) | ((broken.tm_mon + 1) << 5) | broken.tm_mday);
}

bool writeAll(int fd, const std::vector<uint8_t> &data, uint64_t offset) {
	size_t done = 0;
	while (done < data.size()) {
		ssize_t put = pwrite(fd, data.data() + done, data.size() - done, off_t(offset + done));
		if (put <= 0) {
			return false;
		}
		done += size_t(put);
	}
	return true;
}

// --- zip input --------------------------------------------------------------

struct ZipStreamEntry {
	std::string name;
	uint16_t flags = 0;
	uint16_t method = 0;
	uint32_t crc = 0;
	uint64_t compressed_size = 0;
	uint64_t uncompressed_size = 0;
	uint64_t data_offset = 0; // absolute offset of the deflate/stored payload
};

/** Reads the next local file entry header at [p_at]; returns the offset after the header+name+extra. */
bool zipStreamNext(int fd, uint64_t file_size, uint64_t &r_at, ZipStreamEntry &r_entry) {
	while (r_at + 4 <= file_size) {
		std::vector<uint8_t> header;
		if (!readFully(fd, r_at, 30, header)) {
			return false;
		}
		if (!(header[0] == 'P' && header[1] == 'K' && header[2] == 3 && header[3] == 4)) {
			return false; // end of entries
		}
		r_entry.flags = uint16_t(header[6]) | (uint16_t(header[7]) << 8);
		r_entry.method = uint16_t(header[8]) | (uint16_t(header[9]) << 8);
		r_entry.crc = uint32_t(header[14]) | (uint32_t(header[15]) << 8) | (uint32_t(header[16]) << 16) |
				(uint32_t(header[17]) << 24);
		r_entry.compressed_size = uint64_t(header[18]) | (uint64_t(header[19]) << 8) |
				(uint64_t(header[20]) << 16) | (uint64_t(header[21]) << 24);
		r_entry.uncompressed_size = uint64_t(header[22]) | (uint64_t(header[23]) << 8) |
				(uint64_t(header[24]) << 16) | (uint64_t(header[25]) << 24);
		uint16_t name_length = uint16_t(header[26]) | (uint16_t(header[27]) << 8);
		uint16_t extra_length = uint16_t(header[28]) | (uint16_t(header[29]) << 8);
		if (name_length == 0 || r_at + 30 + name_length + extra_length > file_size) {
			return false;
		}
		std::vector<uint8_t> name;
		if (!readFully(fd, r_at + 30, name_length, name)) {
			return false;
		}
		r_entry.name.assign(reinterpret_cast<const char *>(name.data()), name.size());
		r_entry.data_offset = r_at + 30 + name_length + extra_length;
		return true;
	}
	return false;
}

/** Inflates/stores the payload of [p_entry] into [r_out] (raw deflate for method 8). */
bool zipReadPayload(int fd, const ZipStreamEntry &entry, std::vector<uint8_t> &r_out, uint64_t &r_consumed) {
	if (entry.method == 0) {
		uint64_t size = entry.compressed_size;
		if (size == 0 || (entry.flags & 0x8) != 0) {
			return false; // stored with unknown size is not produced by our writer
		}
		r_out.resize(size_t(size));
		if (size > 0 && !readFully(fd, entry.data_offset, size, r_out)) {
			return false;
		}
		r_consumed = size;
		return true;
	}
	if (entry.method != 8) {
		return false;
	}
	z_stream stream{};
	if (inflateInit2(&stream, -15) != Z_OK) {
		return false;
	}
	r_out.clear();
	std::vector<uint8_t> in(BUFFER_SIZE);
	std::vector<uint8_t> chunk(BUFFER_SIZE);
	uint64_t consumed = 0;
	bool ok = false;
	while (true) {
		ssize_t got = pread(fd, in.data(), BUFFER_SIZE, off_t(entry.data_offset + consumed));
		if (got <= 0) {
			break;
		}
		stream.next_in = in.data();
		stream.avail_in = uInt(got);
		consumed += uint64_t(got);
		while (stream.avail_in > 0) {
			stream.next_out = chunk.data();
			stream.avail_out = uInt(chunk.size());
			int status = inflate(&stream, Z_NO_FLUSH);
			size_t produced = chunk.size() - stream.avail_out;
			r_out.insert(r_out.end(), chunk.begin(), chunk.begin() + int64_t(produced));
			if (status == Z_STREAM_END) {
				ok = true;
				break;
			}
			if (status != Z_OK && status != Z_BUF_ERROR) {
				ok = false;
				break;
			}
		}
		if (ok || got < ssize_t(BUFFER_SIZE)) {
			break;
		}
	}
	inflateEnd(&stream);
	r_consumed = consumed;
	return ok;
}

/** Turns an archive entry name into a path under [p_root], rejecting traversal outside it. */
bool safePath(const std::string &root, const std::string &name, std::string &r_relative, std::string &r_absolute) {
	std::vector<std::string> parts;
	std::string current;
	for (char c : name) {
		if (c == '/' || c == '\\') {
			if (!current.empty() && current != "." && current != "..") {
				parts.push_back(current);
			}
			current.clear();
		} else {
			current.push_back(c);
		}
	}
	if (!current.empty() && current != "." && current != "..") {
		parts.push_back(current);
	}
	if (parts.empty()) {
		return false;
	}
	std::string relative;
	for (size_t i = 0; i < parts.size(); i++) {
		if (i > 0) {
			relative += '/';
		}
		relative += parts[i];
	}
	std::string absolute = root + "/" + relative;
	struct stat stat_result{};
	if (lstat(absolute.c_str(), &stat_result) == 0 && S_ISLNK(stat_result.st_mode)) {
		return false; // never write through a symlink
	}
	r_relative = relative;
	r_absolute = absolute;
	return true;
}

bool makeParents(const std::string &path) {
	size_t at = path.find('/');
	while (at != std::string::npos) {
		std::string parent = path.substr(0, at);
		if (!parent.empty()) {
			mkdir(parent.c_str(), 0755);
		}
		at = path.find('/', at + 1);
	}
	return true;
}

bool removeRecursively(const std::string &path) {
	struct stat stat_result{};
	if (lstat(path.c_str(), &stat_result) != 0) {
		return true;
	}
	if (!S_ISDIR(stat_result.st_mode)) {
		return unlink(path.c_str()) == 0;
	}
	DIR *dir = opendir(path.c_str());
	if (dir != nullptr) {
		while (dirent *entry = readdir(dir)) {
			std::string name = entry->d_name;
			if (name == "." || name == "..") {
				continue;
			}
			removeRecursively(path + "/" + name);
		}
		closedir(dir);
	}
	return rmdir(path.c_str()) == 0;
}

void walkFiles(const std::string &dir, const std::string &prefix, std::vector<std::string> &r_files) {
	DIR *handle = opendir(dir.c_str());
	if (handle == nullptr) {
		return;
	}
	std::vector<std::string> names;
	while (dirent *entry = readdir(handle)) {
		std::string name = entry->d_name;
		if (name == "." || name == "..") {
			continue;
		}
		names.push_back(name);
	}
	closedir(handle);
	std::sort(names.begin(), names.end());
	for (const std::string &name : names) {
		std::string relative = prefix.empty() ? name : prefix + "/" + name;
		if (excluded(relative)) {
			continue;
		}
		std::string absolute = dir + "/" + name;
		struct stat stat_result{};
		if (lstat(absolute.c_str(), &stat_result) != 0) {
			continue;
		}
		if (S_ISDIR(stat_result.st_mode)) {
			walkFiles(absolute, relative, r_files);
		} else if (S_ISREG(stat_result.st_mode)) {
			r_files.push_back(absolute);
		}
	}
}

} // namespace

int64_t saveBackupZip(const std::string &p_root_dir, int p_fd, SaveZipProgress p_progress, void *p_user,
		std::string &r_error) {
	std::vector<std::string> files;
	walkFiles(p_root_dir, "", files);

	uint64_t total = 0;
	std::vector<uint64_t> sizes;
	std::vector<uint64_t> mtimes;
	for (const std::string &file : files) {
		struct stat stat_result{};
		if (stat(file.c_str(), &stat_result) != 0) {
			sizes.push_back(0);
			mtimes.push_back(0);
			continue;
		}
		sizes.push_back(uint64_t(stat_result.st_size));
		mtimes.push_back(uint64_t(stat_result.st_mtime) * 1000);
		total += sizes.back();
	}

	uint64_t written = 0;
	uint64_t copied = 0;
	std::vector<uint8_t> central;
	std::vector<uint8_t> local(BUFFER_SIZE);

	for (size_t i = 0; i < files.size(); i++) {
		const std::string &absolute = files[i];
		std::string name = absolute;
		if (name.compare(0, p_root_dir.size() + 1, p_root_dir + "/") == 0) {
			name = absolute.substr(p_root_dir.size() + 1);
		}
		if (excluded(name)) {
			continue;
		}

		int src = open(absolute.c_str(), O_RDONLY);
		if (src < 0) {
			continue;
		}
		z_stream stream{};
		if (deflateInit2(&stream, Z_DEFAULT_COMPRESSION, Z_DEFLATED, -15, 8, Z_DEFAULT_STRATEGY) != Z_OK) {
			close(src);
			r_error = "Couldn't compress backup data.";
			return -1;
		}
		std::vector<uint8_t> deflated;
		while (true) {
			ssize_t got = read(src, local.data(), local.size());
			if (got < 0) {
				break;
			}
			bool last = got < ssize_t(local.size());
			stream.next_in = local.data();
			stream.avail_in = uInt(got);
			std::vector<uint8_t> out(BUFFER_SIZE);
			int status = Z_OK;
			do {
				stream.next_out = out.data();
				stream.avail_out = uInt(out.size());
				status = deflate(&stream, last ? Z_FINISH : Z_NO_FLUSH);
				deflated.insert(deflated.end(), out.begin(), out.begin() + int64_t(out.size() - stream.avail_out));
			} while (status == Z_OK && stream.avail_in > 0);
			if (got < 0 || last || status == Z_STREAM_END) {
				break;
			}
		}
		deflateEnd(&stream);
		close(src);

		uint32_t crc = crc32(0, Z_NULL, 0);
		// Recompute crc from a second read to avoid buffering whole files.
		src = open(absolute.c_str(), O_RDONLY);
		if (src < 0) {
			continue;
		}
		while (true) {
			ssize_t got = read(src, local.data(), local.size());
			if (got <= 0) {
				break;
			}
			crc = crc32(crc, local.data(), uInt(got));
		}
		close(src);

		uint16_t dos_time = 0;
		uint16_t dos_date = 0;
		dosDateTime(mtimes[i], dos_time, dos_date);

		uint64_t entry_offset = written;
		std::vector<uint8_t> header;
		putU32(header, 0x04034b50);
		putU16(header, 20);      // version needed
		putU16(header, 0);       // flags
		putU16(header, 8);       // method: deflate
		putU16(header, dos_time);
		putU16(header, dos_date);
		putU32(header, crc);
		putU32(header, uint32_t(deflated.size()));
		putU32(header, uint32_t(sizes[i]));
		putU16(header, uint16_t(name.size()));
		putU16(header, 0);       // extra length
		putBytes(header, name.data(), name.size());
		if (!writeAll(p_fd, header, written)) {
			r_error = "Couldn't write the backup file.";
			return -1;
		}
		written += header.size();
		if (!writeAll(p_fd, deflated, written)) {
			r_error = "Couldn't write the backup file.";
			return -1;
		}
		written += deflated.size();

		putU32(central, 0x02014b50);
		putU16(central, 20);     // version made by
		putU16(central, 20);     // version needed
		putU16(central, 0);      // flags
		putU16(central, 8);      // method
		putU16(central, dos_time);
		putU16(central, dos_date);
		putU32(central, crc);
		putU32(central, uint32_t(deflated.size()));
		putU32(central, uint32_t(sizes[i]));
		putU16(central, uint16_t(name.size()));
		putU16(central, 0);      // extra
		putU16(central, 0);      // comment
		putU16(central, 0);      // disk start
		putU16(central, 0);      // internal attrs
		putU32(central, 0);      // external attrs
		putU32(central, uint32_t(entry_offset));
		putBytes(central, name.data(), name.size());

		copied += sizes[i];
		if (p_progress != nullptr && total > 0) {
			p_progress(int64_t(copied), int64_t(total), p_user);
		}
	}

	uint64_t central_offset = written;
	if (!writeAll(p_fd, central, written)) {
		r_error = "Couldn't write the backup file.";
		return -1;
	}
	written += central.size();

	std::vector<uint8_t> eocd;
	putU32(eocd, 0x06054b50);
	putU16(eocd, 0);         // disk
	putU16(eocd, 0);         // central disk
	putU16(eocd, uint16_t(files.size()));
	putU16(eocd, uint16_t(files.size()));
	putU32(eocd, uint32_t(written - central_offset));
	putU32(eocd, uint32_t(central_offset));
	putU16(eocd, 0);         // comment length
	if (!writeAll(p_fd, eocd, written)) {
		r_error = "Couldn't write the backup file.";
		return -1;
	}
	return int64_t(copied);
}

int64_t saveRestoreZip(int p_fd, const std::string &p_root_dir, SaveZipProgress p_progress, void *p_user,
		std::string &r_error) {
	struct stat stat_result{};
	if (fstat(p_fd, &stat_result) != 0) {
		r_error = "Couldn't read the backup file.";
		return -1;
	}
	uint64_t file_size = uint64_t(stat_result.st_size);

	// Pass 1: validate every entry path before touching the disk.
	uint64_t at = 0;
	bool any_entry = false;
	std::vector<std::string> names;
	while (true) {
		ZipStreamEntry entry;
		if (!zipStreamNext(p_fd, file_size, at, entry)) {
			break;
		}
		any_entry = true;
		if (entry.compressed_size == 0) {
			break; // no recorded payload size; not produced by our writer
		}
		at = entry.data_offset + entry.compressed_size;
		if (excluded(entry.name)) {
			continue;
		}
		std::string relative;
		std::string absolute;
		if (!safePath(p_root_dir, entry.name, relative, absolute)) {
			r_error = "Backup contains an unsafe path: " + entry.name;
			return -1;
		}
		names.push_back(relative);
	}
	if (!any_entry) {
		r_error = "That file is not a save backup.";
		return -1;
	}
	if (names.empty()) {
		r_error = "That backup contains no save files.";
		return -1;
	}

	// Delete everything in the restorable scope so the archive fully replaces it.
	DIR *root_dir = opendir(p_root_dir.c_str());
	if (root_dir != nullptr) {
		while (dirent *child = readdir(root_dir)) {
			std::string name = child->d_name;
			if (name == "." || name == ".." || excluded(name)) {
				continue;
			}
			removeRecursively(p_root_dir + "/" + name);
		}
		closedir(root_dir);
	}

	// Pass 2: extract.
	if (lseek(p_fd, 0, SEEK_SET) < 0) {
		r_error = "Couldn't read the backup file.";
		return -1;
	}
	at = 0;
	int64_t restored = 0;
	std::vector<uint8_t> payload;
	uint64_t consumed = 0;
	while (true) {
		ZipStreamEntry entry;
		if (!zipStreamNext(p_fd, file_size, at, entry)) {
			break;
		}
		payload.clear();
		if (!zipReadPayload(p_fd, entry, payload, consumed)) {
			continue;
		}
		at = entry.data_offset + consumed;
		if (excluded(entry.name)) {
			continue;
		}
		std::string relative;
		std::string absolute;
		if (!safePath(p_root_dir, entry.name, relative, absolute)) {
			continue;
		}
		makeParents(absolute);
		int out_fd = open(absolute.c_str(), O_WRONLY | O_CREAT | O_TRUNC, 0644);
		if (out_fd < 0) {
			continue;
		}
		size_t done = 0;
		while (done < payload.size()) {
			ssize_t put = write(out_fd, payload.data() + done, payload.size() - done);
			if (put <= 0) {
				break;
			}
			done += size_t(put);
		}
		close(out_fd);
		if (done == payload.size()) {
			restored++;
			if (p_progress != nullptr) {
				p_progress(restored, int64_t(names.size()), p_user);
			}
		}
	}
	return restored;
}

} // namespace utarcore
