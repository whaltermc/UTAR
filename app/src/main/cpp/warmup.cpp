#include "warmup.h"

#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>

#include <algorithm>
#include <cstring>
#include <vector>

namespace utarcore {

namespace {

constexpr size_t WARM_BUFFER = 1 << 20;

#ifdef POSIX_FADV_WILLNEED
bool fadviseWarm(int fd) {
	return posix_fadvise(fd, 0, 0, POSIX_FADV_SEQUENTIAL) == 0 &&
			posix_fadvise(fd, 0, 0, POSIX_FADV_WILLNEED) == 0;
}
#else
bool fadviseWarm(int) {
	return false;
}
#endif

bool sequentialReadWarm(int fd, uint64_t size) {
	if (size == 0) {
		return true;
	}
	std::vector<uint8_t> buffer(WARM_BUFFER);
	uint64_t at = 0;
	while (at < size) {
		uint64_t chunk = std::min<uint64_t>(WARM_BUFFER, size - at);
		ssize_t got = pread(fd, buffer.data(), size_t(chunk), off_t(at));
		if (got <= 0) {
			return false;
		}
		at += uint64_t(got);
	}
	return true;
}

} // namespace

bool warmupPack(const std::string &path) {
	int fd = open(path.c_str(), O_RDONLY);
	if (fd < 0) {
		return false;
	}
	struct stat stat_result{};
	if (fstat(fd, &stat_result) != 0) {
		close(fd);
		return false;
	}
	uint64_t size = uint64_t(stat_result.st_size);
	if (size == 0) {
		close(fd);
		return true;
	}
	bool ok = fadviseWarm(fd);
	if (!ok) {
		ok = sequentialReadWarm(fd, size);
	}
	close(fd);
	return ok;
}

} // namespace utarcore
