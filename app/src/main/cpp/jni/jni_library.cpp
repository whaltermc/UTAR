#include "jni_common.h"
#include "library_set.h"

#include <algorithm>
#include <fstream>
#include <set>
#include <sys/stat.h>
#include <unistd.h>
#include <dirent.h>
#include <fcntl.h>

extern "C" {


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
} // extern "C"
