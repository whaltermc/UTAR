#ifndef UTARCORE_JNI_COMMON_H
#define UTARCORE_JNI_COMMON_H

#include <jni.h>

#include <string>
#include <vector>
#include <set>
#include <map>
#include <sstream>
#include <cctype>
#include <cstdio>
#include <cstring>
#include <android/log.h>

namespace utarcore { } // placeholder

extern std::string g_lastError;
void setError(const std::string &message);
void nativeLog(const std::string &message);

std::string jstringToStd(JNIEnv *env, jstring value);
jstring stdToJString(JNIEnv *env, const std::string &value);
jbyteArray vectorToJByteArray(JNIEnv *env, const std::vector<uint8_t> &data);
std::vector<uint8_t> jByteArrayToVector(JNIEnv *env, jbyteArray array);
std::vector<std::string> jStringArrayToVector(JNIEnv *env, jobjectArray array);
std::vector<std::vector<uint8_t>> jByteArray2ToVector(JNIEnv *env, jobjectArray array);
void reportProgress(JNIEnv *env, jobject callback, int64_t done, int64_t total);
std::vector<std::pair<std::string, std::vector<uint8_t>>> pairUp(
		const std::vector<std::string> &paths, const std::vector<std::vector<uint8_t>> &payloads);
void closeFdGuard(int *fd);

std::string lowerExt(const std::string &path);

#endif // UTARCORE_JNI_COMMON_H
