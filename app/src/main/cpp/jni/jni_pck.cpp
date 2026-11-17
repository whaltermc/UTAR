#include "jni_common.h"
#include "pck_engine.h"

#include <algorithm>
#include <sys/stat.h>
#include <unistd.h>
#include <fcntl.h>

extern "C" {


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

} // extern "C"
