#include "patch_loader.h"

#include <godot_cpp/classes/dir_access.hpp>
#include <godot_cpp/classes/engine.hpp>
#include <godot_cpp/classes/file_access.hpp>
#include <godot_cpp/classes/project_settings.hpp>
#include <godot_cpp/variant/utility_functions.hpp>
#include <godot_cpp/templates/vector.hpp>

using namespace godot;

namespace utinternal {

namespace {

// Keep in sync with the GLES3Rules documentation in the runner app; bumping invalidates
// every cached shader decision.
const int RULES_VERSION = 1;

const char *MARKER_PATH = "user://utar_patches/backend.txt";
const char *PATCH_PACK_PATH = "user://utar_patches/patches.pck";
const char *MANIFEST_PATH = "user://utar_patches/manifest.md5";
const char *CACHE_ROOT = "user://shader_cache";

const int64_t MAX_SHADER_BYTES = 4LL * 1024 * 1024;

// ---------------------------------------------------------------------------
// MD5 (RFC 1321) — used for the override pack entry hashes, matching the format
// the engine itself writes.
// ---------------------------------------------------------------------------

struct MD5Context {
	uint32_t state[4];
	uint64_t bit_count;
	uint8_t buffer[64];
};

static const uint32_t MD5_K[64] = {
	0xd76aa478, 0xe8c7b756, 0x242070db, 0xc1bdceee, 0xf57c0faf, 0x4787c62a, 0xa8304613, 0xfd469501,
	0x698098d8, 0x8b44f7af, 0xffff5bb1, 0x895cd7be, 0x6b901122, 0xfd987193, 0xa679438e, 0x49b40821,
	0xf61e2562, 0xc040b340, 0x265e5a51, 0xe9b6c7aa, 0xd62f105d, 0x02441453, 0xd8a1e681, 0xe7d3fbc8,
	0x21e1cde6, 0xc33707d6, 0xf4d50d87, 0x455a14ed, 0xa9e3e905, 0xfcefa3f8, 0x676f02d9, 0x8d2a4c8a,
	0xfffa3942, 0x8771f681, 0x6d9d6122, 0xfde5380c, 0xa4beea44, 0x4bdecfa9, 0xf6bb4b60, 0xbebfbc70,
	0x289b7ec6, 0xeaa127fa, 0xd4ef3085, 0x04881d05, 0xd9d4d039, 0xe6db99e5, 0x1fa27cf8, 0xc4ac5665,
	0xf4292244, 0x432aff97, 0xab9423a7, 0xfc93a039, 0x655b59c3, 0x8f0ccc92, 0xffeff47d, 0x85845dd1,
	0x6fa87e4f, 0xfe2ce6e0, 0xa3014314, 0x4e0811a1, 0xf7537e82, 0xbd3af235, 0x2ad7d2bb, 0xeb86d391,
};

static const int MD5_S[64] = {
	7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22,
	5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20,
	4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23,
	6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21,
};

static inline uint32_t md5_rotate(uint32_t v, int s) {
	return (v << s) | (v >> (32 - s));
}

void md5_init(MD5Context &ctx) {
	ctx.state[0] = 0x67452301;
	ctx.state[1] = 0xefcdab89;
	ctx.state[2] = 0x98badcfe;
	ctx.state[3] = 0x10325476;
	ctx.bit_count = 0;
}

static void md5_block(MD5Context &ctx, const uint8_t *block) {
	uint32_t m[16];
	for (int i = 0; i < 16; i++) {
		m[i] = uint32_t(block[i * 4]) | (uint32_t(block[i * 4 + 1]) << 8) |
				(uint32_t(block[i * 4 + 2]) << 16) | (uint32_t(block[i * 4 + 3]) << 24);
	}
	uint32_t a = ctx.state[0], b = ctx.state[1], c = ctx.state[2], d = ctx.state[3];
	for (int i = 0; i < 64; i++) {
		uint32_t f;
		int g;
		if (i < 16) {
			f = (b & c) | (~b & d);
			g = i;
		} else if (i < 32) {
			f = (d & b) | (~d & c);
			g = (5 * i + 1) % 16;
		} else if (i < 48) {
			f = b ^ c ^ d;
			g = (3 * i + 5) % 16;
		} else {
			f = c ^ (b | ~d);
			g = (7 * i) % 16;
		}
		uint32_t temp = d;
		d = c;
		c = b;
		b = b + md5_rotate(a + f + MD5_K[i] + m[g], MD5_S[i]);
		a = temp;
	}
	ctx.state[0] += a;
	ctx.state[1] += b;
	ctx.state[2] += c;
	ctx.state[3] += d;
}

void md5_update(MD5Context &ctx, const uint8_t *data, size_t length) {
	ctx.bit_count += uint64_t(length) * 8;
	size_t offset = 0;
	size_t buffered = size_t((ctx.bit_count / 8) % 64);
	if (buffered > 0) {
		size_t take = 64 - buffered < length ? 64 - buffered : length;
		for (size_t i = 0; i < take; i++) {
			ctx.buffer[buffered + i] = data[offset + i];
		}
		buffered += take;
		offset += take;
		length -= take;
		if (buffered == 64) {
			md5_block(ctx, ctx.buffer);
			buffered = 0;
		}
	}
	while (length >= 64) {
		md5_block(ctx, data + offset);
		offset += 64;
		length -= 64;
	}
	for (size_t i = 0; i < length; i++) {
		ctx.buffer[buffered + i] = data[offset + i];
	}
}

void md5_final(MD5Context &ctx, uint8_t digest[16]) {
	uint8_t pad = 0x80;
	uint8_t zeros[64] = { 0 };
	uint64_t bits = ctx.bit_count;
	md5_update(ctx, &pad, 1);
	size_t buffered = size_t((ctx.bit_count / 8) % 64);
	if (buffered > 56) {
		md5_update(ctx, zeros, 64 - buffered);
		buffered = 0;
	}
	while (buffered != 56) {
		uint8_t zero = 0;
		md5_update(ctx, &zero, 1);
		buffered = size_t((ctx.bit_count / 8) % 64);
	}
	uint8_t length_bytes[8];
	for (int i = 0; i < 8; i++) {
		length_bytes[i] = uint8_t((bits >> (8 * i)) & 0xFF);
	}
	// md5_update would mutate bit_count; the padding length bookkeeping no longer matters.
	{
		size_t len = 8, offset = 0;
		size_t pos = size_t((ctx.bit_count / 8) % 64);
		if (pos + len <= 64) {
			for (size_t i = 0; i < len; i++) {
				ctx.buffer[pos + i] = length_bytes[offset + i];
			}
			md5_block(ctx, ctx.buffer);
		}
	}
	for (int i = 0; i < 4; i++) {
		digest[i * 4 + 0] = uint8_t(ctx.state[i] & 0xFF);
		digest[i * 4 + 1] = uint8_t((ctx.state[i] >> 8) & 0xFF);
		digest[i * 4 + 2] = uint8_t((ctx.state[i] >> 16) & 0xFF);
		digest[i * 4 + 3] = uint8_t((ctx.state[i] >> 24) & 0xFF);
	}
}

void md5_buffer(const uint8_t *data, size_t length, uint8_t digest[16]) {
	MD5Context ctx;
	md5_init(ctx);
	md5_update(ctx, data, length);
	md5_final(ctx, digest);
}

static String md5_hex(const uint8_t digest[16]) {
	static const char *hex = "0123456789abcdef";
	char out[33];
	for (int i = 0; i < 16; i++) {
		out[i * 2] = hex[digest[i] >> 4];
		out[i * 2 + 1] = hex[digest[i] & 0x0F];
	}
	out[32] = '\0';
	return String(out);
}

// ---------------------------------------------------------------------------
// GLES3 (Compatibility renderer) analysis for Godot 4.1 shaders.
// ---------------------------------------------------------------------------

// render_mode tokens the compatibility renderer does not implement in 4.1. Dropping one only
// removes an optional rendering hint; the shading itself stays identical.
static const char *DROPPABLE_RENDER_MODES[] = {
	"depth_prepass_alpha", // forward+ depth pre-pass hint; compat alpha works without it
	"alpha_to_coverage",   // MSAA coverage resolve; no compat equivalent in 4.1
	"sss_mode_skin",       // sub-surface scattering profile; compat has no SSS
};

// Features the 4.1 compatibility renderer cannot compile and that have no safe textual
// equivalent; such shaders are left untouched and reported.
static const char *UNFIXABLE_WORDS[] = {
	"VIEW_INDEX",
	"VIEW_MONO_LEFT",
	"VIEW_MONO_RIGHT",
};

/** Removes comments for scanning only; the original source is what gets patched/stored. */
static String strip_comments(const String &p_source) {
	String out;
	bool in_string = false;
	bool in_line_comment = false;
	bool in_block_comment = false;
	char32_t prev = 0;
	for (int i = 0; i < p_source.length(); i++) {
		char32_t c = p_source[i];
		char32_t next = i + 1 < p_source.length() ? p_source[i + 1] : 0;
		if (in_line_comment) {
			if (c == '\n') {
				in_line_comment = false;
				out += c;
			}
			prev = 0;
			continue;
		}
		if (in_block_comment) {
			if (prev == '*' && c == '/') {
				in_block_comment = false;
				prev = 0;
				continue;
			}
			prev = c;
			continue;
		}
		if (in_string) {
			if (c == '\\' && i + 1 < p_source.length()) {
				out += c;
				out += next;
				i++;
				continue;
			}
			if (c == '"') {
				in_string = false;
			}
			out += c;
			prev = c;
			continue;
		}
		if (prev == '/' && c == '/') {
			in_line_comment = true;
			out[out.length() - 1] = ' ';
			prev = 0;
			continue;
		}
		if (prev == '/' && c == '*') {
			in_block_comment = true;
			out[out.length() - 1] = ' ';
			prev = 0;
			continue;
		}
		if (c == '"') {
			in_string = true;
		}
		out += c;
		prev = c;
	}
	return out;
}

static bool is_word_char(char32_t c) {
	return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_';
}

/** True when [p_word] appears in [p_text] bounded by non-word characters. */
static bool contains_word(const String &p_text, const String &p_word) {
	int index = p_text.find(p_word);
	while (index >= 0) {
		bool left_ok = index == 0 || !is_word_char(p_text[index - 1]);
		bool right_ok = index + p_word.length() >= p_text.length() || !is_word_char(p_text[index + p_word.length()]);
		if (left_ok && right_ok) {
			return true;
		}
		index = p_text.find(p_word, index + 1);
	}
	return false;
}

/** Skips whitespace/comments from [p_text] starting at [p_index]; returns the next index. */
static int skip_space(const String &p_text, int index) {
	while (index < p_text.length()) {
		char32_t c = p_text[index];
		if (c == ' ' || c == '\t' || c == '\r' || c == '\n') {
			index++;
		} else if (c == '/' && index + 1 < p_text.length() && p_text[index + 1] == '/') {
			while (index < p_text.length() && p_text[index] != '\n') {
				index++;
			}
		} else if (c == '/' && index + 1 < p_text.length() && p_text[index + 1] == '*') {
			index += 2;
			while (index + 1 < p_text.length() && !(p_text[index] == '*' && p_text[index + 1] == '/')) {
				index++;
			}
			index = index + 2 <= p_text.length() ? index + 2 : p_text.length();
		} else {
			break;
		}
	}
	return index;
}

/** Whole-word lookup at [p_index] in [p_text]. */
static bool word_at(const String &p_text, int index, const String &p_word) {
	if (index + p_word.length() > p_text.length()) {
		return false;
	}
	if (index > 0 && is_word_char(p_text[index - 1])) {
		return false;
	}
	if (index + p_word.length() < p_text.length() && is_word_char(p_text[index + p_word.length()])) {
		return false;
	}
	return p_text.substr(index, p_word.length()) == p_word;
}

/**
 * Analyses one shader source for GLES3 compatibility.
 *
 * `unfixable` — the shader needs a feature the 4.1 compat renderer cannot compile; it is left
 *               untouched so the engine reports the original, unmodified shader.
 * `clean`     — no known incompatibility.
 * otherwise   — `text` holds the faithful GLES3-compatible rewrite.
 */
struct ShaderAnalysis {
	bool unfixable = false;
	bool clean = false;
	String text;
};

static ShaderAnalysis analyse_shader(const String &p_source) {
	ShaderAnalysis result;
	String text = strip_comments(p_source);

	// instance uniform: values are supplied per instance by the scene tree; there is no safe
	// textual rewrite for the compat renderer of this engine version.
	int scan = 0;
	while (scan < text.length()) {
		int instance_at = text.find("instance", scan);
		if (instance_at < 0) {
			break;
		}
		if (word_at(text, instance_at, "instance")) {
			int after = skip_space(text, instance_at + 8);
			if (word_at(text, after, "uniform")) {
				result.unfixable = true;
				break;
			}
		}
		scan = instance_at + 8;
	}
	if (result.unfixable) {
		return result;
	}

	// Compute entry points: the GLES3 backend has no compute stage.
	int compute_at = text.find("shader_type");
	if (compute_at >= 0) {
		int after = skip_space(text, compute_at + 11);
		if (word_at(text, after, "compute")) {
			result.unfixable = true;
			return result;
		}
	}
	if (contains_word(text, "void") && contains_word(text, "compute")) {
		// Any "void compute(" is a compute entry point candidate; conservatively unfixable.
		int void_at = text.find("void");
		while (void_at >= 0) {
			if (word_at(text, void_at, "void")) {
				int after = skip_space(text, void_at + 4);
				if (word_at(text, after, "compute")) {
					int paren = skip_space(text, after + 7);
					if (paren < text.length() && text[paren] == '(') {
						result.unfixable = true;
						return result;
					}
				}
			}
			void_at = text.find("void", void_at + 4);
		}
	}

	for (const char *word : UNFIXABLE_WORDS) {
		if (contains_word(text, word)) {
			result.unfixable = true;
			return result;
		}
	}

	// Rewrite the render_mode statements.
	String source = p_source;
	String rebuilt;
	int read = 0;
	bool changed = false;
	while (read < source.length()) {
		int mode_at = source.find("render_mode", read);
		if (mode_at < 0 || !word_at(source, mode_at, "render_mode")) {
			rebuilt += source.substr(read);
			break;
		}
		// Copy everything up to the statement.
		rebuilt += source.substr(read, mode_at - read);
		// Find the terminating ';' (render_mode lists may span lines; strings cannot occur here).
		int end = mode_at;
		while (end < source.length() && source[end] != ';') {
			end++;
		}
		if (end >= source.length()) {
			rebuilt += source.substr(mode_at);
			read = source.length();
			break;
		}
		String body = source.substr(mode_at + 11, end - (mode_at + 11));
		String kept;
		int token_start = 0;
		bool first = true;
		while (token_start <= body.length()) {
			int comma = body.find(",", token_start);
			int token_end = comma < 0 ? body.length() : comma;
			String token = body.substr(token_start, token_end - token_start).strip_edges();
			String name = token;
			int equals = token.find("=");
			if (equals >= 0) {
				name = token.substr(0, equals).strip_edges();
			}
			bool drop = false;
			for (const char *droppable : DROPPABLE_RENDER_MODES) {
				if (name == String(droppable)) {
					drop = true;
					break;
				}
			}
			if (!drop) {
				if (!first) {
					kept += ", ";
				}
				kept += token;
				first = false;
			} else {
				changed = true;
			}
			if (comma < 0) {
				break;
			}
			token_start = comma + 1;
		}
		if (changed) {
			if (kept.is_empty()) {
				// Drop the whole (now empty) statement together with one trailing newline.
				int line_end = end + 1 < source.length() && source[end + 1] == '\n' ? end + 1 : end;
				if (end + 1 < source.length() && source[end + 1] == '\r') {
					line_end = end + 2 < source.length() && source[end + 2] == '\n' ? end + 2 : end + 1;
				}
				read = line_end + 1;
				continue;
			}
			rebuilt += "render_mode " + kept + ";";
		} else {
			rebuilt += source.substr(mode_at, end - mode_at + 1);
		}
		read = end + 1;
	}

	if (!changed) {
		result.clean = true;
		return result;
	}
	result.text = rebuilt;
	return result;
}

// ---------------------------------------------------------------------------
// PCK (format v2) writer for the override pack.
// ---------------------------------------------------------------------------

struct PatchEntry {
	String path;
	PackedByteArray data;
};

struct EngineVersionInfo {
	int major = 0;
	int minor = 0;
	int patch = 0;
};

static inline uint64_t align_up(uint64_t value, uint64_t alignment) {
	return (value + alignment - 1) / alignment * alignment;
}

static inline uint64_t padded_path_length(const String &p_path) {
	uint64_t length = p_path.utf8().length();
	uint64_t remainder = length % 4;
	return remainder == 0 ? length : length + (4 - remainder);
}

static void store_zeros(const Ref<FileAccess> &file, uint64_t count) {
	uint8_t zeros[64] = { 0 };
	while (count > 0) {
		uint64_t chunk = count < 64 ? count : 64;
		file->store_buffer(zeros, chunk);
		count -= chunk;
	}
}

static bool write_patch_pack(const String &p_path, const Vector<PatchEntry> &p_entries, const EngineVersionInfo &p_version) {
	struct Planned {
		const PatchEntry *entry;
		uint64_t relative_offset;
	};

	uint64_t cursor = 0;
	Vector<Planned> planned;
	for (int i = 0; i < p_entries.size(); i++) {
		uint64_t start = align_up(cursor, 32);
		Planned item;
		item.entry = &p_entries[i];
		item.relative_offset = start;
		planned.push_back(item);
		cursor = start + uint64_t(p_entries[i].data.size());
	}

	uint64_t index_size = 4;
	for (int i = 0; i < planned.size(); i++) {
		index_size += 4 + padded_path_length(planned[i].entry->path) + 8 + 8 + 16 + 4;
	}
	uint64_t file_base = align_up(96 + index_size, 32);

	Ref<FileAccess> file = FileAccess::open(p_path, FileAccess::WRITE);
	if (file.is_null()) {
		UtilityFunctions::print("ShaderPatch: could not write ", p_path);
		return false;
	}

	file->store_32(0x43504447); // "GDPC"
	file->store_32(2); // format version
	file->store_32(p_version.major);
	file->store_32(p_version.minor);
	file->store_32(p_version.patch);
	file->store_32(0); // pack flags: unencrypted directory
	file->store_64(file_base);
	for (int i = 0; i < 16; i++) {
		file->store_32(0);
	}

	file->store_32(planned.size());
	for (int i = 0; i < planned.size(); i++) {
		const PatchEntry &entry = *planned[i].entry;
		CharString path_utf8 = entry.path.utf8();
		uint64_t path_length = path_utf8.length();
		uint64_t padded = padded_path_length(entry.path);
		file->store_32(uint32_t(padded));
		file->store_buffer(reinterpret_cast<const uint8_t *>(path_utf8.get_data()), path_length);
		store_zeros(file, padded - path_length);
		file->store_64(planned[i].relative_offset);
		file->store_64(uint64_t(entry.data.size()));
		uint8_t digest[16];
		md5_buffer(entry.data.ptr(), size_t(entry.data.size()), digest);
		file->store_buffer(digest, 16);
		file->store_32(0);
	}

	uint64_t written = 96 + index_size;
	if (file_base > written) {
		store_zeros(file, file_base - written);
		written = file_base;
	}
	for (int i = 0; i < planned.size(); i++) {
		const PatchEntry &entry = *planned[i].entry;
		uint64_t start = file_base + planned[i].relative_offset;
		if (start > written) {
			store_zeros(file, start - written);
			written = start;
		}
		file->store_buffer(entry.data.ptr(), uint64_t(entry.data.size()));
		written += uint64_t(entry.data.size());
	}
	return true;
}

// ---------------------------------------------------------------------------
// Loader.
// ---------------------------------------------------------------------------

static void collect_shader_paths(const String &p_dir, Vector<String> &r_out) {
	Ref<DirAccess> dir = DirAccess::open(p_dir);
	if (dir.is_null()) {
		return;
	}
	dir->list_dir_begin();
	String next = dir->get_next();
	while (!next.is_empty()) {
		String full = p_dir.path_join(next);
		if (dir->current_is_dir()) {
			if (!next.begins_with(".")) {
				collect_shader_paths(full, r_out);
			}
		} else if (next.get_extension().to_lower() == "gdshader") {
			r_out.push_back(full);
		}
		next = dir->get_next();
	}
	dir->list_dir_end();
}

static EngineVersionInfo engine_version() {
	Dictionary info = Engine::get_singleton()->get_version_info();
	EngineVersionInfo version;
	version.major = int(info["major"]);
	version.minor = int(info["minor"]);
	version.patch = int(info["patch"]);
	return version;
}

} // namespace

void load_shader_patch_pack() {
	if (!FileAccess::file_exists(MARKER_PATH)) {
		return;
	}
	String backend = FileAccess::get_file_as_string(MARKER_PATH).strip_edges();
	if (backend != String("compatibility")) {
		// Vulkan selection: the original shaders run untouched and nothing is mounted.
		return;
	}

	EngineVersionInfo version = engine_version();
	String cache_dir = String(CACHE_ROOT) + "/gles3_" +
			String::num_int64(version.major) + "_" + String::num_int64(version.minor) + "_" +
			String::num_int64(version.patch) + "_v" + String::num_int64(RULES_VERSION);

	Vector<String> shader_paths;
	collect_shader_paths("res://", shader_paths);
	if (shader_paths.is_empty()) {
		return;
	}

	Vector<PatchEntry> overrides;
	int unfixable_count = 0;
	for (int i = 0; i < shader_paths.size(); i++) {
		const String &path = shader_paths[i];
		String md5 = FileAccess::get_md5(path).to_lower();
		if (md5.is_empty()) {
			UtilityFunctions::print("ShaderPatch: could not hash ", path, ", left as-is");
			continue;
		}

		String clean_cache = cache_dir.path_join(md5 + ".clean");
		String unfixable_cache = cache_dir.path_join(md5 + ".unfixable");
		String patched_cache = cache_dir.path_join(md5 + ".patched");

		if (FileAccess::file_exists(clean_cache)) {
			continue;
		}
		if (FileAccess::file_exists(unfixable_cache)) {
			unfixable_count++;
			continue;
		}
		if (FileAccess::file_exists(patched_cache)) {
			PatchEntry entry;
			entry.path = path;
			entry.data = FileAccess::get_file_as_bytes(patched_cache);
			if (!entry.data.is_empty()) {
				overrides.push_back(entry);
			}
			continue;
		}

		String source = FileAccess::get_file_as_string(path);
		if (source.is_empty() || source.utf8().length() > MAX_SHADER_BYTES) {
			UtilityFunctions::print("ShaderPatch: skipping oversized/empty shader ", path);
			continue;
		}

		ShaderAnalysis analysis = analyse_shader(source);
		if (analysis.unfixable) {
			Ref<FileAccess> cache = FileAccess::open(unfixable_cache, FileAccess::WRITE);
			if (cache.is_valid()) {
				cache->store_string("");
			}
			unfixable_count++;
			UtilityFunctions::print(
					"ShaderPatch: ", path,
					" needs a GLES3 feature with no automatic equivalent — original shader kept");
			continue;
		}
		if (analysis.clean) {
			Ref<FileAccess> cache = FileAccess::open(clean_cache, FileAccess::WRITE);
			if (cache.is_valid()) {
				cache->store_string("");
			}
			continue;
		}

		Ref<FileAccess> cache = FileAccess::open(patched_cache, FileAccess::WRITE);
		if (cache.is_valid()) {
			cache->store_string(analysis.text);
		}
		PatchEntry entry;
		entry.path = path;
		CharString text_utf8 = analysis.text.utf8();
		int text_length = text_utf8.length();
		entry.data.resize(text_length);
		for (int c = 0; c < text_length; c++) {
			entry.data[c] = uint8_t(text_utf8[c]);
		}
		overrides.push_back(entry);
		UtilityFunctions::print("ShaderPatch: GLES3 override generated for ", path);
	}

	// Manifest of everything that decides the pack's contents; when it is unchanged the pack
	// on disk is already current and can be mounted without rewriting.
	String manifest;
	{
		MD5Context ctx;
		md5_init(ctx);
		for (int i = 0; i < overrides.size(); i++) {
			String line = overrides[i].path + String(":") + String::num_int64(overrides[i].data.size()) + String("\n");
			CharString utf8 = line.utf8();
			md5_update(ctx, reinterpret_cast<const uint8_t *>(utf8.get_data()), utf8.length());
		}
		uint8_t digest[16];
		md5_final(ctx, digest);
		manifest = md5_hex(digest);
	}

	if (overrides.is_empty()) {
		if (FileAccess::file_exists(PATCH_PACK_PATH)) {
			DirAccess::remove_absolute(ProjectSettings::get_singleton()->globalize_path(PATCH_PACK_PATH));
		}
		UtilityFunctions::print(
				"ShaderPatch: GLES3 scan done — ", shader_paths.size(), " shader(s), no overrides needed",
				unfixable_count > 0 ? String(", ") + String::num_int64(unfixable_count) + " not auto-convertible" : String(""));
		return;
	}

	bool pack_current = FileAccess::file_exists(PATCH_PACK_PATH);
	if (pack_current) {
		String stored = FileAccess::file_exists(MANIFEST_PATH)
				? FileAccess::get_file_as_string(MANIFEST_PATH).strip_edges()
				: String();
		pack_current = stored == manifest;
	}
	if (!pack_current) {
		if (!write_patch_pack(PATCH_PACK_PATH, overrides, version)) {
			return;
		}
		Ref<FileAccess> manifest_file = FileAccess::open(MANIFEST_PATH, FileAccess::WRITE);
		if (manifest_file.is_valid()) {
			manifest_file->store_string(manifest);
		}
		UtilityFunctions::print("ShaderPatch: wrote ", overrides.size(), " GLES3 override(s) into patches.pck");
	}

	if (ProjectSettings::get_singleton()->load_resource_pack(PATCH_PACK_PATH, true)) {
		// The overrides were mounted last with replace_files=true, so their entries shadow the
		// game pack's originals at the same res:// paths (patch priority). The originals stay
		// byte-for-byte intact for the Vulkan backend.
		UtilityFunctions::print("ShaderPatch: GLES3 override pack mounted (", overrides.size(), " shader(s))");
	} else {
		UtilityFunctions::print("ShaderPatch: mounting the override pack failed");
	}
}

} // namespace utinternal
