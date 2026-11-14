#ifndef UTARCORE_SETTINGS_OVERRIDE_H
#define UTARCORE_SETTINGS_OVERRIDE_H

#include <string>
#include <vector>

namespace utarcore {

struct RendererMethodSlot {
	std::string path;
	uint64_t value_offset = 0; // absolute file offset of the string payload
	uint32_t value_length = 0;
	std::string current_value;

	/** Serialises for the Kotlin side: [len path, path]; u64 offset; u32 length; [len value, value]. */
	std::vector<uint8_t> toBlob() const;
};

/**
 * Parses `res://project.binary` inside the pack and locates the
 * `rendering/renderer/rendering_method` string payload. Returns false when the pack keeps
 * the engine default (the key is simply not stored).
 */
bool settingsLocateMethodSlot(const std::string &pack_path, RendererMethodSlot &r_slot);

/** Same-length in-place patch of the renderer string inside the installed pack. */
bool settingsPatchString(const std::string &pack_path, uint64_t offset, uint32_t length, const std::string &value);

} // namespace utarcore

#endif // UTARCORE_SETTINGS_OVERRIDE_H
