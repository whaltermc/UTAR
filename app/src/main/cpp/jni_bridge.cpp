#include <jni.h>

#include "gdext_rewriter.h"
#include "library_set.h"
#include "pck_engine.h"
#include "save_zip.h"
#include "script_patcher.h"
#include "settings_override.h"
#include "warmup.h"
#include "md5.h"
#include <algorithm>
#include <fstream>
#include <set>
#include <sys/stat.h>

#include <dirent.h>
#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>
#include <cstdio>
#include <cctype>
#include <cstring>
#include <sstream>
#include <android/log.h>

#include <string>
#include <vector>

namespace {

std::string g_lastError;

void setError(const std::string &message) {
	g_lastError = message;
}

void nativeLog(const std::string &message) {
#ifdef __ANDROID__
	__android_log_print(ANDROID_LOG_INFO, "UTAR", "%s", message.c_str());
#else
	std::printf("%s\n", message.c_str());
#endif
}

std::string fontFormat(const std::vector<uint8_t> &data) {
	if (data.size() < 4) return "empty";
	uint32_t magic = (uint32_t(data[0]) << 24) | (uint32_t(data[1]) << 16) |
		(uint32_t(data[2]) << 8) | uint32_t(data[3]);
	switch (magic) {
		case 0x00010000: return "truetype";
		case 0x4F54544F: return "opentype";
		case 0x74746366: return "ttc";
		case 0x774F4646: return "woff";
		case 0x774F4632: return "woff2";
		case 0x52535243: return "godot-resource";
		case 0x52534343: return "godot-resource-compressed";
		default: return "unknown";
	}
}

bool isFontExt(const std::string &ext) {
	static const std::set<std::string> exts = { "ttf","otf","ttc","otc","woff","woff2","fnt","fontdata" };
	return exts.count(ext) > 0;
}

std::string lowerExt(const std::string &path) {
	size_t pos = path.find_last_of('.');
	if (pos == std::string::npos) return "";
	std::string ext = path.substr(pos + 1);
	for (char &c : ext) c = (char)std::tolower((unsigned char)c);
	return ext;
}

std::string jstringToStd(JNIEnv *env, jstring value) {
	if (value == nullptr) {
		return std::string();
	}
	const char *chars = env->GetStringUTFChars(value, nullptr);
	std::string result(chars == nullptr ? std::string() : std::string(chars));
	if (chars != nullptr) {
		env->ReleaseStringUTFChars(value, chars);
	}
	return result;
}

jstring stdToJString(JNIEnv *env, const std::string &value) {
	return env->NewStringUTF(value.c_str());
}

jbyteArray vectorToJByteArray(JNIEnv *env, const std::vector<uint8_t> &data) {
	if (data.empty()) {
		return env->NewByteArray(0);
	}
	jbyteArray array = env->NewByteArray(jsize(data.size()));
	if (array == nullptr) {
		return nullptr;
	}
	env->SetByteArrayRegion(array, 0, jsize(data.size()), reinterpret_cast<const jbyte *>(data.data()));
	return array;
}

std::vector<uint8_t> jByteArrayToVector(JNIEnv *env, jbyteArray array) {
	if (array == nullptr) {
		return {};
	}
	jsize length = env->GetArrayLength(array);
	std::vector<uint8_t> data(length);
	if (length > 0) {
		env->GetByteArrayRegion(array, 0, length, reinterpret_cast<jbyte *>(data.data()));
	}
	return data;
}

std::vector<std::string> jStringArrayToVector(JNIEnv *env, jobjectArray array) {
	std::vector<std::string> result;
	if (array == nullptr) {
		return result;
	}
	jsize length = env->GetArrayLength(array);
	result.reserve(size_t(length));
	for (jsize i = 0; i < length; i++) {
		jstring element = static_cast<jstring>(env->GetObjectArrayElement(array, i));
		result.push_back(jstringToStd(env, element));
		if (element != nullptr) {
			env->DeleteLocalRef(element);
		}
	}
	return result;
}

std::vector<std::vector<uint8_t>> jByteArray2ToVector(JNIEnv *env, jobjectArray array) {
	std::vector<std::vector<uint8_t>> result;
	if (array == nullptr) {
		return result;
	}
	jsize length = env->GetArrayLength(array);
	result.reserve(size_t(length));
	for (jsize i = 0; i < length; i++) {
		jbyteArray element = static_cast<jbyteArray>(env->GetObjectArrayElement(array, i));
		result.push_back(jByteArrayToVector(env, element));
		if (element != nullptr) {
			env->DeleteLocalRef(element);
		}
	}
	return result;
}

/** Invokes progress.onProgress(done, total) on the Kotlin callback object (may be null). */
void reportProgress(JNIEnv *env, jobject callback, int64_t done, int64_t total) {
	if (callback == nullptr) {
		return;
	}
	jclass klass = env->GetObjectClass(callback);
	if (klass == nullptr) {
		return;
	}
	jmethodID method = env->GetMethodID(klass, "onProgress", "(JJ)V");
	if (method != nullptr) {
		env->CallVoidMethod(callback, method, jlong(done), jlong(total));
	}
	env->DeleteLocalRef(klass);
}

/** Sends a file list payload to the native scanner: vector of (path, payload). */
std::vector<std::pair<std::string, std::vector<uint8_t>>> pairUp(
		const std::vector<std::string> &paths, const std::vector<std::vector<uint8_t>> &payloads) {
	std::vector<std::pair<std::string, std::vector<uint8_t>>> result;
	size_t count = std::min(paths.size(), payloads.size());
	result.reserve(count);
	for (size_t i = 0; i < count; i++) {
		result.emplace_back(paths[i], payloads[i]);
	}
	return result;
}

void closeFdGuard(int *fd) {
	if (*fd >= 0) {
		close(*fd);
		*fd = -1;
	}
}

} // namespace

extern "C" {

// --- native library identity -------------------------------------------------

JNIEXPORT jstring JNICALL
Java_com_utar_runner_core_NativeCore_lastError(JNIEnv *env, jobject) {
	return stdToJString(env, g_lastError);
}

// --- PCK engine ---------------------------------------------------------------

JNIEXPORT jlong JNICALL
Java_com_utar_runner_core_NativeCore_pckOpen(JNIEnv *env, jobject, jstring path) {
	g_lastError.clear();
	std::string pack_path = jstringToStd(env, path);
	std::string error;
	utarcore::PckFile *pck = utarcore::PckFile::open(pack_path, error);
	if (pck == nullptr) {
		setError(error.empty() ? "Couldn't open the pack." : error);
		return 0;
	}
	return reinterpret_cast<jlong>(pck);
}

JNIEXPORT void JNICALL
Java_com_utar_runner_core_NativeCore_pckClose(JNIEnv *, jobject, jlong handle) {
	if (handle != 0) {
		delete reinterpret_cast<utarcore::PckFile *>(handle);
	}
}

/** Snapshot: u32 packVersion, u32 major, u32 minor, u32 patch; u32 count; per entry [len path, u64 offset, u64 size, 16 md5, u32 flags]. */
JNIEXPORT jbyteArray JNICALL
Java_com_utar_runner_core_NativeCore_pckSnapshot(JNIEnv *env, jobject, jlong handle) {
	g_lastError.clear();
	utarcore::PckFile *pck = reinterpret_cast<utarcore::PckFile *>(handle);
	if (pck == nullptr) {
		setError("Pack is not open.");
		return nullptr;
	}
	std::vector<uint8_t> blob;
	auto u32 = [&](uint32_t value) {
		blob.push_back(uint8_t(value & 0xFF));
		blob.push_back(uint8_t((value >> 8) & 0xFF));
		blob.push_back(uint8_t((value >> 16) & 0xFF));
		blob.push_back(uint8_t((value >> 24) & 0xFF));
	};
	auto u64 = [&](uint64_t value) {
		u32(uint32_t(value & 0xFFFFFFFF));
		u32(uint32_t(value >> 32));
	};
	u32(pck->packVersion());
	u32(pck->engineMajor());
	u32(pck->engineMinor());
	u32(pck->enginePatch());
	u32(uint32_t(pck->entryCount()));
	for (size_t i = 0; i < pck->entryCount(); i++) {
		const utarcore::PckEntry &entry = pck->entry(i);
		u32(uint32_t(entry.path.size()));
		blob.insert(blob.end(), entry.path.begin(), entry.path.end());
		u64(entry.offset);
		u64(entry.size);
		blob.insert(blob.end(), entry.md5, entry.md5 + 16);
		u32(entry.flags);
	}
	return vectorToJByteArray(env, blob);
}

JNIEXPORT jbyteArray JNICALL
Java_com_utar_runner_core_NativeCore_pckReadEntry(JNIEnv *env, jobject, jlong handle, jint index, jlong limit) {
	g_lastError.clear();
	utarcore::PckFile *pck = reinterpret_cast<utarcore::PckFile *>(handle);
	if (pck == nullptr) {
		setError("Pack is not open.");
		return nullptr;
	}
	std::vector<uint8_t> data;
	std::string error;
	if (!pck->readEntry(size_t(index), data, uint64_t(limit), error)) {
		setError(error.empty() ? "Couldn't read the pack entry." : error);
		return nullptr;
	}
	return vectorToJByteArray(env, data);
}

JNIEXPORT jint JNICALL
Java_com_utar_runner_core_NativeCore_pckFindEntry(JNIEnv *env, jobject, jlong handle, jstring path) {
	utarcore::PckFile *pck = reinterpret_cast<utarcore::PckFile *>(handle);
	if (pck == nullptr) {
		return -1;
	}
	return jint(pck->findEntry(jstringToStd(env, path)));
}

JNIEXPORT jlong JNICALL
Java_com_utar_runner_core_NativeCore_pckRewrite(JNIEnv *env, jobject, jlong handle, jstring target,
		jobjectArray repl_paths, jobjectArray repl_payloads,
		jobjectArray add_paths, jobjectArray add_payloads, jobject progress) {
	g_lastError.clear();
	utarcore::PckFile *pck = reinterpret_cast<utarcore::PckFile *>(handle);
	if (pck == nullptr) {
		setError("Pack is not open.");
		return -1;
	}
	struct Context {
		JNIEnv *env;
		jobject callback;
	} context{ env, progress };
	auto progress_fn = [](int64_t written, void *user) {
		Context *ctx = static_cast<Context *>(user);
		reportProgress(ctx->env, ctx->callback, written, -1);
	};
	std::string error;
	bool ok = pck->rewrite(jstringToStd(env, target),
			pairUp(jStringArrayToVector(env, repl_paths), jByteArray2ToVector(env, repl_payloads)),
			pairUp(jStringArrayToVector(env, add_paths), jByteArray2ToVector(env, add_payloads)),
			progress_fn, &context, error);
	if (!ok) {
		setError(error.empty() ? "Pack rewrite failed." : error);
		return -1;
	}
	// Kotlin reports target.length(); rewriting streams the whole file, so written == size.
	struct stat stat_result{};
	if (stat(jstringToStd(env, target).c_str(), &stat_result) != 0) {
		setError("Pack rewrite produced no output.");
		return -1;
	}
	return jlong(stat_result.st_size);
}

JNIEXPORT jboolean JNICALL
Java_com_utar_runner_core_NativeCore_pckWriteNew(JNIEnv *env, jobject, jstring target,
		jobjectArray paths, jobjectArray payloads, jint major, jint minor, jint patch) {
	g_lastError.clear();
	std::string error;
	if (!utarcore::PckFile::writeNew(jstringToStd(env, target),
				pairUp(jStringArrayToVector(env, paths), jByteArray2ToVector(env, payloads)),
				uint32_t(major), uint32_t(minor), uint32_t(patch), error)) {
		setError(error.empty() ? "Couldn't write the patch pack." : error);
		return JNI_FALSE;
	}
	return JNI_TRUE;
}

// --- library scanning ----------------------------------------------------------

JNIEXPORT jbyteArray JNICALL
Java_com_utar_runner_core_NativeCore_libScanApks(JNIEnv *env, jobject, jobjectArray apks, jstring abi,
		jstring cache_dir) {
	g_lastError.clear();
	utarcore::LibrarySet set = utarcore::LibrarySet::fromApks(jStringArrayToVector(env, apks),
			jstringToStd(env, abi), jstringToStd(env, cache_dir));
	if (set.empty()) {
		return nullptr;
	}
	return vectorToJByteArray(env, set.toBlob());
}

JNIEXPORT jbyteArray JNICALL
Java_com_utar_runner_core_NativeCore_libScanDirectory(JNIEnv *env, jobject, jstring directory, jstring abi) {
	g_lastError.clear();
	utarcore::LibrarySet set = utarcore::LibrarySet::fromDirectory(jstringToStd(env, directory),
			jstringToStd(env, abi));
	if (set.empty()) {
		return nullptr;
	}
	return vectorToJByteArray(env, set.toBlob());
}

// --- gdextension rewriting -------------------------------------------------------

JNIEXPORT jbyteArray JNICALL
Java_com_utar_runner_core_NativeCore_gdextPlan(JNIEnv *env, jobject, jstring config_path, jstring original,
		jbyteArray library_blob) {
	g_lastError.clear();
	utarcore::LibrarySet natives = utarcore::LibrarySet::fromBlob(jByteArrayToVector(env, library_blob));
	utarcore::GdextPlan plan = utarcore::gdextPlan(jstringToStd(env, config_path),
			jstringToStd(env, original), natives);
	return vectorToJByteArray(env, plan.toBlob());
}

// --- script patching --------------------------------------------------------------

JNIEXPORT jbyteArray JNICALL
Java_com_utar_runner_core_NativeCore_scriptFix(JNIEnv *env, jobject, jstring source) {
	g_lastError.clear();
	utarcore::ScriptFixResult result = utarcore::scriptFix(jstringToStd(env, source));
	return vectorToJByteArray(env, result.toBlob());
}

// --- project settings override ------------------------------------------------------

JNIEXPORT jbyteArray JNICALL
Java_com_utar_runner_core_NativeCore_settingsLocate(JNIEnv *env, jobject, jstring pack_path) {
	g_lastError.clear();
	utarcore::RendererMethodSlot slot;
	if (!utarcore::settingsLocateMethodSlot(jstringToStd(env, pack_path), slot)) {
		return nullptr;
	}
	return vectorToJByteArray(env, slot.toBlob());
}

JNIEXPORT jboolean JNICALL
Java_com_utar_runner_core_NativeCore_settingsPatch(JNIEnv *env, jobject, jstring pack_path, jlong offset,
		jint length, jstring value) {
	g_lastError.clear();
	if (!utarcore::settingsPatchString(jstringToStd(env, pack_path), uint64_t(offset), uint32_t(length),
				jstringToStd(env, value))) {
		setError("Couldn't patch the renderer setting.");
		return JNI_FALSE;
	}
	return JNI_TRUE;
}

// --- save backup ---------------------------------------------------------------------

JNIEXPORT jlong JNICALL
Java_com_utar_runner_core_NativeCore_saveBackup(JNIEnv *env, jobject, jstring root, jint fd, jobject progress) {
	g_lastError.clear();
	int owned = fd;
	struct Context {
		JNIEnv *env;
		jobject callback;
	} context{ env, progress };
	auto progress_fn = [](int64_t done, int64_t total, void *user) {
		Context *ctx = static_cast<Context *>(user);
		reportProgress(ctx->env, ctx->callback, done, total);
	};
	std::string error;
	int64_t result = utarcore::saveBackupZip(jstringToStd(env, root), owned, progress_fn, &context, error);
	closeFdGuard(&owned);
	if (result < 0) {
		setError(error.empty() ? "Backup failed." : error);
		return -1;
	}
	return result;
}

JNIEXPORT jlong JNICALL
Java_com_utar_runner_core_NativeCore_saveRestore(JNIEnv *env, jobject, jint fd, jstring root, jobject progress) {
	g_lastError.clear();
	int owned = fd;
	struct Context {
		JNIEnv *env;
		jobject callback;
	} context{ env, progress };
	auto progress_fn = [](int64_t done, int64_t total, void *user) {
		Context *ctx = static_cast<Context *>(user);
		reportProgress(ctx->env, ctx->callback, done, total);
	};
	std::string error;
	int64_t result = utarcore::saveRestoreZip(owned, jstringToStd(env, root), progress_fn, &context, error);
	closeFdGuard(&owned);
	if (result < 0) {
		setError(error.empty() ? "Restore failed." : error);
		return -1;
	}
	return result;
}

// --- pack warmup ------------------------------------------------------------------------

JNIEXPORT jboolean JNICALL
Java_com_utar_runner_core_NativeCore_warmup(JNIEnv *env, jobject, jstring path) {
	g_lastError.clear();
	if (!utarcore::warmupPack(jstringToStd(env, path))) {
		setError("Couldn't prefetch the pack.");
		return JNI_FALSE;
	}
	return JNI_TRUE;
}

// --- shader patch marker -------------------------------------------------------------------

JNIEXPORT jboolean JNICALL
Java_com_utar_runner_core_NativeCore_markerWrite(JNIEnv *env, jobject, jstring files_dir, jstring value) {
	g_lastError.clear();
	std::string dir = jstringToStd(env, files_dir);
	std::string patch_dir = dir + "/utar_patches";
	mkdir(patch_dir.c_str(), 0755);
	std::string marker_path = patch_dir + "/backend.txt";
	int fd = open(marker_path.c_str(), O_WRONLY | O_CREAT | O_TRUNC, 0644);
	if (fd < 0) {
		setError("Couldn't write the shader patch marker.");
		return JNI_FALSE;
	}
	std::string content = jstringToStd(env, value);
	bool ok = true;
	size_t done = 0;
	while (done < content.size()) {
		ssize_t put = write(fd, content.data() + done, content.size() - done);
		if (put <= 0) {
			ok = false;
			break;
		}
		done += size_t(put);
	}
	close(fd);
	if (!ok) {
		setError("Couldn't write the shader patch marker.");
	}
	return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_utar_runner_core_NativeCore_patcherClear(JNIEnv *env, jobject, jstring files_dir) {
	g_lastError.clear();
	std::string dir = jstringToStd(env, files_dir);
	bool ok = true;
	const char *names[] = { "utar_patches", "shader_cache" };
	for (const char *name : names) {
		std::string path = dir + "/" + name;
		DIR *handle = opendir(path.c_str());
		if (handle == nullptr) {
			continue;
		}
		while (dirent *entry = readdir(handle)) {
			std::string child = entry->d_name;
			if (child == "." || child == "..") {
				continue;
			}
			if (unlink((path + "/" + child).c_str()) != 0) {
				ok = false;
			}
		}
		closedir(handle);
		if (rmdir(path.c_str()) != 0) {
			ok = false;
		}
	}
	if (!ok) {
		setError("Couldn't clear the shader patch data.");
	}
	return ok ? JNI_TRUE : JNI_FALSE;
}


// --- md5 ------------------------------------------------------------------------

JNIEXPORT jbyteArray JNICALL
Java_com_utar_runner_core_NativeCore_md5(JNIEnv *env, jobject, jbyteArray data) {
	std::vector<uint8_t> bytes = jByteArrayToVector(env, data);
	uint8_t digest[16];
	utarcore::md5_buffer(bytes.empty() ? nullptr : bytes.data(), bytes.size(), digest);
	std::vector<uint8_t> out(digest, digest + 16);
	return vectorToJByteArray(env, out);
}

// --- font scan ------------------------------------------------------------------

JNIEXPORT jlong JNICALL
Java_com_utar_runner_core_NativeCore_pckFontScan(JNIEnv *env, jobject, jlong handle, jlong max_bytes) {
	g_lastError.clear();
	utarcore::PckFile *pck = reinterpret_cast<utarcore::PckFile *>(handle);
	if (pck == nullptr) {
		setError("Pack is not open.");
		return 0;
	}
	int count = 0;
	int bad = 0;
	for (size_t i = 0; i < pck->entryCount(); i++) {
		const utarcore::PckEntry &entry = pck->entry(i);
		std::string ext = lowerExt(entry.path);
		if (!isFontExt(ext)) continue;
		if (entry.isEncrypted() || entry.size <= 0) {
			nativeLog("Font " + entry.path + ": unreadable (encrypted or " + std::to_string(entry.size) + " bytes)");
			bad++;
			continue;
		}
		if ((int64_t)entry.size > max_bytes) {
			nativeLog("Font " + entry.path + ": skipped (too large to scan: " + std::to_string(entry.size) + " bytes)");
			continue;
		}
		std::vector<uint8_t> data;
		std::string error;
		if (!pck->readEntry(i, data, (uint64_t)max_bytes, error)) {
			nativeLog("Font " + entry.path + ": unreadable (read failed)");
			bad++;
			continue;
		}
		uint8_t digest[16];
		utarcore::md5_buffer(data.data(), data.size(), digest);
		bool integrity = (std::memcmp(digest, entry.md5, 16) == 0);
		std::string format = fontFormat(data);
		count++;
		if (!integrity) bad++;
		nativeLog("Font " + entry.path + ": " + std::to_string(data.size()) + "B format=" + format +
			" flags=" + std::to_string(entry.flags) + " integrity=" + (integrity ? "ok" : "MISMATCH"));
	}
	return ( (jlong)count << 32 ) | (jlong)(bad & 0xFFFFFFFF);
}

// --- script dump ----------------------------------------------------------------

JNIEXPORT jint JNICALL
Java_com_utar_runner_core_NativeCore_scriptDump(JNIEnv *env, jobject, jlong handle, jstring logs_dir, jint max_chars) {
	g_lastError.clear();
	utarcore::PckFile *pck = reinterpret_cast<utarcore::PckFile *>(handle);
	if (pck == nullptr) {
		setError("Pack is not open.");
		return -1;
	}
	std::string dir = jstringToStd(env, logs_dir);
	mkdir(dir.c_str(), 0755);
	std::string dump_file = dir + "/scripts-dump.txt";

	// collect scripts/scenes
	std::vector<size_t> scripts;
	std::vector<size_t> scenes;
	for (size_t i = 0; i < pck->entryCount(); i++) {
		const utarcore::PckEntry &e = pck->entry(i);
		if (e.path.size() >= 3 && e.path.compare(e.path.size()-3, 3, ".gd") == 0) {
			if (!e.isEncrypted() && e.size >= 1 && e.size <= 512*1024) scripts.push_back(i);
		}
		if (e.path.size() >= 5 && e.path.compare(e.path.size()-5, 5, ".tscn") == 0) {
			scenes.push_back(i);
		}
	}
	// sort by path
	auto byPath = [&](size_t a, size_t b){ return pck->entry(a).path < pck->entry(b).path; };
	std::sort(scripts.begin(), scripts.end(), byPath);
	std::sort(scenes.begin(), scenes.end(), byPath);

	std::ostringstream out;
	out << "=== Script index (" << scripts.size() << " scripts) ===\n";
	for (size_t i : scripts) out << pck->entry(i).path << " (" << pck->entry(i).size << "B)\n";
	out << "\n=== Scene index (" << scenes.size() << " scenes) ===\n";
	for (size_t i : scenes) out << pck->entry(i).path << " (" << pck->entry(i).size << "B)\n";
	out << "\n=== Sources ===\n";

	int sourceCount = 0;
	for (size_t i : scripts) {
		const utarcore::PckEntry &e = pck->entry(i);
		std::string path = e.path;
		bool interesting = (path.find("chat") != std::string::npos) ||
			(path.find("Chat") != std::string::npos) ||
			(path.find("messenger") != std::string::npos) ||
			(path.find("Messenger") != std::string::npos) ||
			(path.find("dialog") != std::string::npos) ||
			(path.find("Dialog") != std::string::npos) ||
			(path.find("SceneView") != std::string::npos) ||
			(path.find("ScreenNavigator") != std::string::npos) ||
			(path.size() >= 7 && path.compare(path.size()-7, 7, "/Game.gd") == 0);
		// case-insensitive contains for chat etc not necessary; keep simple contains with tolower? Use lower copy.
		if (!interesting) {
			// do case-insensitive check
			std::string lower = path;
			for (char &c : lower) c = (char)std::tolower((unsigned char)c);
			interesting = lower.find("chat") != std::string::npos ||
				lower.find("messenger") != std::string::npos ||
				lower.find("dialog") != std::string::npos ||
				lower.find("sceneview") != std::string::npos ||
				lower.find("screennavigator") != std::string::npos ||
				(lower.size() >= 7 && lower.compare(lower.size()-7,7,"/game.gd")==0);
		}
		if (!interesting) continue;
		std::vector<uint8_t> data;
		std::string error;
		if (!pck->readEntry(i, data, 512*1024, error)) continue;
		std::string source(data.begin(), data.end());
		out << "---- " << path << " ----\n";
		if ((int)source.size() > max_chars) {
			out << source.substr(source.size() - max_chars);
			out << "\n[... " << (source.size() - max_chars) << " earlier chars truncated ...]\n";
		} else {
			out << source << "\n";
		}
		out << "\n";
		sourceCount++;
	}

	std::ofstream f(dump_file, std::ios::trunc);
	if (!f) {
		setError("Couldn't write script dump.");
		return -1;
	}
	f << out.str();
	f.close();
	return sourceCount;
}


// --- file copy ------------------------------------------------------------------

JNIEXPORT jlong JNICALL
Java_com_utar_runner_core_NativeCore_copyFile(JNIEnv *env, jobject, jint src_fd, jstring dst_path, jlong expected_size, jobject progress) {
	g_lastError.clear();
	int owned = src_fd;
	int out = open(jstringToStd(env, dst_path).c_str(), O_WRONLY | O_CREAT | O_TRUNC, 0644);
	if (out < 0) {
		if (owned >= 0) close(owned);
		setError("Couldn't open the copy destination.");
		return -1;
	}
	const size_t BUF = 1 << 20;
	std::vector<char> buffer(BUF);
	int64_t copied = 0;
	bool ok = true;
	struct Context { JNIEnv *env; jobject callback; } context{ env, progress };
	while (true) {
		ssize_t r = read(owned, buffer.data(), buffer.size());
		if (r < 0) { ok = false; setError("Read failed during copy."); break; }
		if (r == 0) break;
		size_t w = 0;
		while (w < (size_t)r) {
			ssize_t put = write(out, buffer.data() + w, (size_t)r - w);
			if (put <= 0) { ok = false; setError("Write failed during copy."); break; }
			w += (size_t)put;
		}
		if (!ok) break;
		copied += r;
		reportProgress(context.env, context.callback, copied, expected_size > 0 ? expected_size : -1);
	}
	close(out);
	close(owned);
	if (!ok) return -1;
	return copied;
}

} // extern "C"
