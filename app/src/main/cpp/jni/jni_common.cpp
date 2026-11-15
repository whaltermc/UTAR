#include "jni_common.h"

#include <algorithm>
#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>
#include <dirent.h>

std::string g_lastError;

void setError(const std::string &message) { g_lastError = message; }

void nativeLog(const std::string &message) {
#ifdef __ANDROID__
	__android_log_print(ANDROID_LOG_INFO, "UTAR", "%s", message.c_str());
#else
	std::printf("%s\n", message.c_str());
#endif
}

std::string jstringToStd(JNIEnv *env, jstring value) {
	if (value == nullptr) return std::string();
	const char *chars = env->GetStringUTFChars(value, nullptr);
	std::string result(chars == nullptr ? std::string() : std::string(chars));
	if (chars != nullptr) env->ReleaseStringUTFChars(value, chars);
	return result;
}

jstring stdToJString(JNIEnv *env, const std::string &value) {
	return env->NewStringUTF(value.c_str());
}

jbyteArray vectorToJByteArray(JNIEnv *env, const std::vector<uint8_t> &data) {
	if (data.empty()) return env->NewByteArray(0);
	jbyteArray array = env->NewByteArray(jsize(data.size()));
	if (array == nullptr) return nullptr;
	env->SetByteArrayRegion(array, 0, jsize(data.size()), reinterpret_cast<const jbyte *>(data.data()));
	return array;
}

std::vector<uint8_t> jByteArrayToVector(JNIEnv *env, jbyteArray array) {
	if (array == nullptr) return {};
	jsize length = env->GetArrayLength(array);
	std::vector<uint8_t> data(length);
	if (length > 0) env->GetByteArrayRegion(array, 0, length, reinterpret_cast<jbyte *>(data.data()));
	return data;
}

std::vector<std::string> jStringArrayToVector(JNIEnv *env, jobjectArray array) {
	std::vector<std::string> result;
	if (array == nullptr) return result;
	jsize length = env->GetArrayLength(array);
	result.reserve(size_t(length));
	for (jsize i = 0; i < length; i++) {
		jstring element = static_cast<jstring>(env->GetObjectArrayElement(array, i));
		result.push_back(jstringToStd(env, element));
		if (element != nullptr) env->DeleteLocalRef(element);
	}
	return result;
}

std::vector<std::vector<uint8_t>> jByteArray2ToVector(JNIEnv *env, jobjectArray array) {
	std::vector<std::vector<uint8_t>> result;
	if (array == nullptr) return result;
	jsize length = env->GetArrayLength(array);
	result.reserve(size_t(length));
	for (jsize i = 0; i < length; i++) {
		jbyteArray element = static_cast<jbyteArray>(env->GetObjectArrayElement(array, i));
		result.push_back(jByteArrayToVector(env, element));
		if (element != nullptr) env->DeleteLocalRef(element);
	}
	return result;
}

void reportProgress(JNIEnv *env, jobject callback, int64_t done, int64_t total) {
	if (callback == nullptr) return;
	jclass klass = env->GetObjectClass(callback);
	if (klass == nullptr) return;
	jmethodID method = env->GetMethodID(klass, "onProgress", "(JJ)V");
	if (method != nullptr) env->CallVoidMethod(callback, method, jlong(done), jlong(total));
	env->DeleteLocalRef(klass);
}

std::vector<std::pair<std::string, std::vector<uint8_t>>> pairUp(
		const std::vector<std::string> &paths, const std::vector<std::vector<uint8_t>> &payloads) {
	std::vector<std::pair<std::string, std::vector<uint8_t>>> result;
	size_t count = std::min(paths.size(), payloads.size());
	result.reserve(count);
	for (size_t i = 0; i < count; i++) result.emplace_back(paths[i], payloads[i]);
	return result;
}

void closeFdGuard(int *fd) {
	if (fd && *fd >= 0) { close(*fd); *fd = -1; }
}

std::string lowerExt(const std::string &path) {
	size_t pos = path.find_last_of('.');
	if (pos == std::string::npos) return "";
	std::string ext = path.substr(pos + 1);
	for (char &c : ext) c = (char)std::tolower((unsigned char)c);
	return ext;
}

bool isFontExt(const std::string &ext) {
	static const std::set<std::string> exts = { "ttf","otf","ttc","otc","woff","woff2","fnt","fontdata" };
	return exts.count(ext) > 0;
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

bool isFontPath(const std::string &path) {
	std::string lower = path;
	for (char &c : lower) c = (char)std::tolower((unsigned char)c);
	const size_t n = lower.size();
	return (n >= 4 && lower.compare(n - 4, 4, ".ttf") == 0) ||
		(n >= 4 && lower.compare(n - 4, 4, ".otf") == 0) ||
		(n >= 4 && lower.compare(n - 4, 4, ".ttc") == 0) ||
		(n >= 5 && lower.compare(n - 5, 5, ".woff") == 0) ||
		(n >= 6 && lower.compare(n - 6, 6, ".woff2") == 0) ||
		(n >= 9 && lower.compare(n - 9, 9, ".fontdata") == 0);
}
