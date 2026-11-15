#include "jni_common.h"
#include "settings_override.h"

#include <algorithm>
#include <fstream>
#include <set>
#include <sys/stat.h>
#include <unistd.h>
#include <dirent.h>
#include <fcntl.h>

extern "C" {


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
} // extern "C"
