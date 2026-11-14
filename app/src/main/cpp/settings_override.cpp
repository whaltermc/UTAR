#include "settings_override.h"

#include "pck_engine.h"

#include <fcntl.h>
#include <unistd.h>

#include <cstring>

namespace utarcore {

namespace {

constexpr const char *METHOD_KEY = "rendering/renderer/rendering_method";
constexpr const char *METHOD_KEY_MOBILE = "rendering/renderer/rendering_method.mobile";
constexpr int64_t PROJECT_BINARY_READ_LIMIT = 4LL * 1024 * 1024;

int32_t read_i32_le(const uint8_t *data, size_t offset) {
	return int32_t(uint32_t(data[offset]) | (uint32_t(data[offset + 1]) << 8) |
			(uint32_t(data[offset + 2]) << 16) | (uint32_t(data[offset + 3]) << 24));
}

} // namespace

std::vector<uint8_t> RendererMethodSlot::toBlob() const {
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
	auto u64 = [&](uint64_t value) {
		u32(uint32_t(value & 0xFFFFFFFF));
		u32(uint32_t(value >> 32));
	};
	put_str(path);
	u64(value_offset);
	u32(value_length);
	put_str(current_value);
	return out;
}

bool settingsLocateMethodSlot(const std::string &pack_path, RendererMethodSlot &r_slot) {
	std::string error;
	PckFile *pck = PckFile::open(pack_path, error);
	if (pck == nullptr) {
		return false;
	}
	int64_t index = pck->findEntry("res://project.binary");
	if (index < 0) {
		delete pck;
		return false;
	}
	const PckEntry &entry = pck->entry(size_t(index));
	if (entry.isEncrypted()) {
		delete pck;
		return false;
	}
	std::vector<uint8_t> data;
	if (!pck->readEntry(size_t(index), data, PROJECT_BINARY_READ_LIMIT, error) || data.empty()) {
		delete pck;
		return false;
	}
	delete pck;

	size_t off = 8; // skip "ECFG" + u32 version
	while (off + 4 <= data.size()) {
		int32_t key_length = read_i32_le(data.data(), off);
		off += 4;
		if (key_length < 1 || key_length > 4096 || off + size_t(key_length) > data.size()) {
			return false;
		}
		std::string key(reinterpret_cast<const char *>(data.data()) + off, size_t(key_length));
		off += size_t(key_length);
		if (off + 4 > data.size()) {
			return false;
		}
		int32_t value_size = read_i32_le(data.data(), off);
		off += 4;
		if (value_size < 0 || off + size_t(value_size) > data.size()) {
			return false;
		}

		if (key == METHOD_KEY_MOBILE || key == METHOD_KEY) {
			int32_t value_type = read_i32_le(data.data(), off);
			if (value_type == 4 && off + 8 <= size_t(int64_t(off) + value_size)) {
				int32_t string_length = read_i32_le(data.data(), off + 4);
				if (string_length >= 0 && size_t(string_length) + 8 <= size_t(value_size)) {
					r_slot.path = key;
					r_slot.value_offset = entry.offset + uint64_t(off) + 8;
					r_slot.value_length = uint32_t(string_length);
					r_slot.current_value.assign(reinterpret_cast<const char *>(data.data()) + off + 8,
							size_t(string_length));
					return true;
				}
			}
		}
		off += size_t(value_size);
	}
	return false;
}

bool settingsPatchString(const std::string &pack_path, uint64_t offset, uint32_t length, const std::string &value) {
	if (value.size() != size_t(length)) {
		return false;
	}
	int fd = open(pack_path.c_str(), O_RDWR);
	if (fd < 0) {
		return false;
	}
	bool ok = true;
	size_t done = 0;
	while (done < value.size()) {
		ssize_t put = pwrite(fd, value.data() + done, value.size() - done, off_t(offset + done));
		if (put <= 0) {
			ok = false;
			break;
		}
		done += size_t(put);
	}
	close(fd);
	return ok;
}

} // namespace utarcore
