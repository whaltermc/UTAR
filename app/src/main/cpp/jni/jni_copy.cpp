#include "jni_common.h"

#include <cerrno>
#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>
#include <vector>

#include "sparse_copy.h"

extern "C" {

// --- file copy ------------------------------------------------------------------

/**
 * Copies the pack selected by the user into private storage. The source is normally a regular
 * (seekable) file, so the copy is sparse-aware: only allocated extents are written and the
 * destination is sized with ftruncate(), which keeps zero-filled regions as holes instead of
 * writing gigabytes of zeros. Non-regular sources (pipes) fall back to a plain read/write loop.
 */
JNIEXPORT jlong JNICALL
Java_com_utar_runner_core_NativeCore_copyFile(JNIEnv *env, jobject, jint src_fd, jstring dst_path, jlong expected_size, jobject progress) {
	g_lastError.clear();
	int owned = src_fd;
	std::string destination = jstringToStd(env, dst_path);
	int out = open(destination.c_str(), O_WRONLY | O_CREAT | O_TRUNC, 0644);
	if (out < 0) {
		if (owned >= 0) close(owned);
		setError("Couldn't open the copy destination.");
		return -1;
	}
	struct Context {
		JNIEnv *env;
		jobject callback;
		int64_t total;
	} context{ env, progress, expected_size };
	auto progress_fn = [](int64_t processed, void *user) {
		Context *ctx = static_cast<Context *>(user);
		reportProgress(ctx->env, ctx->callback, processed, ctx->total > 0 ? ctx->total : -1);
	};

	struct stat source_info{};
	bool regular = fstat(owned, &source_info) == 0 && S_ISREG(source_info.st_mode);
	int64_t copied = -1;
	std::string error;

	if (regular) {
		const uint64_t length = uint64_t(source_info.st_size);
		const bool sparse = utarcore::fileIsSparse(owned);
		context.total = int64_t(length);
		uint64_t written = 0;
		if (utarcore::sparseCopyRange(owned, out, 0, 0, length, sparse, progress_fn, &context, written, error) &&
				ftruncate(out, off_t(length)) == 0) {
			copied = int64_t(length);
		} else if (error.empty()) {
			error = "Couldn't finalise the copied pack.";
		}
	} else {
		const size_t BUF = 1 << 20;
		std::vector<char> buffer(BUF);
		int64_t total = 0;
		bool ok = true;
		while (true) {
			ssize_t r = read(owned, buffer.data(), buffer.size());
			if (r < 0 && errno == EINTR) continue;
			if (r < 0) { ok = false; error = "Read failed during copy."; break; }
			if (r == 0) break;
			size_t w = 0;
			while (w < (size_t)r) {
				ssize_t put = write(out, buffer.data() + w, (size_t)r - w);
				if (put <= 0) { ok = false; error = "Write failed during copy."; break; }
				w += (size_t)put;
			}
			if (!ok) break;
			total += r;
			reportProgress(context.env, context.callback, total, expected_size > 0 ? expected_size : -1);
		}
		if (ok) copied = total;
	}

	close(out);
	close(owned);
	if (copied < 0) {
		setError(error.empty() ? "Copy failed." : error);
		return -1;
	}
	return jlong(copied);
}
} // extern "C"
