#include "jni_common.h"
#include "gdext_rewriter.h"
#include "library_set.h"

#include <algorithm>
#include <fstream>
#include <set>
#include <sys/stat.h>
#include <unistd.h>
#include <dirent.h>
#include <fcntl.h>

extern "C" {


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
} // extern "C"
