#include "jni_common.h"

#include <cstdlib>

extern "C" {

JNIEXPORT jstring JNICALL
Java_com_utar_runner_core_NativeCore_lastError(JNIEnv *env, jobject) {
	return stdToJString(env, g_lastError);
}

JNIEXPORT jboolean JNICALL
Java_com_utar_runner_core_NativeCore_setEnv(JNIEnv *env, jobject, jstring name, jstring value) {
	std::string n = jstringToStd(env, name);
	std::string v = jstringToStd(env, value);
	if (setenv(n.c_str(), v.c_str(), 1) != 0) {
		return JNI_FALSE;
	}
	return JNI_TRUE;
}
} // extern "C"
