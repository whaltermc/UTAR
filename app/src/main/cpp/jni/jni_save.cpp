#include "jni_common.h"
#include "save_zip.h"

#include <algorithm>
#include <fstream>
#include <set>
#include <sys/stat.h>
#include <unistd.h>
#include <dirent.h>
#include <fcntl.h>

extern "C" {


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
} // extern "C"
