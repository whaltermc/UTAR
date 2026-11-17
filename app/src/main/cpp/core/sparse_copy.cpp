#include "sparse_copy.h"

#include <algorithm>
#include <cerrno>
#include <cstring>
#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>
#include <vector>

namespace utarcore {

namespace {

constexpr size_t COPY_CHUNK = 1 << 20;

bool writeAll(int p_fd, const uint8_t *p_data, uint64_t p_length, uint64_t p_offset, std::string &r_error) {
	size_t done = 0;
	while (done < p_length) {
		ssize_t put = pwrite(p_fd, p_data + done, size_t(p_length - done), off_t(p_offset + done));
		if (put < 0 && errno == EINTR) {
			continue;
		}
		if (put <= 0) {
			r_error = "write failed while copying pack data";
			return false;
		}
		done += size_t(put);
	}
	return true;
}

bool copySequential(
		int p_src_fd, int p_dst_fd,
		uint64_t p_src_offset, uint64_t p_dst_offset, uint64_t p_length,
		SparseProgressFn p_progress, void *p_user,
		uint64_t &r_written, std::string &r_error) {
	std::vector<uint8_t> buffer(std::min<uint64_t>(p_length, COPY_CHUNK));
	uint64_t position = 0;
	while (position < p_length) {
		size_t chunk = size_t(std::min<uint64_t>(p_length - position, COPY_CHUNK));
		ssize_t got = pread(p_src_fd, buffer.data(), chunk, off_t(p_src_offset + position));
		if (got < 0 && errno == EINTR) {
			continue;
		}
		if (got <= 0) {
			r_error = "unexpected end of source while copying";
			return false;
		}
		if (!writeAll(p_dst_fd, buffer.data(), uint64_t(got), p_dst_offset + position, r_error)) {
			return false;
		}
		position += uint64_t(got);
		r_written += uint64_t(got);
		if (p_progress) {
			p_progress(int64_t(position), p_user);
		}
	}
	return true;
}

/**
 * Walks the source extents with SEEK_DATA/SEEK_HOLE and copies only allocated runs.
 * Returns false with [r_supported] false when the filesystem has no extent support, so the
 * caller can fall back to a sequential copy.
 */
bool copyExtents(
		int p_src_fd, int p_dst_fd,
		uint64_t p_src_offset, uint64_t p_dst_offset, uint64_t p_length,
		SparseProgressFn p_progress, void *p_user,
		uint64_t &r_written, bool &r_supported, std::string &r_error) {
	const uint64_t end = p_src_offset + p_length;
	uint64_t position = p_src_offset;
	while (position < end) {
		errno = 0;
		off_t data = lseek(p_src_fd, off_t(position), SEEK_DATA);
		if (data < 0) {
			if (errno == ENXIO) {
				break; // trailing hole: nothing left to copy
			}
			if (errno == EINVAL || errno == ENOTSUP || errno == EOPNOTSUPP) {
				r_supported = false;
				return false;
			}
			r_error = std::string("couldn't read the source extents: ") + std::strerror(errno);
			return false;
		}
		if (uint64_t(data) >= end) {
			break;
		}
		errno = 0;
		off_t hole = lseek(p_src_fd, data, SEEK_HOLE);
		if (hole < 0) {
			r_error = std::string("couldn't read the source extents: ") + std::strerror(errno);
			return false;
		}
		const uint64_t run_start = uint64_t(data);
		const uint64_t run_end = std::min<uint64_t>(uint64_t(hole), end);
		if (run_end > run_start) {
			uint64_t run_written = 0;
			if (!copySequential(p_src_fd, p_dst_fd,
					run_start, p_dst_offset + (run_start - p_src_offset), run_end - run_start,
					nullptr, nullptr, run_written, r_error)) {
				return false;
			}
			r_written += run_written;
			if (p_progress) {
				p_progress(int64_t(run_end - p_src_offset), p_user);
			}
		}
		position = run_end;
	}
	return true;
}

} // namespace

bool fileIsSparse(int p_fd) {
	struct stat info{};
	if (fstat(p_fd, &info) != 0 || !S_ISREG(info.st_mode)) {
		return false;
	}
	if (info.st_size <= 0) {
		return false;
	}
	return uint64_t(info.st_blocks) * 512ULL < uint64_t(info.st_size);
}

bool sparseCopyRange(
		int p_src_fd, int p_dst_fd,
		uint64_t p_src_offset, uint64_t p_dst_offset, uint64_t p_length,
		bool p_source_sparse, SparseProgressFn p_progress, void *p_user,
		uint64_t &r_written, std::string &r_error) {
	r_written = 0;
	if (p_length == 0) {
		return true;
	}
	if (!p_source_sparse) {
		return copySequential(p_src_fd, p_dst_fd, p_src_offset, p_dst_offset, p_length,
				p_progress, p_user, r_written, r_error);
	}
	bool supported = true;
	if (copyExtents(p_src_fd, p_dst_fd, p_src_offset, p_dst_offset, p_length,
			p_progress, p_user, r_written, supported, r_error)) {
		return true;
	}
	if (supported) {
		return false;
	}
	r_written = 0;
	r_error.clear();
	return copySequential(p_src_fd, p_dst_fd, p_src_offset, p_dst_offset, p_length,
			p_progress, p_user, r_written, r_error);
}

} // namespace utarcore
