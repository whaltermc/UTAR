#include "jni_common.h"

#include <algorithm>
#include <fstream>
#include <set>
#include <sys/stat.h>
#include <unistd.h>
#include <dirent.h>
#include <fcntl.h>

extern "C" {



// --- native library identity -------------------------------------------------

JNIEXPORT jstring JNICALL
Java_com_utar_runner_core_NativeCore_lastError(JNIEnv *env, jobject) {
	return stdToJString(env, g_lastError);
}
} // extern "C"
