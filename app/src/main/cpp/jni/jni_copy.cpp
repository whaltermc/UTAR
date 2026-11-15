#include "jni_common.h"

#include <algorithm>
#include <fstream>
#include <set>
#include <sys/stat.h>
#include <unistd.h>
#include <dirent.h>
#include <fcntl.h>

extern "C" {



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
